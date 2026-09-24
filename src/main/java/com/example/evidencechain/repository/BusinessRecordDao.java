package com.example.evidencechain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Inserts into the four business result tables (confirmation / mock receipt /
 * rejection / write-off). Each insert shares the caller's transaction, which
 * is the same transaction that appends the evidence event and updates the
 * plan status.
 */
@Repository
public class BusinessRecordDao {

    private final JdbcTemplate jdbc;

    public BusinessRecordDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertConfirmation(long planId, String operator, String remark,
                                   String idempotencyKey, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO plan_confirmation (plan_id, operator, remark, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, planId, operator, remark, idempotencyKey, Timestamp.from(at));
    }

    public long insertMockReceipt(long planId, String channel, String payload,
                                  String idempotencyKey, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO plan_mock_receipt (plan_id, channel, payload, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, planId, channel, payload, idempotencyKey, Timestamp.from(at));
    }

    public long insertRejection(long planId, String reason, String operator,
                                String idempotencyKey, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO plan_rejection (plan_id, reason, operator, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, planId, reason, operator, idempotencyKey, Timestamp.from(at));
    }

    public long insertWriteoff(long planId, String operator, String remark,
                               String idempotencyKey, Instant at) {
        return jdbc.queryForObject("""
                INSERT INTO plan_writeoff (plan_id, operator, remark, idempotency_key, created_at)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, planId, operator, remark, idempotencyKey, Timestamp.from(at));
    }

    public long countConfirmations(long planId) {
        return count("SELECT count(*) FROM plan_confirmation WHERE plan_id = ?", planId);
    }

    public long countMockReceipts(long planId) {
        return count("SELECT count(*) FROM plan_mock_receipt WHERE plan_id = ?", planId);
    }

    public long countRejections(long planId) {
        return count("SELECT count(*) FROM plan_rejection WHERE plan_id = ?", planId);
    }

    public long countWriteoffs(long planId) {
        return count("SELECT count(*) FROM plan_writeoff WHERE plan_id = ?", planId);
    }

    public boolean confirmationExists(long id) {
        return exists("SELECT count(*) FROM plan_confirmation WHERE id = ?", id);
    }

    public boolean mockReceiptExists(long id) {
        return exists("SELECT count(*) FROM plan_mock_receipt WHERE id = ?", id);
    }

    public boolean rejectionExists(long id) {
        return exists("SELECT count(*) FROM plan_rejection WHERE id = ?", id);
    }

    public boolean writeoffExists(long id) {
        return exists("SELECT count(*) FROM plan_writeoff WHERE id = ?", id);
    }

    private boolean exists(String sql, long id) {
        Long value = jdbc.queryForObject(sql, Long.class, id);
        return value != null && value > 0;
    }

    private long count(String sql, long planId) {
        Long value = jdbc.queryForObject(sql, Long.class, planId);
        return value == null ? 0 : value;
    }
}
