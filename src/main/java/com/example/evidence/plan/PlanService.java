package com.example.evidence.plan;

import com.example.evidence.chain.EvidenceEvent;
import com.example.evidence.chain.EvidenceEventRepository;
import com.example.evidence.chain.FailureInjector;
import com.example.evidence.chain.HashChain;
import com.example.evidence.domain.EventType;
import com.example.evidence.domain.PlanStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Maintenance-plan use cases.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>every state change appends exactly one hash-chained event in the
 *       <b>same database transaction</b> as the status update, so the two can
 *       never commit independently;</li>
 *   <li>each client request carries an idempotency key (header or body);
 *       replays return the original outcome and never create a second event or
 *       a second business effect, even across restarts (the key lives in the
 *       database, not in memory);</li>
 *   <li>same key with a different body is rejected as a conflict;</li>
 *   <li>per-plan row locks (SELECT ... FOR UPDATE) plus a (plan_id, seq)
 *       unique constraint serialize concurrent writers.</li>
 * </ul>
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final EvidenceEventRepository eventRepository;
    private final TransactionalBoundary boundary;

    public PlanService(PlanRepository planRepository,
                       EvidenceEventRepository eventRepository,
                       TransactionalBoundary boundary) {
        this.planRepository = planRepository;
        this.eventRepository = eventRepository;
        this.boundary = boundary;
    }

    public record CreatePlanCommand(String requestId, String planNo, String title) {
        CreatePlanCommand withRequestId(String newRequestId) {
            return new CreatePlanCommand(newRequestId, planNo, title);
        }
    }

    public record TransitionCommand(String requestId, long planId, PlanAction action,
                                    Map<String, Object> payload) {
        TransitionCommand withRequestId(String newRequestId) {
            return new TransitionCommand(newRequestId, planId, action, payload);
        }
    }

    public record PlanOperationResult(PlanRepository.PlanRow plan, EvidenceEvent event, boolean replayed) {
    }

    /** Non-transactional entry point: handles idempotent replay around the tx boundary. */
    public PlanOperationResult createPlan(CreatePlanCommand cmd) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw PlanException.invalidRequest("title is required");
        }
        String requestId = normalizeRequestId(cmd.requestId());
        EvidenceEvent existing = eventRepository.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            ensureFingerprint(existing, fingerprintCreate(cmd));
            return replay(existing);
        }
        // The unique(request_id) / unique(plan_no) constraints can still fire if a
        // concurrent request commits between the read above and our insert.
        for (int attempt = 0; ; attempt++) {
            try {
                return boundary.createPlan(cmd.withRequestId(requestId));
            } catch (DuplicateRequestRace race) {
                EvidenceEvent winner = eventRepository.findByRequestId(requestId).orElse(null);
                if (winner != null) {
                    ensureFingerprint(winner, fingerprintCreate(cmd));
                    return replay(winner);
                }
                // no winner for our key: a different request owns the plan_no
                if (cmd.planNo() != null && !cmd.planNo().isBlank()
                        && planRepository.findByPlanNo(cmd.planNo()).isPresent()) {
                    throw PlanException.invalidTransition("plan number already exists: " + cmd.planNo());
                }
                if (attempt >= 2) {
                    throw new IllegalStateException("unresolvable concurrent requestId race", race);
                }
            }
        }
    }

    /** Non-transactional entry point: handles idempotent replay around the tx boundary. */
    public PlanOperationResult transition(TransitionCommand cmd) {
        String requestId = normalizeRequestId(cmd.requestId());
        EvidenceEvent existing = eventRepository.findByRequestId(requestId).orElse(null);
        if (existing != null) {
            ensureFingerprint(existing, fingerprintAction(cmd));
            return replay(existing);
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return boundary.transition(cmd.withRequestId(requestId));
            } catch (DuplicateRequestRace race) {
                EvidenceEvent winner = eventRepository.findByRequestId(requestId).orElse(null);
                if (winner != null) {
                    ensureFingerprint(winner, fingerprintAction(cmd));
                    return replay(winner);
                }
                if (attempt >= 2) {
                    throw new IllegalStateException("unresolvable concurrent requestId race", race);
                }
            }
        }
    }

    public List<EvidenceEvent> events(long planId) {
        planRepository.findById(planId)
                .orElseThrow(() -> PlanException.notFound("plan not found: " + planId));
        return eventRepository.findByPlanOrdered(planId);
    }

    private PlanOperationResult replay(EvidenceEvent event) {
        PlanRepository.PlanRow plan = planRepository.findById(event.planId())
                .orElseThrow(() -> new IllegalStateException(
                        "evidence event " + event.seq() + " refers to missing plan " + event.planId()));
        return new PlanOperationResult(plan, event, true);
    }

    static void ensureFingerprint(EvidenceEvent existing, String fingerprint) {
        if (!fingerprint.equals(existing.requestFingerprint())) {
            throw PlanException.idempotencyConflict(
                    "requestId " + existing.requestId()
                            + " was already used with a different request body");
        }
    }

    static String fingerprintCreate(CreatePlanCommand cmd) {
        Map<String, Object> body = new TreeMap<>();
        body.put("title", cmd.title());
        if (cmd.planNo() != null && !cmd.planNo().isBlank()) {
            body.put("planNo", cmd.planNo());
        }
        return fingerprint("CREATE", body);
    }

    static String fingerprintAction(TransitionCommand cmd) {
        Map<String, Object> body = new TreeMap<>();
        body.put("planId", cmd.planId());
        body.put("action", cmd.action().name());
        body.put("payload", cmd.payload() == null ? Map.of() : new TreeMap<>(cmd.payload()));
        return fingerprint("ACTION", body);
    }

    private static String fingerprint(String scope, Map<String, Object> body) {
        Map<String, Object> envelope = new TreeMap<>();
        envelope.put("scope", scope);
        envelope.put("body", body);
        return HashChain.sha256Hex(HashChain.canonicalJsonStatic(envelope));
    }

    private static String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            // Clients SHOULD pass their own key; without one the call is simply
            // not retryable, so mint a fresh key.
            return UUID.randomUUID().toString();
        }
        return requestId.trim();
    }

    /** Signals that another transaction already committed the same requestId. */
    static class DuplicateRequestRace extends RuntimeException {
    }

    /**
     * All writes live here in methods demarcated by a single Spring transaction.
     * Evidence insert and business-state update therefore commit or roll back
     * together; an injected fault after either statement proves rollback.
     */
    @Service
    public static class TransactionalBoundary {

        private final PlanRepository planRepository;
        private final EvidenceEventRepository eventRepository;
        private final HashChain hashChain;
        private final FailureInjector failureInjector;

        TransactionalBoundary(PlanRepository planRepository,
                              EvidenceEventRepository eventRepository,
                              HashChain hashChain,
                              FailureInjector failureInjector) {
            this.planRepository = planRepository;
            this.eventRepository = eventRepository;
            this.hashChain = hashChain;
            this.failureInjector = failureInjector;
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public PlanOperationResult createPlan(CreatePlanCommand cmd) {
            // Re-check inside the lock/transaction to close the check-then-act gap.
            eventRepository.findByRequestId(cmd.requestId()).ifPresent(e -> {
                ensureFingerprint(e, fingerprintCreate(cmd));
                throw new DuplicateRequestRace();
            });

            String planNo = (cmd.planNo() == null || cmd.planNo().isBlank())
                    ? "PL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase()
                    : cmd.planNo();
            if (planRepository.findByPlanNo(planNo).isPresent()) {
                throw PlanException.invalidTransition("plan number already exists: " + planNo);
            }
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            long planId;
            try {
                planId = planRepository.insert(planNo, cmd.title(), PlanStatus.DRAFT.name(), "NEW", null, now);
            } catch (org.springframework.dao.DataIntegrityViolationException dupPlanNo) {
                // Most likely a concurrent request with the same idempotency key
                // won the plan_no unique race: let the outer loop replay the winner.
                throw new DuplicateRequestRace();
            }
            PlanRepository.PlanRow plan = planRepository.findById(planId).orElseThrow();

            Map<String, Object> payload = new TreeMap<>();
            payload.put("planNo", planNo);
            payload.put("title", cmd.title());

            EvidenceEvent event = appendEvent(plan, 1L, EventType.CREATED, null, PlanStatus.DRAFT,
                    payload, cmd.requestId(), fingerprintCreate(cmd), now);
            failureInjector.afterStateUpdate(planId);
            return new PlanOperationResult(plan, event, false);
        }

        @Transactional(propagation = Propagation.REQUIRED)
        public PlanOperationResult transition(TransitionCommand cmd) {
            // Acquire the plan row lock FIRST: a concurrent request with the same
            // idempotency key that is already in flight holds this lock until its
            // commit, so once we proceed we can observe its committed event and
            // turn this call into a replay instead of failing on stale state.
            PlanRepository.PlanRow plan = planRepository.findByIdForUpdate(cmd.planId())
                    .orElseThrow(() -> PlanException.notFound("plan not found: " + cmd.planId()));

            eventRepository.findByRequestId(cmd.requestId()).ifPresent(e -> {
                ensureFingerprint(e, fingerprintAction(cmd));
                throw new DuplicateRequestRace();
            });

            PlanStatus current = PlanStatus.valueOf(plan.status());
            if (!cmd.action().isAllowedFrom(current)) {
                throw PlanException.invalidTransition(
                        "action " + cmd.action() + " not allowed in state " + current
                                + " (plan " + cmd.planId() + ")");
            }

            PlanStatus next = cmd.action().to();
            long seq = eventRepository.countByPlan(cmd.planId()) + 1;
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            Map<String, Object> payload = cmd.payload() == null
                    ? new TreeMap<>() : new TreeMap<>(cmd.payload());
            if (cmd.action() == PlanAction.RECEIPT) {
                // Deterministic simulated receipt number produced in-transaction.
                payload.put("receiptNo", "SIM-" + cmd.planId() + "-" + seq);
                payload.putIfAbsent("simulated", true);
            }

            EvidenceEvent event = appendEvent(plan, seq, cmd.action().eventType(), current, next,
                    payload, cmd.requestId(), fingerprintAction(cmd), now);

            // Evidence row is already inserted; if this update fails the whole
            // transaction rolls the evidence insert back too.
            planRepository.updateStatus(cmd.planId(), next.name(), now);
            failureInjector.afterStateUpdate(cmd.planId());

            PlanRepository.PlanRow updated = planRepository.findById(cmd.planId()).orElseThrow();
            return new PlanOperationResult(updated, event, false);
        }

        private EvidenceEvent appendEvent(PlanRepository.PlanRow plan, long seq, EventType eventType,
                                          PlanStatus before, PlanStatus after, Map<String, Object> payload,
                                          String requestId, String fingerprint, Instant now) {
            List<EvidenceEvent> prior = eventRepository.findByPlanOrdered(plan.id());
            String prevHash = prior.isEmpty()
                    ? HashChain.GENESIS_PREV_HASH
                    : prior.get(prior.size() - 1).eventHash();

            String hash = hashChain.computeHash(new HashChain.HashInput(
                    plan.id(), seq, eventType.name(),
                    before == null ? null : before.name(), after.name(),
                    prevHash, now.toEpochMilli(), payload));

            try {
                eventRepository.insert(new EvidenceEventRepository.NewEvent(
                        plan.id(), seq, eventType.name(),
                        before == null ? null : before.name(), after.name(),
                        payload, requestId, fingerprint, prevHash, hash, now));
            } catch (org.springframework.dao.DataIntegrityViolationException race) {
                // Unique (request_id) or (plan_id, seq) lost a race: abort this tx,
                // the outer non-transactional method replays the winner.
                throw new DuplicateRequestRace();
            }

            failureInjector.afterEvidenceInsert(plan.id());
            return new EvidenceEvent(0L, plan.id(), seq, eventType.name(),
                    before == null ? null : before.name(), after.name(),
                    new LinkedHashMap<>(payload), requestId, fingerprint, prevHash, hash, now);
        }
    }
}
