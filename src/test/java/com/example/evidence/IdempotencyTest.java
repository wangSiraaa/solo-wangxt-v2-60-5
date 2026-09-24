package com.example.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance 2: idempotent replays (same key) never produce a second business
 * result or a second evidence event — sequentially after a restart and under
 * concurrent duplicate calls. Same key + different body is a 409.
 */
class IdempotencyTest extends AbstractIntegrationTest {

    @Test
    void duplicateCreateRequestsProduceOnePlanAndOneEvent() {
        TestApi api = new TestApi(url(""), http);
        String key = "idem-create-" + UUID.randomUUID();
        Map<String, Object> body = Map.of("planNo", "PL-IDEM-1", "title", "one shot");

        var first = api.post("/api/plans", body, key);
        var second = api.post("/api/plans", body, key);
        var third = api.post("/api/plans", body, key);

        long firstId = planId(first);
        assertThat(planId(second)).isEqualTo(firstId);
        assertThat(planId(third)).isEqualTo(firstId);
        assertThat(TestApi.body(first).get("replayed")).isEqualTo(false);
        assertThat(TestApi.body(second).get("replayed")).isEqualTo(true);
        assertThat(TestApi.body(third).get("replayed")).isEqualTo(true);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long plans = jdbc.queryForObject("SELECT COUNT(*) FROM plan WHERE plan_no = 'PL-IDEM-1'", Long.class);
        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = ?", Long.class, firstId);
        assertThat(plans).isEqualTo(1L);
        assertThat(events).isEqualTo(1L);
    }

    @Test
    void duplicateActionRequestsDoNotAdvanceStateOrAppendEvents() {
        TestApi api = new TestApi(url(""), http);
        long planId = create(api, "PL-IDEM-A");
        String key = "idem-start-" + UUID.randomUUID();
        Map<String, Object> action = Map.of("action", "START", "payload", Map.of("operator", "alice"));

        var r1 = api.post("/api/plans/" + planId + "/actions", action, key);
        var r2 = api.post("/api/plans/" + planId + "/actions", action, key);

        assertThat(r1.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(TestApi.body(r1).get("replayed")).isEqualTo(false);
        assertThat(TestApi.body(r2).get("replayed")).isEqualTo(true);
        // state is ISSUED, not advanced twice (a second start would be invalid anyway)
        assertThat(planStatus(api, planId)).isEqualTo("ISSUED");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = ?", Long.class, planId);
        assertThat(events).isEqualTo(2L); // CREATED + STARTED
        // receipt replay must keep the same deterministic receipt number
        String receiptKey = "idem-receipt-" + UUID.randomUUID();
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "CONFIRM"), null);
        Map<String, Object> receiptBody = Map.of("action", "RECEIPT", "payload", Map.of());
        var receipt1 = api.post("/api/plans/" + planId + "/actions", receiptBody, receiptKey);
        var receipt2 = api.post("/api/plans/" + planId + "/actions", receiptBody, receiptKey);
        assertThat(hash(receipt1)).isEqualTo(hash(receipt2));
        Long eventsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = ?", Long.class, planId);
        assertThat(eventsAfter).isEqualTo(4L);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejectedAsConflict() {
        TestApi api = new TestApi(url(""), http);
        String key = "idem-conflict-" + UUID.randomUUID();
        api.post("/api/plans", Map.of("planNo", "PL-CONF-1", "title", "original"), key);
        var conflict = api.post("/api/plans", Map.of("planNo", "PL-CONF-1", "title", "changed body"), key);
        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(TestApi.body(conflict).get("code")).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void concurrentDuplicateCallsYieldExactlyOneEvent() throws Exception {
        TestApi api = new TestApi(url(""), http);
        long planId = create(api, "PL-IDEM-C");
        String key = "idem-concurrent-" + UUID.randomUUID();
        Map<String, Object> action = Map.of("action", "START");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<ResponseEntity<Map>> task = () -> api.post(
                "/api/plans/" + planId + "/actions", action, key);
        Future<ResponseEntity<Map>> f1 = pool.submit(task);
        Future<ResponseEntity<Map>> f2 = pool.submit(task);

        int ok = 0;
        int replayed = 0;
        for (Future<ResponseEntity<Map>> f : java.util.List.of(f1, f2)) {
            var response = f.get();
            assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
            ok++;
            if (Boolean.TRUE.equals(TestApi.body(response).get("replayed"))) {
                replayed++;
            }
        }
        pool.shutdown();
        assertThat(ok).isEqualTo(2);
        assertThat(replayed).isEqualTo(1);

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Long events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = ?", Long.class, planId);
        assertThat(events).isEqualTo(2L);
        assertThat(planStatus(api, planId)).isEqualTo("ISSUED");
    }

    // ---------------------------------------------------------------- helpers

    private long create(TestApi api, String planNo) {
        var created = api.post("/api/plans", Map.of("planNo", planNo, "title", "t"), null);
        return planId(created);
    }

    @SuppressWarnings("unchecked")
    private long planId(ResponseEntity<Map> r) {
        Map<String, Object> plan = (Map<String, Object>) TestApi.body(r).get("plan");
        return ((Number) plan.get("id")).longValue();
    }

    @SuppressWarnings("unchecked")
    private String hash(ResponseEntity<Map> r) {
        return (String) ((Map<String, Object>) TestApi.body(r).get("event")).get("eventHash");
    }

    @SuppressWarnings("unchecked")
    private String planStatus(TestApi api, long planId) {
        return (String) TestApi.body(api.get("/api/plans/" + planId)).get("status");
    }
}
