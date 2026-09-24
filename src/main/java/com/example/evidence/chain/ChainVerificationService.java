package com.example.evidence.chain;

import com.example.evidence.plan.PlanRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Recomputes the hash chain from stored rows and locates the first break.
 *
 * <p>Checks performed, in order, per plan:
 * <ol>
 *   <li>the plan still exists;</li>
 *   <li>the chain is non-empty and seq starts at 1 with no gaps;</li>
 *   <li>each stored prev_hash equals the previous event_hash (link integrity);</li>
 *   <li>each stored event_hash equals a freshly recomputed hash over the row
 *       (content integrity: catches tampering of payload/state/type/time);</li>
 *   <li>the plan's current status equals the last event's state_after
 *       (state/evidence agreement).</li>
 * </ol>
 * The first detected break is reported with its exact seq and a machine
 * readable reason; everything before it is proven intact.
 */
@Service
public class ChainVerificationService {

    private final EvidenceEventRepository eventRepository;
    private final PlanRepository planRepository;
    private final HashChain hashChain;

    public ChainVerificationService(EvidenceEventRepository eventRepository,
                                    PlanRepository planRepository,
                                    HashChain hashChain) {
        this.eventRepository = eventRepository;
        this.planRepository = planRepository;
        this.hashChain = hashChain;
    }

    public VerificationResult verifyPlan(long planId) {
        Optional<PlanRepository.PlanRow> plan = planRepository.findById(planId);
        if (plan.isEmpty()) {
            return new VerificationResult(planId, false, null, "PLAN_NOT_FOUND",
                    "plan " + planId + " does not exist", List.of());
        }

        List<EvidenceEvent> events = eventRepository.findByPlanOrdered(planId);
        List<String> verifiedHashes = new ArrayList<>();

        if (events.isEmpty()) {
            return broken(planId, null, "EMPTY_CHAIN", "plan has no evidence events", verifiedHashes);
        }

        EvidenceEvent first = events.get(0);
        if (first.seq() != 1) {
            return broken(planId, first.seq(), "SEQ_NOT_STARTING_AT_ONE",
                    "first event seq is " + first.seq() + ", expected 1", verifiedHashes);
        }

        String expectedPrev = HashChain.GENESIS_PREV_HASH;
        long expectedSeq = 1;

        for (EvidenceEvent event : events) {
            if (event.seq() != expectedSeq) {
                return broken(planId, event.seq(), "SEQ_GAP",
                        "expected seq " + expectedSeq + " but found " + event.seq(), verifiedHashes);
            }

            if (!expectedPrev.equals(event.prevHash())) {
                String reason = event.seq() == 1
                        ? "genesis prev_hash must be 64 zeros"
                        : "prev_hash does not match previous event_hash";
                return broken(planId, event.seq(), "BROKEN_LINK", reason, verifiedHashes);
            }

            String recomputed = recompute(event);
            if (!recomputed.equals(event.eventHash())) {
                return broken(planId, event.seq(), "CONTENT_TAMPERED",
                        "stored event_hash does not match hash recomputed from row contents", verifiedHashes);
            }

            verifiedHashes.add(event.eventHash());
            expectedPrev = event.eventHash();
            expectedSeq++;
        }

        EvidenceEvent last = events.get(events.size() - 1);
        if (!last.stateAfter().equals(plan.get().status())) {
            return broken(planId, last.seq(), "STATE_DIVERGED",
                    "plan status is " + plan.get().status()
                            + " but last evidence event ends in " + last.stateAfter(), verifiedHashes);
        }

        return new VerificationResult(planId, true, null, "OK",
                "chain of " + events.size() + " event(s) verified intact; last seq " + last.seq(),
                verifiedHashes);
    }

    public MultiVerificationResult verifyAll() {
        List<VerificationResult> results = new ArrayList<>();
        boolean allOk = true;
        for (Long planId : eventRepository.findAllPlanIds()) {
            VerificationResult result = verifyPlan(planId);
            results.add(result);
            allOk &= result.valid();
        }
        return new MultiVerificationResult(allOk, results.size(), results);
    }

    private String recompute(EvidenceEvent e) {
        long millis = e.createdAt().toEpochMilli();
        return hashChain.computeHash(new HashChain.HashInput(
                e.planId(), e.seq(), e.eventType(), e.stateBefore(), e.stateAfter(),
                e.prevHash(), millis, e.payload() == null ? Map.of() : e.payload()));
    }

    private VerificationResult broken(long planId, Long seq, String reasonCode, String detail,
                                      List<String> verifiedHashes) {
        return new VerificationResult(planId, false, seq, reasonCode, detail, List.copyOf(verifiedHashes));
    }

    public record VerificationResult(long planId, boolean valid, Long firstBrokenSeq,
                                     String reasonCode, String detail, List<String> verifiedEventHashes) {
    }

    public record MultiVerificationResult(boolean allValid, int planCount, List<VerificationResult> results) {
    }
}
