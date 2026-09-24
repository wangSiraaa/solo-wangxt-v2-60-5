package com.example.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance 5: when a transaction fails at either point — after the evidence
 * row is inserted but before the status update, or after the status update but
 * before commit — rollback leaves neither "evidence without state" nor "state
 * without evidence". The same request can then be retried cleanly.
 */
class TransactionAtomicityTest extends AbstractIntegrationTest {

    @Test
    void failureAfterEvidenceInsertRollsBackEntirely() {
        TestApi api = new TestApi(url(""), http);
        long planId = readyForStart(api, "PL-TX-EVID");

        int eventsBefore = eventCount(api, planId);
        api.post("/api/test/plans/arm-failure/AFTER_EVIDENCE_INSERT", Map.of());
        ResponseEntity<Map> failed = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "START", "payload", Map.of("operator", "alice")), "tx-key-1");

        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();

        Map<String, Object> state = internalState(api, planId);
        assertThat(state.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) state.get("eventCount")).intValue()).isEqualTo(eventsBefore);
        // and the chain still verifies after the failed attempt
        assertThat(TestApi.body(api.get("/api/plans/" + planId + "/verify")).get("valid")).isEqualTo(true);

        // retry with the same requestId: clean success, exactly one event added
        var retry = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "START", "payload", Map.of("operator", "alice")), "tx-key-1");
        assertThat(retry.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(TestApi.body(retry).get("replayed")).isEqualTo(false);
        assertThat(internalState(api, planId).get("status")).isEqualTo("ISSUED");
        assertThat(((Number) internalState(api, planId).get("eventCount")).intValue())
                .isEqualTo(eventsBefore + 1);
    }

    @Test
    void failureAfterStateUpdateRollsBackStateAndEvidence() {
        TestApi api = new TestApi(url(""), http);
        long planId = readyForStart(api, "PL-TX-STATE");

        int eventsBefore = eventCount(api, planId);
        api.post("/api/test/plans/arm-failure/AFTER_STATE_UPDATE", Map.of());
        ResponseEntity<Map> failed = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "START"), "tx-key-2");
        assertThat(failed.getStatusCode().is5xxServerError()).isTrue();

        Map<String, Object> state = internalState(api, planId);
        // status update must have rolled back together with the evidence insert
        assertThat(state.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) state.get("eventCount")).intValue()).isEqualTo(eventsBefore);

        // normal retry after a simulated pre-commit crash
        var retry = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "START"), "tx-key-2");
        assertThat(retry.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(internalState(api, planId).get("status")).isEqualTo("ISSUED");
        assertThat(TestApi.body(api.get("/api/plans/" + planId + "/verify")).get("valid")).isEqualTo(true);
    }

    @Test
    void invalidTransitionCreatesNeitherEventNorStateChange() {
        TestApi api = new TestApi(url(""), http);
        long planId = readyForStart(api, "PL-TX-INVALID");
        int eventsBefore = eventCount(api, planId);

        // WRITE_OFF from DRAFT is illegal
        var illegal = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "WRITE_OFF"), "tx-key-3");
        assertThat(illegal.getStatusCode().value()).isEqualTo(409);

        Map<String, Object> state = internalState(api, planId);
        assertThat(state.get("status")).isEqualTo("DRAFT");
        assertThat(((Number) state.get("eventCount")).intValue()).isEqualTo(eventsBefore);
    }

    private long readyForStart(TestApi api, String planNo) {
        var created = api.post("/api/plans", Map.of("planNo", planNo, "title", "t"), null);
        return ((Number) ((Map<?, ?>) TestApi.body(created).get("plan")).get("id")).longValue();
    }

    private int eventCount(TestApi api, long planId) {
        return ((Number) internalState(api, planId).get("eventCount")).intValue();
    }

    private Map<String, Object> internalState(TestApi api, long planId) {
        return TestApi.body(api.get("/api/test/plans/" + planId + "/internal-state"));
    }
}
