package com.example.evidence.migration;

import com.example.evidence.chain.EvidenceEventRepository;
import com.example.evidence.chain.HashChain;
import com.example.evidence.domain.EventType;
import com.example.evidence.domain.PlanStatus;
import com.example.evidence.plan.PlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Safe one-time initialization of evidence anchors for pre-existing plans.
 *
 * <p>Rules:
 * <ul>
 *   <li>the {@code legacy_plan} table is only read: no historical record is
 *       deleted, modified or copied away — the snapshot lives additionally in
 *       {@code plan} / {@code plan_evidence_event};</li>
 *   <li>each legacy plan is anchored in its <b>own transaction</b>, so a bad
 *       row never blocks the rest and a crash mid-batch leaves only fully
 *       anchored or fully untouched rows;</li>
 *   <li>idempotent: re-running skips plans that already have an anchor
 *       (matched by unique {@code legacy_ref}); no duplicate plan or event is
 *       ever produced;</li>
 *   <li>anchored plans continue their lifecycle with normal actions from the
 *       snapshotted state.</li>
 * </ul>
 */
@Service
public class LegacyAnchorService {

    private final LegacyPlanRepository legacyRepository;
    private final PlanRepository planRepository;
    private final EvidenceEventRepository eventRepository;
    private final HashChain hashChain;
    private final TransactionTemplate tx;

    public LegacyAnchorService(LegacyPlanRepository legacyRepository,
                               PlanRepository planRepository,
                               EvidenceEventRepository eventRepository,
                               HashChain hashChain,
                               PlatformTransactionManager transactionManager) {
        this.legacyRepository = legacyRepository;
        this.planRepository = planRepository;
        this.eventRepository = eventRepository;
        this.hashChain = hashChain;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record AnchorResult(long totalLegacy, long anchored, long skipped,
                               long failed, List<Item> items) {
        public record Item(long legacyId, long planId, String status, String detail) {
        }
    }

    public AnchorResult anchorAll() {
        List<LegacyPlanRepository.LegacyRow> rows = legacyRepository.findAll();
        List<AnchorResult.Item> items = new ArrayList<>();
        long anchored = 0;
        long skipped = 0;
        long failed = 0;

        for (LegacyPlanRepository.LegacyRow row : rows) {
            var existingAnchor = planRepository.findByLegacyRef(row.id());
            if (existingAnchor.isPresent()) {
                skipped++;
                items.add(new AnchorResult.Item(row.id(), existingAnchor.orElseThrow().id(),
                        "SKIPPED", "anchor already exists"));
                continue;
            }
            try {
                Long newPlanId = tx.execute(status -> anchorOne(row));
                anchored++;
                items.add(new AnchorResult.Item(row.id(), newPlanId == null ? -1L : newPlanId, "ANCHORED",
                        "genesis ANCHOR event at state " + mapStatus(row.status()).name()));
            } catch (Exception e) {
                // Concurrent anchoring of the same legacy row: the winner committed
                // the unique legacy_ref; classify as skipped rather than failed.
                var winner = planRepository.findByLegacyRef(row.id());
                if (winner.isPresent()) {
                    skipped++;
                    items.add(new AnchorResult.Item(row.id(), winner.orElseThrow().id(),
                            "SKIPPED", "anchor already exists (concurrent run)"));
                } else {
                    failed++;
                    items.add(new AnchorResult.Item(row.id(), -1L, "FAILED", e.getMessage()));
                }
            }
        }
        return new AnchorResult(rows.size(), anchored, skipped, failed, items);
    }

    /**
     * Anchors one legacy plan in a single transaction: create the plan and its
     * genesis ANCHOR event atomically. Returns the new plan id.
     */
    Long anchorOne(LegacyPlanRepository.LegacyRow row) {
        PlanStatus status = mapStatus(row.status());
        Instant createdAt = row.createdAt() == null
                ? Instant.now().truncatedTo(ChronoUnit.MILLIS)
                : row.createdAt().truncatedTo(ChronoUnit.MILLIS);
        Instant updatedAt = row.updatedAt() == null ? createdAt : row.updatedAt().truncatedTo(ChronoUnit.MILLIS);
        Instant anchoredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        long planId = planRepository.insertLegacy(row.planNo(), row.title(), status.name(),
                row.id(), createdAt, updatedAt);
        PlanRepository.PlanRow plan = planRepository.findById(planId).orElseThrow();

        Map<String, Object> snapshot = new TreeMap<>();
        snapshot.put("legacyId", row.id());
        snapshot.put("legacyPlanNo", row.planNo());
        snapshot.put("title", row.title());
        snapshot.put("legacyStatus", row.status());
        snapshot.put("snapshotStatus", status.name());
        snapshot.put("legacyCreatedAt", createdAt.toEpochMilli());
        snapshot.put("legacyUpdatedAt", updatedAt.toEpochMilli());
        snapshot.put("anchoredAt", anchoredAt.toEpochMilli());
        if (row.extraJson() != null && !row.extraJson().isBlank()) {
            snapshot.put("legacyExtraRaw", row.extraJson());
        }

        String hash = hashChain.computeHash(new HashChain.HashInput(
                planId, 1L, EventType.ANCHOR.name(), null, status.name(),
                HashChain.GENESIS_PREV_HASH, anchoredAt.toEpochMilli(), snapshot));

        eventRepository.insert(new EvidenceEventRepository.NewEvent(
                planId, 1L, EventType.ANCHOR.name(), null, status.name(),
                snapshot, null, null, HashChain.GENESIS_PREV_HASH, hash, anchoredAt));

        return planId;
    }

    /** Map legacy status vocabulary onto the current lifecycle; unknown values fail loudly. */
    static PlanStatus mapStatus(String legacyStatus) {
        if (legacyStatus == null) {
            throw new IllegalArgumentException("legacy status is null");
        }
        String normalized = legacyStatus.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return switch (normalized) {
            case "DRAFT", "NEW", "INIT" -> PlanStatus.DRAFT;
            case "ISSUED", "STARTED", "IN_PROGRESS", "OPEN" -> PlanStatus.ISSUED;
            case "CONFIRMED", "ACKED", "ACKNOWLEDGED" -> PlanStatus.CONFIRMED;
            case "RECEIPTED", "RECEIVED", "SIMULATED" -> PlanStatus.RECEIPTED;
            case "REJECTED", "DENIED" -> PlanStatus.REJECTED;
            case "WRITTEN_OFF", "CLOSED", "DONE", "COMPLETED", "FINISHED" -> PlanStatus.WRITTEN_OFF;
            default -> throw new IllegalArgumentException("unknown legacy status: " + legacyStatus);
        };
    }
}
