package com.example.evidencechain.service;

import com.example.evidencechain.domain.EventType;
import com.example.evidencechain.domain.PlanStatus;
import com.example.evidencechain.repository.BusinessRecordDao;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.repository.EvidenceEventDao.EventRow;
import com.example.evidencechain.repository.PlanDao;
import com.example.evidencechain.repository.PlanDao.PlanRow;
import com.example.evidencechain.support.FailureSimulator;
import com.example.evidencechain.support.Hashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies lifecycle commands to plans.
 *
 * <h2>Invariants guaranteed by every mutating method</h2>
 * <ol>
 *   <li><b>Atomicity:</b> business record insert, plan status update and
 *       evidence event append execute in one database transaction. A crash
 *       or failure can never leave a state change without its evidence event
 *       or an evidence event without the state change.</li>
 *   <li><b>Ordering:</b> the plan row and the last evidence row are locked
 *       ({@code SELECT ... FOR UPDATE}); {@code seq} and {@code prev_hash}
 *       are derived from the locked tail, so events for a plan are strictly
 *       sequential even under concurrency.</li>
 *   <li><b>Idempotency:</b> the {@code (plan_id, idempotency_key)} unique
 *       key identifies a command; replays return the original result and
 *       create nothing. A key reused for a different command is rejected.</li>
 * </ol>
 */
@Service
public class EvidenceService {

    private final PlanDao planDao;
    private final EvidenceEventDao eventDao;
    private final BusinessRecordDao recordDao;
    private final FailureSimulator failureSimulator;

    public EvidenceService(PlanDao planDao, EvidenceEventDao eventDao,
                           BusinessRecordDao recordDao, FailureSimulator failureSimulator) {
        this.planDao = planDao;
        this.eventDao = eventDao;
        this.recordDao = recordDao;
        this.failureSimulator = failureSimulator;
    }

    /**
     * Creates a new DRAFT plan and its genesis {@code CREATE} evidence event
     * in one transaction. Every plan therefore always has evidence from the
     * instant it exists.
     */
    @Transactional
    public CommandResult createPlan(String planNo, String title, String creator,
                                    String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required to create a plan");
        }
        PlanRow existing = planDao.findByPlanNo(planNo);
        if (existing != null) {
            EventRow prior = eventDao.findByIdempotencyKey(existing.id(), idempotencyKey);
            if (prior != null && prior.eventType() == EventType.CREATE) {
                // Idempotent replay of the same create request.
                return new CommandResult(existing.id(), null, existing.status(),
                        prior.seq(), prior.eventHash(), prior.prevHash(), null, true);
            }
            throw new IdempotencyConflictException(
                    "Plan " + planNo + " already exists (id=" + existing.id() + ")");
        }

        Instant now = Instant.now();
        long planId = planDao.insert(planNo, PlanStatus.DRAFT, title, now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", EventType.CREATE.name());
        payload.put("eventTime", Hashing.formatInstant(now));
        payload.put("operator", nullIfBlank(creator));
        payload.put("planNo", planNo);
        payload.put("title", title);

        EventRow event = appendEvent(planId, EventType.CREATE, null, PlanStatus.DRAFT,
                idempotencyKey, false, payload, now, null);
        return new CommandResult(planId, null, PlanStatus.DRAFT, event.seq(),
                event.eventHash(), event.prevHash(), null, false);
    }

    @Transactional
    public CommandResult execute(PlanCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for every command");
        }

        PlanRow plan = planDao.lockById(command.planId());
        if (plan == null) {
            throw new IllegalArgumentException("Plan not found: " + command.planId());
        }

        // Idempotency re-check inside the lock. A previous committed event
        // with the same key means this is a replay.
        EventRow existing = eventDao.findByIdempotencyKey(plan.id(), command.idempotencyKey());
        if (existing != null) {
            return replay(command, plan, existing);
        }

        PlanStatus from = plan.status();
        PlanStatus to = targetStatus(from, command.eventType());
        Instant now = Instant.now();

        Map<String, Object> payload = buildPayload(command, now);
        Long recordId = insertBusinessRecord(command, now);

        EventRow event = appendEvent(plan.id(), command.eventType(), from, to,
                command.idempotencyKey(), false, payload, now, recordId);

        planDao.updateStatus(plan.id(), to, now);

        // Hook used by failure-recovery tests: throwing here rolls back
        // status update, business row and evidence event together.
        failureSimulator.faultPoint();

