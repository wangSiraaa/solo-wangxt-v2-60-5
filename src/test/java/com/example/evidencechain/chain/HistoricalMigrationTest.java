package com.example.evidencechain.chain;

import com.example.evidencechain.AbstractIntegrationTest;
import com.example.evidencechain.domain.EventType;
import com.example.evidencechain.domain.PlanStatus;
import com.example.evidencechain.repository.EvidenceEventDao;
import com.example.evidencechain.repository.LegacySnapshotDao;
import com.example.evidencechain.repository.PlanDao;
import com.example.evidencechain.service.AnchorMigrationService;
import com.example.evidencechain.service.ChainVerifier;
import com.example.evidencechain.service.EvidenceService;
import com.example.evidencechain.service.PlanCommand;
import com.example.evidencechain.service.VerificationResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance: historical plans that pre-date the evidence system are safely
 * anchored — original records survive, they remain queryable, and the chain
 * verifies. Re-running migration adds nothing.
 */
class HistoricalMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private AnchorMigrationService migrationService;
    @Autowired
    private ChainVerifier verifier;
    @Autowired
    private PlanDao planDao;
    @Autowired
    private EvidenceEventDao eventDao;
    @Autowired
    private LegacySnapshotDao legacyDao;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void plansAlreadyInTableWithoutEventsAreAnchoredWithoutLosingData() {
        // Simulate legacy rows inserted before the evidence feature existed.
        Instant oldTime = Instant.now().minus(365, ChronoUnit.DAYS);
        jdbc.update("INSERT INTO plan (plan_no, status, title, created_at, updated_at) "
                        + "VALUES ('LEGACY-1', 'CONFIRMED', '历史计划一', ?, ?)",
                java.sql.Timestamp.from(oldTime), java.sql.Timestamp.from(oldTime));
        long legacyPlanId = jdbc.queryForObject(
                "SELECT id FROM plan WHERE plan_no = 'LEGACY-1'", Long.class);

        // Original record intact before migration.
        var before = planDao.findById(legacyPlanId);
        assertThat(before.status()).isEqualTo(PlanStatus.CONFIRMED);
        assertThat(before.createdAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(oldTime.truncatedTo(ChronoUnit.MILLIS));

        var report = migrationService.migrateAll();
        assertThat(report.anchored()).isGreaterThanOrEqualTo(1);

        // Original record still intact after migration (id, status, timestamps).
        var after = planDao.findById(legacyPlanId);
        assertThat(after.id()).isEqualTo(legacyPlanId);
        assertThat(after.status()).isEqualTo(PlanStatus.CONFIRMED);
        assertThat(after.createdAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(oldTime.truncatedTo(ChronoUnit.MILLIS));
        assertThat(after.title()).isEqualTo("历史计划一");

        // One ANCHOR event exists and the chain verifies.
        List<EvidenceEventDao.EventRow> chain = eventDao.findByPlanOrderBySeq(legacyPlanId);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).eventType()).isEqualTo(EventType.ANCHOR);
        assertThat(chain.get(0).toStatus()).isEqualTo(PlanStatus.CONFIRMED);
        assertThat(chain.get(0).anchor()).isTrue();

        VerificationResult verification = verifier.verify(legacyPlanId);
        assertThat(verification.valid())
                .as("anchored legacy plan should verify: %s", verification)
                .isTrue();

        // Business can continue on top of the anchored plan.
        evidenceService.execute(PlanCommand.receipt(legacyPlanId, "CH", "{}", "kr-1"));
        evidenceService.execute(PlanCommand.writeoff(legacyPlanId, "c", "ok", "kw-1"));
        assertThat(verifier.verify(legacyPlanId).valid()).isTrue();

        // Idempotent re-run: no second anchor.
        var secondReport = migrationService.migrateAll();
        assertThat(secondReport.anchored()).isZero();
        assertThat(eventDao.countByPlan(legacyPlanId)).isEqualTo(3);
    }

    @Test
    void legacySnapshotsAreImportedAnchoredAndRemainQueryable() {
        Instant oldTime = Instant.now().minus(30, ChronoUnit.DAYS);
        long snapshotId = legacyDao.insert("LEGACY-S1", "RECEIPTED", "来自旧系统的计划", oldTime);

        migrationService.migrateAll();

        var snapshot = legacyDao.findAll().stream()
                .filter(row -> row.id() == snapshotId).findFirst().orElseThrow();
        assertThat(snapshot.anchorEventId()).isNotNull();
        assertThat(snapshot.migratedAt()).isNotNull();

        var plan = planDao.findByPlanNo("LEGACY-S1");
        assertThat(plan).isNotNull();
        assertThat(plan.status()).isEqualTo(PlanStatus.RECEIPTED);
        assertThat(plan.createdAt().truncatedTo(ChronoUnit.MILLIS))
                .isEqualTo(oldTime.truncatedTo(ChronoUnit.MILLIS));

        VerificationResult result = verifier.verify(plan.id());
        assertThat(result.valid())
                .as("imported snapshot chain should verify: %s", result)
                .isTrue();

        // Re-running must not duplicate.
        migrationService.migrateAll();
        assertThat(planDao.findByPlanNo("LEGACY-S1").id()).isEqualTo(plan.id());
        assertThat(eventDao.countByPlan(plan.id())).isEqualTo(1);
    }

    @Test
    void aBrandNewPlanDoesNotNeedAnAnchorAndIsNotTouchedByMigration() {
        long planId = evidenceService.createPlan("NEW-1", "new", "a", "kc").planId();
        var report = migrationService.migrateAll();
        assertThat(report.anchored()).isZero();

        List<EvidenceEventDao.EventRow> chain = eventDao.findByPlanOrderBySeq(planId);
        assertThat(chain).hasSize(1);
        assertThat(chain.get(0).eventType()).isEqualTo(EventType.CREATE);
        assertThat(verifier.verify(planId).valid()).isTrue();
    }
}
