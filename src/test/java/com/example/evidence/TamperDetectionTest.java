package com.example.evidence;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance 3: simulated tampering is detected and the first break located
 * precisely (seq + reason code), including: payload rewrite, hash overwrite,
 * event deletion (gap), and out-of-band status change.
 */
class TamperDetectionTest extends AbstractIntegrationTest {

    @Test
    void payloadTamperingIsLocatedAtTheExactEvent() {
        TestApi api = new TestApi(url(""), http);
        long planId = fullChain(api, "PL-TAMP-P");

        // tamper with the 3rd event (CONFIRMED) directly in the database
        var tampered = api.post("/api/test/plans/" + planId + "/events/3/tamper-payload",
                Map.of("note", "changed by attacker"));
        assertThat(tampered.getStatusCode().is2xxSuccessful()).isTrue();

        var verify = api.get("/api/plans/" + planId + "/verify");
        Map<String, Object> v = TestApi.body(verify);
        assertThat(v.get("valid")).isEqualTo(false);
        assertThat(((Number) v.get("firstBrokenSeq")).longValue()).isEqualTo(3L);
        assertThat(v.get("reasonCode")).isEqualTo("CONTENT_TAMPERED");
        // events 1..2 proven intact, reported as verified hashes
        assertThat(v.get("verifiedEventHashes")).asList().hasSize(2);

        // global sweep flags this plan but healthy plans stay valid
        var sweep = api.get("/api/plans/verify");
        Map<String, Object> all = TestApi.body(sweep);
        assertThat(all.get("allValid")).isEqualTo(false);
    }

    @Test
    void hashOverwriteIsDetected() {
        TestApi api = new TestApi(url(""), http);
        long planId = fullChain(api, "PL-TAMP-H");
        api.post("/api/test/plans/" + planId + "/events/2/tamper-hash", Map.of());

        Map<String, Object> v = TestApi.body(api.get("/api/plans/" + planId + "/verify"));
        assertThat(v.get("valid")).isEqualTo(false);
        // event 2 no longer recomputes to the stored hash -> break reported at 2
        // (the link of event 3 is implicitly broken too; first break wins)
        assertThat(((Number) v.get("firstBrokenSeq")).longValue()).isEqualTo(2L);
    }

    @Test
    void deletedEventLeavesLocatedGap() {
        TestApi api = new TestApi(url(""), http);
        long planId = fullChain(api, "PL-TAMP-D");
        api.post("/api/test/plans/" + planId + "/events/2/delete", Map.of());

        Map<String, Object> v = TestApi.body(api.get("/api/plans/" + planId + "/verify"));
        assertThat(v.get("valid")).isEqualTo(false);
        // after deleting seq 2, the next stored row is seq 3 -> seq gap at 3
        assertThat(((Number) v.get("firstBrokenSeq")).longValue()).isEqualTo(3L);
        assertThat(v.get("reasonCode")).isEqualTo("SEQ_GAP");
    }

    @Test
    void outOfBandStatusChangeIsStateDivergence() {
        TestApi api = new TestApi(url(""), http);
        long planId = fullChain(api, "PL-TAMP-S");
        api.post("/api/test/plans/" + planId + "/tamper-status", Map.of("status", "REJECTED"));

        Map<String, Object> v = TestApi.body(api.get("/api/plans/" + planId + "/verify"));
        assertThat(v.get("valid")).isEqualTo(false);
        assertThat(v.get("reasonCode")).isEqualTo("STATE_DIVERGED");
        // all hashes check out, so the break is reported at the last event
        assertThat(((Number) v.get("firstBrokenSeq")).longValue()).isEqualTo(5L);
    }

    @Test
    void healthyChainStillVerifiesAlongsideTamperedOne() {
        TestApi api = new TestApi(url(""), http);
        long healthy = fullChain(api, "PL-OK");
        long tampered = fullChain(api, "PL-BAD");
        api.post("/api/test/plans/" + tampered + "/events/4/tamper-payload", Map.of());

        Map<String, Object> vHealthy = TestApi.body(api.get("/api/plans/" + healthy + "/verify"));
        assertThat(vHealthy.get("valid")).isEqualTo(true);
        Map<String, Object> vBad = TestApi.body(api.get("/api/plans/" + tampered + "/verify"));
        assertThat(vBad.get("valid")).isEqualTo(false);
        assertThat(((Number) vBad.get("firstBrokenSeq")).longValue()).isEqualTo(4L);
    }

    private long fullChain(TestApi api, String planNo) {
        var created = api.post("/api/plans", Map.of("planNo", planNo, "title", "t"), null);
        long planId = ((Number) ((Map<?, ?>) TestApi.body(created).get("plan")).get("id")).longValue();
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "START"), null);
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "CONFIRM"), null);
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "RECEIPT"), null);
        api.post("/api/plans/" + planId + "/actions", Map.of("action", "WRITE_OFF"), null);
        ResponseEntity<Map> verify = api.get("/api/plans/" + planId + "/verify");
        assertThat(TestApi.body(verify).get("valid")).isEqualTo(true);
        return planId;
    }
}
