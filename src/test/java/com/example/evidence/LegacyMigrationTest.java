package com.example.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance 4: historical plans are safely anchored — original records
 * preserved, chains queryable and verifiable, migration idempotent, and
 * anchored plans can continue transitioning.
 */
class LegacyMigrationTest extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void legacyPlansAreAnchoredWithoutLosingOriginalRecords() {
        TestApi api = new TestApi(url(""), http);
        Instant historical = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        // seed two historical rows directly in the legacy table
        seedLegacy(api, 9001, "L-1", "old pump fix", "IN_PROGRESS", historical);
        seedLegacy(api, 9002, "L-2", "old valve check", "CLOSED", historical);

        // legacy table untouched before migration, plan table empty
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_plan", Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan", Long.class)).isZero();

        var anchor = api.post("/api/plans/legacy/anchor", Map.of());
        Map<String, Object> result = TestApi.body(anchor);
        assertThat(((Number) result.get("totalLegacy")).longValue()).isEqualTo(2L);
        assertThat(((Number) result.get("anchored")).longValue()).isEqualTo(2L);
        assertThat(((Number) result.get("failed")).longValue()).isZero();

        // original legacy records still present and unchanged
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legacy_plan", Long.class)).isEqualTo(2L);
        Map<String, Object> legacyRow = jdbc.queryForMap("SELECT * FROM legacy_plan WHERE id = 9001");
        assertThat(legacyRow.get("status")).isEqualTo("IN_PROGRESS");

        // anchored plans queryable with genesis ANCHOR event at mapped state ISSUED
        Long newId = jdbc.queryForObject(
                "SELECT id FROM plan WHERE legacy_ref = 9001", Long.class);
        Map<String, Object> plan = TestApi.body(api.get("/api/plans/" + newId));
        assertThat(plan.get("status")).isEqualTo("ISSUED");
        assertThat(plan.get("source")).isEqualTo("LEGACY");

        Map<String, Object> evidence = TestApi.body(api.get("/api/plans/" + newId + "/evidence"));
        List<Map<String, Object>> events = (List<Map<String, Object>>) evidence.get("events");
        assertThat(events).hasSize(1);
        assertThat(events.get(0).get("eventType")).isEqualTo("ANCHOR");
        assertThat(events.get(0).get("genesis")).isEqualTo(true);
        assertThat(events.get(0).get("stateAfter")).isEqualTo("ISSUED");
        assertThat(((Map<String, Object>) events.get(0).get("payload")).get("legacyId")).isEqualTo(9001);

        // anchor chains verify
        Map<String, Object> verify = TestApi.body(api.get("/api/plans/" + newId + "/verify"));
        assertThat(verify.get("valid")).isEqualTo(true);

        // CLOSED legacy plan anchored at terminal WRITTEN_OFF
        Long closedId = jdbc.queryForObject(
                "SELECT id FROM plan WHERE legacy_ref = 9002", Long.class);
        assertThat(TestApi.body(api.get("/api/plans/" + closedId)).get("status")).isEqualTo("WRITTEN_OFF");
        assertThat(TestApi.body(api.get("/api/plans/" + closedId + "/verify")).get("valid")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anchoringIsIdempotentAcrossRepeatedRuns() {
        TestApi api = new TestApi(url(""), http);
        seedLegacy(api, 9101, "L-10", "repeated", "NEW", Instant.now().minusSeconds(3600));

        var first = TestApi.body(api.post("/api/plans/legacy/anchor", Map.of()));
        var second = TestApi.body(api.post("/api/plans/legacy/anchor", Map.of()));
        var third = TestApi.body(api.post("/api/plans/legacy/anchor", Map.of()));

        assertThat(((Number) first.get("anchored")).longValue()).isEqualTo(1L);
        assertThat(((Number) second.get("anchored")).longValue()).isZero();
        assertThat(((Number) second.get("skipped")).longValue()).isEqualTo(1L);
        assertThat(((Number) third.get("skipped")).longValue()).isEqualTo(1L);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM plan WHERE legacy_ref = 9101", Long.class))
                .isEqualTo(1L);
        Long planId = jdbc.queryForObject("SELECT id FROM plan WHERE legacy_ref = 9101", Long.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = ?", Long.class, planId))
                .isEqualTo(1L);
        // re-anchoring must not have destroyed verifiability
        assertThat(TestApi.body(api.get("/api/plans/" + planId + "/verify")).get("valid")).isEqualTo(true);
    }

    @Test
    void anchoredPlanContinuesItsLifecycleAndChainStillVerifies() {
        TestApi api = new TestApi(url(""), http);
        seedLegacy(api, 9201, "L-20", "continue me", "NEW", Instant.now().minusSeconds(600));
        TestApi.body(api.post("/api/plans/legacy/anchor", Map.of()));

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        long planId = jdbc.queryForObject("SELECT id FROM plan WHERE legacy_ref = 9201", Long.class);

        api.post("/api/plans/" + planId + "/actions", Map.of("action", "START"), null);
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "CONFIRM"), null);
        var receipt = api.post("/api/plans/" + planId + "/actions", Map.of("action", "RECEIPT"), null);
        // receipt event is seq 4: ANCHOR(1), STARTED(2), CONFIRMED(3), RECEIPTED(4)
        assertThat(((Map<?, ?>) ((Map<?, ?>) TestApi.body(receipt).get("event")).get("payload"))
                .get("receiptNo")).isEqualTo("SIM-" + planId + "-4");

        Map<String, Object> v = TestApi.body(api.get("/api/plans/" + planId + "/verify"));
        assertThat(v.get("valid")).isEqualTo(true);
        assertThat(v.get("firstBrokenSeq")).isNull();
    }

    private void seedLegacy(TestApi api, long id, String planNo, String title,
                            String status, Instant createdAt) {
        var response = api.post("/api/plans/legacy/seed", Map.of(
                "id", id,
                "planNo", planNo,
                "title", title,
                "status", status,
                "createdAt", createdAt.toString(),
                "updatedAt", createdAt.toString(),
                "extra", "{\"source\":\"drain-2026\"}"));
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
