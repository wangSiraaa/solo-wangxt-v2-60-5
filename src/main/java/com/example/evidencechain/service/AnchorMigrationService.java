package com.example.evidencechain.service;

import com.example.evidencechain.domain.PlanStatus;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.repository.EvidenceEventDao.EventRow;
import com.example.evidencechain.repository.LegacySnapshotDao;
import com.example.evidencechain.repository.LegacySnapshotDao.LegacyRow;
import com.example.evidencechain.repository.PlanDao;
import com.example.evidencechain.repository.PlanDao.PlanRow;
import com.example.evidencechain.support.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safely anchors pre-existing (historical) plans into the evidence system.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>No original record is modified or deleted.</b> Existing plan rows
 *       keep their id, plan_no, status and timestamps exactly as they were.
 *       Migration only appends a single {@code ANCHOR} evidence event per
 *       plan.</li>
 *   <li><b>Idempotent.</b> Plans that already have events are skipped, the
 *       snapshot's {@code anchor_event_id} is checked, and the anchor event's
 *       own {@code (plan_id, idempotency_key)} unique key makes re-running the
 *       migration after a crash safe. Running on restart adds nothing twice.</li>
 *   <li><b>Atomic per plan.</b> Each plan is anchored in its own transaction
 *       (via {@link TransactionTemplate}); a failure on one plan rolls back
 *       just that plan and never leaves a partial anchor, while other plans
 *       are still migrated.</li>
 * </ul>
 *
 * <p>The ANCHOR event has {@code fromStatus=null}, {@code toStatus=<historical
 * status>}; the payload is a signed snapshot of the original row, so the
 * pre-history is provably captured. Subsequent events chain normally on top
 * of the anchor.
 */
@Service
public class AnchorMigrationService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AnchorMigrationService.class);

    static final String ANCHOR_KEY_PREFIX = "ANCHOR:";
    static final String SNAPSHOT_KEY_PREFIX = "ANCHOR:SNAPSHOT:";

    private final PlanDao planDao;
    private final EvidenceEventDao eventDao;
    private final LegacySnapshotDao legacyDao;
    private final EvidenceService evidenceService;
    private final TransactionTemplate tx;
    private final boolean anchorOnStartup;

    public AnchorMigrationService(PlanDao planDao,
                                  EvidenceEventDao eventDao,
                                  LegacySnapshotDao legacyDao,
                                  EvidenceService evidenceService,
                                  PlatformTransactionManager txManager,
                                  @Value("${evidence.anchor.on-startup:true}") boolean anchorOnStartup) {
        this.planDao = planDao;
        this.eventDao = eventDao;
        this.legacyDao = legacyDao;
        this.evidenceService = evidenceService;
        this.tx = new TransactionTemplate(txManager);
        this.anchorOnStartup = anchorOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!anchorOnStartup) {
            log.info("Historical anchoring on startup is disabled");
            return;
        }
        MigrationReport report = migrateAll();
        log.info("Historical anchoring finished: {} plan(s) anchored, {} skipped",
                report.anchored(), report.skipped());
    }

    /**
     * Anchors every plan (including imported legacy snapshots) that has no
     * evidence yet. Safe to call repeatedly at any time; each plan is handled
     * in its own transaction.
     */
    public MigrationReport migrateAll() {
        int anchored = 0;
        int skipped = 0;

        // 1) Plans already in the live table that pre-date the evidence chain.
        for (Long planId : planDao.findIdsWithoutEvents()) {
            Boolean result = tx.execute(status -> doAnchorExistingPlan(planId));
            if (Boolean.TRUE.equals(result)) {
                anchored++;
            } else {
                skipped++;
            }
        }

        // 2) Legacy staging rows: import into plan (if missing) then anchor.
        // The candidate list is itself fetched per attempt; each row locks
        // itself with FOR UPDATE SKIP LOCKED.
        int snapshotAnchored;
        do {
            snapshotAnchored = 0;
            for (LegacyRow legacy : legacyDao.findUnmigrated()) {
                Boolean result = tx.execute(status -> doMigrateSnapshot(legacy));
                if (Boolean.TRUE.equals(result)) {
                    anchored++;
                    snapshotAnchored++;
                } else {
                    skipped++;
                }
            }
        } while (snapshotAnchored > 0);

        return new MigrationReport(anchored, skipped);
    }

    private boolean doAnchorExistingPlan(long planId) {
        PlanRow plan = planDao.lockById(planId);
        if (plan == null || eventDao.lockLast(planId) != null) {
            return false; // another transaction anchored it first
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "ANCHOR");
        payload.put("anchorReason", "HISTORICAL_PLAN_WITHOUT_EVIDENCE");
        payload.put("eventTime", Hashing.formatInstant(Instant.now()));
        payload.put("originalPlanNo", plan.planNo());
        payload.put("originalTitle", plan.title());
        payload.put("originalStatus", plan.status().name());
        payload.put("originalCreatedAt", Hashing.formatInstant(plan.createdAt()));
        payload.put("originalUpdatedAt", Hashing.formatInstant(plan.updatedAt()));

        evidenceService.appendAnchor(plan.id(), null, plan.status(),
                ANCHOR_KEY_PREFIX + plan.id(), payload, Instant.now());
        log.info("Anchored historical plan id={} planNo={} status={}",
                plan.id(), plan.planNo(), plan.status());
        return true;
    }

    /**
     * Imports one legacy snapshot: creates the live plan row preserving the
     * original status and creation time, then appends the anchor event in the
     * same transaction. The snapshot row is marked afterwards.
     */
    private boolean doMigrateSnapshot(LegacyRow legacy) {
        PlanStatus status = PlanStatus.valueOf(legacy.status());

        PlanRow existing = planDao.findByPlanNo(legacy.planNo());
        long planId;
        if (existing != null) {
            // Plan already imported (e.g. by a previous crashed run); reuse it
            // but only if it is still event-free.
            if (eventDao.countByPlan(existing.id()) > 0) {
                EventRow first = eventDao.findByPlanOrderBySeq(existing.id()).get(0);
                legacyDao.markMigrated(legacy.id(), first.id(), Instant.now());
                return false;
            }
            planId = existing.id();
        } else {
            planId = planDao.insert(legacy.planNo(), status, legacy.title(),
                    legacy.legacyCreatedAt());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "ANCHOR");
        payload.put("anchorReason", "LEGACY_SNAPSHOT_IMPORT");
        payload.put("eventTime", Hashing.formatInstant(Instant.now()));
        payload.put("snapshotId", legacy.id());
        payload.put("originalPlanNo", legacy.planNo());
        payload.put("originalTitle", legacy.title());
        payload.put("originalStatus", status.name());
        payload.put("originalCreatedAt", Hashing.formatInstant(legacy.legacyCreatedAt()));

        var anchor = evidenceService.appendAnchor(planId, null, status,
                SNAPSHOT_KEY_PREFIX + legacy.id(), payload, Instant.now());
        legacyDao.markMigrated(legacy.id(), anchor.id(), Instant.now());
        log.info("Anchored legacy snapshot id={} as plan id={} planNo={}",
                legacy.id(), planId, legacy.planNo());
        return true;
    }

    public record MigrationReport(int anchored, int skipped) {
    }
}
