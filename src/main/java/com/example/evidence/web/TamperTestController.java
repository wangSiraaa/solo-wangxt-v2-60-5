package com.example.evidence.web;

import com.example.evidence.chain.EvidenceEvent;
import com.example.evidence.chain.FailureInjector;
import com.example.evidence.plan.PlanException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Test-only controls for the acceptance scenarios "simulated tampering must be
 * located" and "transaction failure leaves no half-finished record". Disabled
 * unless {@code evidence.test-endpoints-enabled=true}.
 */
@RestController
@RequestMapping("/api/test/plans")
public class TamperTestController {

    private final JdbcClient jdbc;
    private final FailureInjector failureInjector;

    @Value("${evidence.test-endpoints-enabled:false}")
    private boolean enabled;

    public TamperTestController(JdbcClient jdbc, FailureInjector failureInjector) {
        this.jdbc = jdbc;
        this.failureInjector = failureInjector;
    }

    /**
     * Arm a one-shot transaction failure for the very next state-changing
     * request: AFTER_EVIDENCE_INSERT (proves no "evidence without state") or
     * AFTER_STATE_UPDATE (proves no "state without evidence" on pre-commit crash).
     */
    @PostMapping("/arm-failure/{point}")
    public Map<String, Object> armFailure(@PathVariable String point) {
        ensureEnabled();
        FailureInjector.Point p = FailureInjector.Point.valueOf(point);
        failureInjector.arm(p);
        return Map.of("armed", p.name());
    }

    @PostMapping("/reset-failure")
    public Map<String, Object> resetFailure() {
        ensureEnabled();
        failureInjector.reset();
        return Map.of("reset", true);
    }

    /** Internal-state probe for tests: row counts and current status. */
    @GetMapping("/{planId}/internal-state")
    public Map<String, Object> internalState(@PathVariable long planId) {
        ensureEnabled();
        Integer eventCount = jdbc.sql(
                        "SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = :id")
                .param("id", planId).query(Integer.class).single();
        List<String> statuses = jdbc.sql("SELECT status FROM plan WHERE id = :id")
                .param("id", planId).query(String.class).list();
        return Map.of("planId", planId,
                "eventCount", eventCount == null ? 0 : eventCount,
                "status", statuses.isEmpty() ? "ABSENT" : statuses.get(0));
    }

    /** Rewrite payload content while leaving the stored hash untouched -> CONTENT_TAMPERED. */
    @PostMapping("/{planId}/events/{seq}/tamper-payload")
    public Map<String, Object> tamperPayload(@PathVariable long planId, @PathVariable long seq,
                                             @RequestBody(required = false) Map<String, Object> body) {
        ensureEnabled();
        EvidenceEvent event = getEvent(planId, seq);
        String injected = body == null || !body.containsKey("note")
                ? "TAMPERED-" + System.nanoTime()
                : String.valueOf(body.get("note"));
        String newJson = "{\"tampered\":true,\"note\":" + quote(injected) + "}";
        jdbc.sql("UPDATE plan_evidence_event SET payload = CAST(:p AS jsonb) WHERE plan_id = :id AND seq = :seq")
                .param("p", newJson).param("id", planId).param("seq", seq).update();
        return Map.of("planId", planId, "seq", seq, "kind", "CONTENT_TAMPERED",
                "previousHash", event.eventHash());
    }

    /** Overwrite the stored event hash with garbage -> CONTENT_TAMPERED at this seq. */
    @PostMapping("/{planId}/events/{seq}/tamper-hash")
    public Map<String, Object> tamperHash(@PathVariable long planId, @PathVariable long seq) {
        ensureEnabled();
        EvidenceEvent event = getEvent(planId, seq);
        String bad = "f".repeat(64);
        jdbc.sql("UPDATE plan_evidence_event SET event_hash = :h WHERE plan_id = :id AND seq = :seq")
                .param("h", bad).param("id", planId).param("seq", seq).update();
        return Map.of("planId", planId, "seq", seq, "kind", "CONTENT_TAMPERED",
                "previousHash", event.eventHash());
    }

    /** Delete an event row -> SEQ_GAP (or BROKEN_LINK via prev_hash mismatch at the following row). */
    @PostMapping("/{planId}/events/{seq}/delete")
    public Map<String, Object> deleteEvent(@PathVariable long planId, @PathVariable long seq) {
        ensureEnabled();
        getEvent(planId, seq);
        jdbc.sql("DELETE FROM plan_evidence_event WHERE plan_id = :id AND seq = :seq")
                .param("id", planId).param("seq", seq).update();
        return Map.of("planId", planId, "seq", seq, "kind", "SEQ_GAP");
    }

    /** Corrupt the current plan status without an event -> STATE_DIVERGED. */
    @PostMapping("/{planId}/tamper-status")
    public Map<String, Object> tamperStatus(@PathVariable long planId, @RequestBody Map<String, String> body) {
        ensureEnabled();
        getPlan(planId);
        String status = body.getOrDefault("status", "WRITTEN_OFF");
        jdbc.sql("UPDATE plan SET status = :s WHERE id = :id")
                .param("s", status).param("id", planId).update();
        return Map.of("planId", planId, "kind", "STATE_DIVERGED", "status", status);
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw PlanException.notFound("test endpoints are disabled");
        }
    }

    private EvidenceEvent getEvent(long planId, long seq) {
        List<EvidenceEvent> events = jdbcTemplateEvents(planId);
        return events.stream().filter(e -> e.seq() == seq).findFirst()
                .orElseThrow(() -> PlanException.notFound("event not found: plan " + planId + " seq " + seq));
    }

    private void getPlan(long planId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM plan WHERE id = :id")
                .param("id", planId).query(Integer.class).single();
        if (count == null || count == 0) {
            throw PlanException.notFound("plan not found: " + planId);
        }
    }

    private List<EvidenceEvent> jdbcTemplateEvents(long planId) {
        return jdbc.sql("SELECT * FROM plan_evidence_event WHERE plan_id = :id ORDER BY seq")
                .param("id", planId)
                .query((rs, n) -> new EvidenceEvent(
                        rs.getLong("id"), rs.getLong("plan_id"), rs.getLong("seq"),
                        rs.getString("event_type"), rs.getString("state_before"), rs.getString("state_after"),
                        Map.of(), rs.getString("request_id"), rs.getString("request_fingerprint"),
                        rs.getString("prev_hash"), rs.getString("event_hash"),
                        rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static String quote(String s) {
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
