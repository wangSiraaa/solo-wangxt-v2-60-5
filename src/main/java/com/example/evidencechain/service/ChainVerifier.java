package com.example.evidencechain.service;

import com.example.evidencechain.domain.EventType;
import com.example.evidencechain.domain.PlanStatus;
import com.example.evidencechain.repository.BusinessRecordDao;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.repository.EvidenceEventDao.EventRow;
import com.example.evidencechain.repository.PlanDao;
import com.example.evidencechain.repository.PlanDao.PlanRow;
import com.example.evidencechain.support.Hashing;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Recomputes every hash link of a plan's evidence chain and cross-checks the
 * business state. Verification is read-only and never mutates data.
 *
 * <p>Checks stop at the first broken event so callers can pinpoint the exact
 * <em>first break point</em>; all checks evaluated before the break are
 * returned in {@link VerificationResult#checks()}.
 *
 * <p>Per-event checks:
 * <ol>
 *   <li>{@code seq} continuity (1, 2, 3, ...)</li>
 *   <li>genesis: seq=1 must link to the 64-zero GENESIS_HASH; later events
 *       must link the previous event's {@code eventHash}</li>
 *   <li>{@code payloadHash} == sha256(stored payloadJson) and the stored JSON
 *       is canonical (detects payload edits even when the hash column is
 *       recomputed, because hand-edited JSON loses canonical key order)</li>
 *   <li>{@code eventHash} == sha256(canonical(seq, planId, type, from, to,
 *       prevHash, eventTime, payloadHash))</li>
 *   <li>status chaining: {@code fromStatus} equals previous {@code toStatus};
 *       the first event must be ANCHOR or START (DRAFT -&gt; STARTED)</li>
 *   <li>allowed state transitions and type/status combinations</li>
 *   <li>for event types with a business row, {@code business_record_id} must
 *       point at an existing row of the matching table</li>
 * </ol>
 * Global checks: business table counts match per-type event counts; the
 * plan's current status equals the tail event's {@code toStatus} (no "state
 * without evidence"); a plan with zero events fails as an unanchored chain.
 */
@Service
public class ChainVerifier {

    private final PlanDao planDao;
    private final EvidenceEventDao eventDao;
    private final BusinessRecordDao recordDao;

    public ChainVerifier(PlanDao planDao, EvidenceEventDao eventDao,
                         BusinessRecordDao recordDao) {
        this.planDao = planDao;
        this.eventDao = eventDao;
        this.recordDao = recordDao;
    }

    @Transactional(readOnly = true)
    public VerificationResult verify(long planId) {
        PlanRow plan = planDao.findById(planId);
        if (plan == null) {
            return VerificationResult.broken(null, "PLAN_NOT_FOUND",
                    "Plan " + planId + " does not exist", 0, null, List.of());
        }
        return verify(plan, eventDao.findByPlanOrderBySeq(planId));
    }

    /** Package-private pure logic so tests can verify without extra queries. */
    VerificationResult verify(PlanRow plan, List<EventRow> events) {
        List<VerificationResult.CheckResult> checks = new ArrayList<>();
        String tailStatus = null;

        if (events.isEmpty()) {
            return VerificationResult.broken(null, "EMPTY_CHAIN",
                    "Plan " + plan.planNo() + " has no evidence events "
                            + "(unanchored historical data or state-without-evidence)",
                    0, plan.status().name(), checks);
        }

        String expectedPrevHash =
                com.example.evidencechain.domain.EvidenceEvent.GENESIS_HASH;
        PlanStatus expectedFrom = null;
        int expectedSeq = 1;
        boolean first = true;

        for (EventRow e : events) {
            int seq = e.seq();

            // 1. seq continuity
            if (seq != expectedSeq) {
                return fail(seq, "SEQ_GAP",
                        "Expected seq " + expectedSeq + " but found " + seq,
                        events.size(), tailStatus, checks);
            }

            // 2. prevHash link
            if (!safeEquals(expectedPrevHash, e.prevHash())) {
                return fail(seq, "PREV_HASH_MISMATCH",
                        "prevHash does not match "
                                + (first ? "the genesis hash (" + abbreviate(expectedPrevHash) + ")"
                                         : "previous event hash (" + abbreviate(expectedPrevHash) + ")")
                                + "; actual " + abbreviate(e.prevHash()),
                        events.size(), tailStatus, checks);
            }
            checks.add(new VerificationResult.CheckResult(seq, "PREV_HASH", true, null));

            // 3a. payloadHash matches the stored payload bytes
            String recomputedPayloadHash = Hashing.sha256Hex(e.payloadJson());
            if (!safeEquals(recomputedPayloadHash, e.payloadHash())) {
                return fail(seq, "PAYLOAD_HASH_MISMATCH",
                        "payloadHash disagrees with sha256(payloadJson)",
                        events.size(), tailStatus, checks);
            }
            // 3b. the stored payload must itself be canonical: catches an
            // attacker who edits payloadJson AND recomputes payloadHash, since
            // hand-edited JSON almost never preserves canonical key order.
            try {
                Object parsed = Hashing.CANONICAL_MAPPER.readValue(e.payloadJson(), Object.class);
                String recanonicalized = Hashing.canonicalJson(parsed);
                if (!safeEquals(recanonicalized, e.payloadJson())) {
                    return fail(seq, "PAYLOAD_NOT_CANONICAL",
                            "payloadJson is not in canonical form (tampered bytes)",
                            events.size(), tailStatus, checks);
                }
            } catch (Exception ex) {
                return fail(seq, "PAYLOAD_UNPARSEABLE",
                        "payloadJson cannot be parsed: " + ex.getMessage(),
                        events.size(), tailStatus, checks);
            }
            checks.add(new VerificationResult.CheckResult(seq, "PAYLOAD", true, null));

            // 4. eventHash
            String recomputedEventHash = Hashing.sha256Hex(Hashing.buildEventCanonical(
                    seq, e.planId(), e.eventType().name(),
                    e.fromStatus() == null ? null : e.fromStatus().name(),
                    e.toStatus().name(), e.prevHash(), e.eventTime(), e.payloadHash()));
            if (!safeEquals(recomputedEventHash, e.eventHash())) {
                return fail(seq, "EVENT_HASH_MISMATCH",
                        "eventHash disagrees with the recomputed hash of the event fields",
                        events.size(), tailStatus, checks);
            }
            checks.add(new VerificationResult.CheckResult(seq, "EVENT_HASH", true, null));

            // 5/6. first-event shape, status chaining, allowed transitions
            if (first) {
                if (e.eventType() == EventType.ANCHOR) {
                    if (!e.anchor()) {
                        return fail(seq, "ANCHOR_FLAG_MISSING",
                                "ANCHOR event must be flagged anchor=true",
                                events.size(), tailStatus, checks);
                    }
                    if (e.fromStatus() != null) {
                        return fail(seq, "ANCHOR_FROM_STATUS",
                                "ANCHOR event must have null fromStatus",
                                events.size(), tailStatus, checks);
                    }
                } else if (e.eventType() == EventType.CREATE) {
                    if (e.fromStatus() != null || e.toStatus() != PlanStatus.DRAFT) {
                        return fail(seq, "INVALID_CREATE",
                                "First non-anchor event must be CREATE null->DRAFT",
                                events.size(), tailStatus, checks);
                    }
                } else if (e.eventType() == EventType.START) {
                    if (e.fromStatus() != PlanStatus.DRAFT || e.toStatus() != PlanStatus.STARTED) {
                        return fail(seq, "INVALID_START",
                                "First non-anchor event must be START DRAFT->STARTED",
                                events.size(), tailStatus, checks);
                    }
                } else {
                    return fail(seq, "INVALID_FIRST_EVENT",
                            "First event must be ANCHOR, CREATE or START but was " + e.eventType(),
                            events.size(), tailStatus, checks);
                }
            } else {
                if (e.fromStatus() != expectedFrom) {
                    return fail(seq, "STATUS_CHAIN_BROKEN",
                            "fromStatus=" + e.fromStatus()
                                    + " but the previous event ended at " + expectedFrom,
                            events.size(), tailStatus, checks);
                }
                if (!isAllowed(e.eventType(), e.fromStatus(), e.toStatus(), e.anchor())) {
                    return fail(seq, "ILLEGAL_TRANSITION",
                            e.eventType() + " " + e.fromStatus() + "->" + e.toStatus()
                                    + " is not an allowed transition",
                            events.size(), tailStatus, checks);
                }
            }
            checks.add(new VerificationResult.CheckResult(seq, "TRANSITION", true, null));

            // 7. business record existence
            if (requiresBusinessRow(e.eventType())) {
                if (e.businessRecordId() == null
                        || !businessRowExists(e.eventType(), e.businessRecordId())) {
                    return fail(seq, "BUSINESS_RECORD_MISSING",
                            e.eventType() + " event has no surviving business row"
                                    + " (evidence without state)",
                            events.size(), tailStatus, checks);
                }
                checks.add(new VerificationResult.CheckResult(seq, "BUSINESS_RECORD", true, null));
            }

            expectedPrevHash = e.eventHash();
            expectedFrom = e.toStatus();
            tailStatus = e.toStatus().name();
            expectedSeq++;
            first = false;
        }

        // Global: plan status must equal the tail -> catches "state without evidence".
        if (tailStatus != null && !tailStatus.equals(plan.status().name())) {
            return fail(null, "TAIL_STATUS_MISMATCH",
                    "Plan status is " + plan.status() + " but the evidence tail ends at "
                            + tailStatus + " (state without evidence)",
                    events.size(), tailStatus, checks);
        }

        // Global: business row counts must match the number of events per type.
        String countProblem = checkCounts(plan.id(), events);
        if (countProblem != null) {
            return fail(null, "BUSINESS_COUNT_MISMATCH", countProblem,
                    events.size(), tailStatus, checks);
        }

        return VerificationResult.ok(events.size(), tailStatus, checks);
    }

    private boolean businessRowExists(EventType type, long id) {
        return switch (type) {
            case CONFIRMATION -> recordDao.confirmationExists(id);
            case MOCK_RECEIPT -> recordDao.mockReceiptExists(id);
            case REJECTION -> recordDao.rejectionExists(id);
            case WRITEOFF -> recordDao.writeoffExists(id);
            case ANCHOR, CREATE, START -> false;
        };
    }

    private boolean requiresBusinessRow(EventType type) {
        return type == EventType.CONFIRMATION || type == EventType.MOCK_RECEIPT
                || type == EventType.REJECTION || type == EventType.WRITEOFF;
    }

    private String checkCounts(long planId, List<EventRow> events) {
        long confirmations = events.stream().filter(e -> e.eventType() == EventType.CONFIRMATION).count();
        long receipts = events.stream().filter(e -> e.eventType() == EventType.MOCK_RECEIPT).count();
        long rejections = events.stream().filter(e -> e.eventType() == EventType.REJECTION).count();
        long writeoffs = events.stream().filter(e -> e.eventType() == EventType.WRITEOFF).count();

        if (confirmations != recordDao.countConfirmations(planId)) {
            return "confirmation rows (" + recordDao.countConfirmations(planId)
                    + ") != CONFIRMATION events (" + confirmations + ")";
        }
        if (receipts != recordDao.countMockReceipts(planId)) {
            return "mock receipt rows (" + recordDao.countMockReceipts(planId)
                    + ") != MOCK_RECEIPT events (" + receipts + ")";
        }
        if (rejections != recordDao.countRejections(planId)) {
            return "rejection rows (" + recordDao.countRejections(planId)
                    + ") != REJECTION events (" + rejections + ")";
        }
        if (writeoffs != recordDao.countWriteoffs(planId)) {
            return "writeoff rows (" + recordDao.countWriteoffs(planId)
                    + ") != WRITEOFF events (" + writeoffs + ")";
        }
        return null;
    }

    private boolean isAllowed(EventType type, PlanStatus from, PlanStatus to, boolean anchor) {
        if (anchor || type == EventType.ANCHOR) {
            return false; // anchors are only legal as the first event
        }
        return switch (type) {
            case CREATE -> from == null && to == PlanStatus.DRAFT;
            case START -> from == PlanStatus.DRAFT && to == PlanStatus.STARTED;
            case CONFIRMATION -> from == PlanStatus.STARTED && to == PlanStatus.CONFIRMED;
            case MOCK_RECEIPT -> from == PlanStatus.CONFIRMED && to == PlanStatus.RECEIPTED;
            case REJECTION -> (from == PlanStatus.STARTED || from == PlanStatus.CONFIRMED
                    || from == PlanStatus.RECEIPTED) && to == PlanStatus.REJECTED;
            case WRITEOFF -> from == PlanStatus.RECEIPTED && to == PlanStatus.WRITTEN_OFF;
            case ANCHOR -> false;
        };
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String abbreviate(String hash) {
        if (hash == null) {
            return "null";
        }
        return hash.length() <= 12 ? hash : hash.substring(0, 12) + "...";
    }

    private VerificationResult fail(Integer seq, String reason, String detail,
                                    int eventCount, String tailStatus,
                                    List<VerificationResult.CheckResult> checks) {
        return VerificationResult.broken(seq, reason, detail, eventCount, tailStatus, checks);
    }
}
