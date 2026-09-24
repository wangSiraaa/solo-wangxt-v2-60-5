package com.example.evidencechain.repository;

import com.example.evidencechain.domain.EventType;
import com.example.evidencechain.domain.PlanStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Append-only access to {@code plan_evidence_event}.
 *
 * <p>There is deliberately no update/delete method: service code can only
 * append. The last event is always read {@code FOR UPDATE} so two concurrent
 * transactions can never derive the same {@code (plan_id, seq)}.
 */
@Repository
public class EvidenceEventDao {

    private final JdbcTemplate jdbc;

    public EvidenceEventDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record EventRow(long id, long planId, int seq, EventType eventType,
                           PlanStatus fromStatus, PlanStatus toStatus,
                           String prevHash, String eventHash,
                           String payloadJson, String payloadHash,
                           Long businessRecordId, String idempotencyKey, boolean anchor,
                           Instant eventTime, Instant createdAt) {
    }

    private static final RowMapper<EventRow> MAPPER = (rs, n) -> new EventRow(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getInt("seq"),
            EventType.valueOf(rs.getString("event_type")),
            getStatus(rs, "from_status"),
            PlanStatus.valueOf(rs.getString("to_status")),
            rs.getString("prev_hash"),
            rs.getString("event_hash"),
            rs.getString("payload_json"),
            rs.getString("payload_hash"),
            (Long) rs.getObject("business_record_id"),
            rs.getString("idempotency_key"),
            rs.getBoolean("anchor"),
            rs.getTimestamp("event_time").toInstant(),
            rs.getTimestamp("created_at").toInstant()
    );

    private static PlanStatus getStatus(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        String value = rs.getString(column);
        return value == null ? null : PlanStatus.valueOf(value);
    }

    /** Locks the last event of a plan; returns null when the chain is empty. */
    public EventRow lockLast(long planId) {
        List<EventRow> rows = jdbc.query("""
                SELECT * FROM plan_evidence_event
                WHERE plan_id = ?
                ORDER BY seq DESC
                LIMIT 1
                FOR UPDATE
                """, MAPPER, planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public EventRow findByIdempotencyKey(long planId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return null;
        }
        List<EventRow> rows = jdbc.query("""
                SELECT * FROM plan_evidence_event
                WHERE plan_id = ? AND idempotency_key = ?
                """, MAPPER, planId, idempotencyKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<EventRow> findByPlanOrderBySeq(long planId) {
        return jdbc.query("""
                SELECT * FROM plan_evidence_event
                WHERE plan_id = ?
                ORDER BY seq
                """, MAPPER, planId);
    }

    public long append(EventRow input) {
        return jdbc.queryForObject("""
                INSERT INTO plan_evidence_event
                    (plan_id, seq, event_type, from_status, to_status, prev_hash,
                     event_hash, payload_json, payload_hash, business_record_id,
                     idempotency_key, anchor, event_time, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                input.planId(), input.seq(), input.eventType().name(),
                input.fromStatus() == null ? null : input.fromStatus().name(),
                input.toStatus().name(),
                input.prevHash(), input.eventHash(),
                input.payloadJson(), input.payloadHash(),
                input.businessRecordId(), input.idempotencyKey(), input.anchor(),
                Timestamp.from(input.eventTime()), Timestamp.from(Instant.now()));
    }

    public long countByPlan(long planId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM plan_evidence_event WHERE plan_id = ?",
                Long.class, planId);
        return count == null ? 0 : count;
    }
}