        return new CommandResult(plan.id(), from, to, event.seq(),
                event.eventHash(), event.prevHash(), recordId, false);
    }

    private CommandResult replay(PlanCommand command, PlanRow plan, EventRow existing) {
        if (existing.eventType() != command.eventType()) {
            throw new IdempotencyConflictException(
                    "Idempotency key '" + command.idempotencyKey() + "' was already used "
                            + "for event " + existing.eventType()
                            + " (seq=" + existing.seq() + ") but was replayed as "
                            + command.eventType());
        }
        // No INSERT, no UPDATE: pure replay of the original outcome, including
        // the original business row id stored on the event.
        return new CommandResult(plan.id(), existing.fromStatus(), existing.toStatus(),
                existing.seq(), existing.eventHash(), existing.prevHash(),
                existing.businessRecordId(), true);
    }

    /**
     * Appends an anchor event for a historical plan. Must run in the caller's
     * transaction together with any bookkeeping (snapshot marking).
     */
    public EventRow appendAnchor(long planId, PlanStatus fromStatus, PlanStatus anchoredTo,
                                 String anchorKey, Map<String, Object> anchorPayload,
                                 Instant eventTime) {
        return appendEvent(planId, EventType.ANCHOR, fromStatus, anchoredTo,
                anchorKey, true, anchorPayload, eventTime, null);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private EventRow appendEvent(long planId, EventType type, PlanStatus from, PlanStatus to,
                                 String idempotencyKey, boolean anchor,
                                 Map<String, Object> payload, Instant eventTime,
                                 Long businessRecordId) {
        EventRow tail = eventDao.lockLast(planId);
        int seq = tail == null ? 1 : tail.seq() + 1;
        String prevHash = tail == null ? com.example.evidencechain.domain.EvidenceEvent.GENESIS_HASH
                : tail.eventHash();

        String payloadJson = Hashing.canonicalJson(payload);
        String payloadHash = Hashing.sha256Hex(payloadJson);
        String canonical = Hashing.buildEventCanonical(seq, planId, type.name(),
                from == null ? null : from.name(), to.name(), prevHash, eventTime, payloadHash);
        String eventHash = Hashing.sha256Hex(canonical);

        EventRow row = new EventRow(0, planId, seq, type, from, to,
                prevHash, eventHash, payloadJson, payloadHash,
                businessRecordId, idempotencyKey, anchor, eventTime, eventTime);
        long id = eventDao.append(row);
        return new EventRow(id, planId, seq, type, from, to,
                prevHash, eventHash, payloadJson, payloadHash,
                businessRecordId, idempotencyKey, anchor, eventTime, eventTime);
    }

    private Long insertBusinessRecord(PlanCommand c, Instant at) {
        return switch (c.eventType()) {
            case START, CREATE -> null;
            case CONFIRMATION ->
                    recordDao.insertConfirmation(c.planId(), c.operator(), c.remark(),
                            c.idempotencyKey(), at);
            case MOCK_RECEIPT ->
                    recordDao.insertMockReceipt(c.planId(), c.channel(), c.receiptPayload(),
                            c.idempotencyKey(), at);
            case REJECTION ->
                    recordDao.insertRejection(c.planId(), c.reason(), c.operator(),
                            c.idempotencyKey(), at);
            case WRITEOFF ->
                    recordDao.insertWriteoff(c.planId(), c.operator(), c.remark(),
                            c.idempotencyKey(), at);
            case ANCHOR ->
                    throw new IllegalArgumentException("ANCHOR is not a command");
        };
    }

    private Map<String, Object> buildPayload(PlanCommand c, Instant at) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", c.eventType().name());
        payload.put("eventTime", Hashing.formatInstant(at));
        payload.put("operator", nullIfBlank(c.operator()));
        switch (c.eventType()) {
            case CONFIRMATION -> payload.put("remark", nullIfBlank(c.remark()));
            case MOCK_RECEIPT -> {
                payload.put("channel", c.channel());
                payload.put("receiptPayload", c.receiptPayload());
            }
            case REJECTION -> payload.put("reason", c.reason());
            case WRITEOFF -> payload.put("remark", nullIfBlank(c.remark()));
            default -> { /* START: no extra fields */ }
        }
        return payload;
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private PlanStatus targetStatus(PlanStatus from, EventType type) {
        return switch (type) {
            case START -> require(from, PlanStatus.DRAFT, type, PlanStatus.STARTED);
            case CONFIRMATION -> require(from, PlanStatus.STARTED, type, PlanStatus.CONFIRMED);
            case MOCK_RECEIPT -> require(from, PlanStatus.CONFIRMED, type, PlanStatus.RECEIPTED);
            case REJECTION -> {
                if (Set.of(PlanStatus.STARTED, PlanStatus.CONFIRMED, PlanStatus.RECEIPTED).contains(from)) {
                    yield PlanStatus.REJECTED;
                }
                throw new IllegalTransitionException(
                        "Cannot reject a plan in state " + from);
            }
            case WRITEOFF -> require(from, PlanStatus.RECEIPTED, type, PlanStatus.WRITTEN_OFF);
            case ANCHOR, CREATE ->
                    throw new IllegalArgumentException(type + " is not a lifecycle command");
        };
    }

    private PlanStatus require(PlanStatus from, PlanStatus expected, EventType type, PlanStatus to) {
        if (from != expected) {
            throw new IllegalTransitionException(
                    "Cannot apply " + type + " to plan in state " + from
                            + "; required state is " + expected);
        }
        return to;
    }

    /** Read-only: all events of a plan in chain order. */
    @Transactional(readOnly = true)
    public List<EventRow> chainOf(long planId) {
        return eventDao.findByPlanOrderBySeq(planId);
    }
}
