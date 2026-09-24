package com.example.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance 1: the complete 开工 -> 确认 -> 模拟回执 -> 销记 lifecycle is
 * recorded as one unbroken hash chain and verifies clean.
 */
class FullLifecycleChainTest extends AbstractIntegrationTest {

    @Test
    @SuppressWarnings("unchecked")
    void completeStartToWriteOffChainIsVerifiable() {
        TestApi api = new TestApi(url(""), http);

        // create
        ResponseEntity<Map> created = api.post("/api/plans",
                Map.of("planNo", "PL-LIFE-1", "title", "annual maintenance"), "k-create");
        assertThat(created.getStatusCode().value()).isEqualTo(200);
        long planId = planIdFromPlan(TestApi.body(created));

        // 开工 DRAFT -> ISSUED
        var started = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "START", "payload", Map.of("operator", "alice", "shift", "night")), "k-start");
        assertThat(started.getStatusCode().is2xxSuccessful()).isTrue();

        // 确认 ISSUED -> CONFIRMED
        var confirmed = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "CONFIRM", "payload", Map.of("confirmer", "bob")), "k-confirm");
        assertThat(confirmed.getStatusCode().is2xxSuccessful()).isTrue();

        // 模拟回执 CONFIRMED -> RECEIPTED (receipt number generated in-tx)
        var receipt = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "RECEIPT", "payload", Map.of("channel", "mock-gateway")), "k-receipt");
        assertThat(receipt.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> receiptEvent = (Map<String, Object>) TestApi.body(receipt).get("event");
        Map<String, Object> receiptPayload = (Map<String, Object>) receiptEvent.get("payload");
        assertThat(receiptPayload).containsEntry("receiptNo", "SIM-" + planId + "-4")
                .containsEntry("simulated", true);

        // 销记 RECEIPTED -> WRITTEN_OFF
        var writtenOff = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "WRITE_OFF", "payload", Map.of("writtenOffBy", "carol")), "k-off");
        assertThat(writtenOff.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> planView = (Map<String, Object>) TestApi.body(writtenOff).get("plan");
        assertThat(planView.get("status")).isEqualTo("WRITTEN_OFF");

        // replay query: five events in order with contiguous seq
        var evidence = api.get("/api/plans/" + planId + "/evidence");
        Map<String, Object> chain = TestApi.body(evidence);
        List<Map<String, Object>> events = (List<Map<String, Object>>) chain.get("events");
        assertThat(events).hasSize(5);
        for (int i = 0; i < events.size(); i++) {
            assertThat(((Number) events.get(i).get("seq")).longValue()).isEqualTo(i + 1L);
        }
        assertThat(events.get(0).get("eventType")).isEqualTo("CREATED");
        assertThat(events.get(0).get("genesis")).isEqualTo(true);
        assertThat(events.get(4).get("eventType")).isEqualTo("WRITTEN_OFF");

        // each prevHash equals the prior eventHash
        String zeroHash = "0".repeat(64);
        assertThat(events.get(0).get("prevHash")).isEqualTo(zeroHash);
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i).get("prevHash")).isEqualTo(events.get(i - 1).get("eventHash"));
        }

        // verification passes
        var verify = api.get("/api/plans/" + planId + "/verify");
        Map<String, Object> v = TestApi.body(verify);
        assertThat(v.get("valid")).isEqualTo(true);
        assertThat(v.get("reasonCode")).isEqualTo("OK");
        assertThat((List<?>) v.get("verifiedEventHashes")).hasSize(5);

        // whole-system sweep passes as well
        var verifyAll = api.get("/api/plans/verify");
        assertThat(TestApi.body(verifyAll).get("allValid")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectionBranchFromConfirmedIsRecordedAndTerminal() {
        TestApi api = new TestApi(url(""), http);
        long planId = planIdFromPlan(TestApi.body(api.post("/api/plans",
                Map.of("planNo", "PL-REJ-1", "title", "to reject"), null)));
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "START"), null);
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "CONFIRM"), null);
        var rejected = api.post("/api/plans/" + planId + "/actions",
                Map.of("action", "REJECT", "payload", Map.of("reason", "defect")), null);
        assertThat(((Map<?, ?>) TestApi.body(rejected).get("plan")).get("status")).isEqualTo("REJECTED");

        // terminal state: further actions rejected by the state machine, no event appended
        var illegal = api.post("/api/plans/" + planId + "/actions", Map.of("action", "WRITE_OFF"), null);
        assertThat(illegal.getStatusCode().value()).isEqualTo(409);
        assertThat(TestApi.body(illegal).get("code")).isEqualTo("INVALID_TRANSITION");

        var evidence = api.get("/api/plans/" + planId + "/evidence");
        long eventCount = ((List<?>) ((Map<String, Object>) TestApi.body(evidence)).get("events")).size();
        assertThat(eventCount).isEqualTo(4);
        assertThat(TestApi.body(api.get("/api/plans/" + planId + "/verify")).get("valid")).isEqualTo(true);
    }

    @SuppressWarnings("unchecked")
    private long planIdFromPlan(Map<String, Object> operationOrPlan) {
        Object plan = operationOrPlan.get("plan");
        Map<String, Object> planMap = plan instanceof Map ? (Map<String, Object>) plan : operationOrPlan;
        return ((Number) planMap.get("id")).longValue();
    }
}
