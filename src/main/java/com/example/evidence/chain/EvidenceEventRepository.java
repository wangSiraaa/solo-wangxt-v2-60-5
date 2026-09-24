package com.example.evidence.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class EvidenceEventRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public EvidenceEventRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record NewEvent(long planId, long seq, String eventType, String stateBefore, String stateAfter,
                           Map<String, Object> payload, String requestId, String requestFingerprint,
                           String prevHash, String eventHash, Instant occurredAt) {
    }

    public void insert(NewEvent e) {
        String json;
        try {
            json = objectMapper.writeValueAsString(e.payload());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        jdbc.sql("""
                        INSERT INTO plan_evidence_event
                            (plan_id, seq, event_type, state_before, state_after, payload,
                             request_id, request_fingerprint, prev_hash, event_hash, created_at)
                        VALUES
                            (:planId, :seq, :eventType, :stateBefore, :stateAfter, CAST(:payload AS jsonb),
                             :requestId, :requestFingerprint, :prevHash, :eventHash, :createdAt)
                        """)
                .param("planId", e.planId())
                .param("seq", e.seq())
                .param("eventType", e.eventType())
                .param("stateBefore", e.stateBefore())
                .param("stateAfter", e.stateAfter())
                .param("payload", json)
                .param("requestId", e.requestId())
                .param("requestFingerprint", e.requestFingerprint())
                .param("prevHash", e.prevHash())
                .param("eventHash", e.eventHash())
                .param("createdAt", Timestamp.from(e.occurredAt()))
                .update();
    }

    public List<EvidenceEvent> findByPlanOrdered(long planId) {
        return jdbc.sql("SELECT * FROM plan_evidence_event WHERE plan_id = :planId ORDER BY seq ASC")
                .param("planId", planId)
                .query((rs, n) -> map(rs))
                .list();
    }

    public Optional<EvidenceEvent> findByRequestId(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT * FROM plan_evidence_event WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query((rs, n) -> map(rs))
                .optional();
    }

    public long countByPlan(long planId) {
        return jdbc.sql("SELECT COUNT(*) FROM plan_evidence_event WHERE plan_id = :planId")
                .param("planId", planId)
                .query(Long.class)
                .single();
    }

    public long countAll() {
        return jdbc.sql("SELECT COUNT(*) FROM plan_evidence_event")
                .query(Long.class)
                .single();
    }

    public List<Long> findAllPlanIds() {
        return jdbc.sql("SELECT id FROM plan ORDER BY id ASC")
                .query(Long.class)
                .list();
    }

    @SuppressWarnings("unchecked")
    private EvidenceEvent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> payload;
        try {
            String json = rs.getString("payload");
            payload = json == null ? new HashMap<>() : objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new java.sql.SQLException("cannot read event payload", e);
        }
        return new EvidenceEvent(
                rs.getLong("id"),
                rs.getLong("plan_id"),
                rs.getLong("seq"),
                rs.getString("event_type"),
                rs.getString("state_before"),
                rs.getString("state_after"),
                payload,
                rs.getString("request_id"),
                rs.getString("request_fingerprint"),
                rs.getString("prev_hash"),
                rs.getString("event_hash"),
                rs.getTimestamp("created_at").toInstant());
    }
}
