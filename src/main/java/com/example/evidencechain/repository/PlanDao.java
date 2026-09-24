package com.example.evidencechain.repository;

import com.example.evidencechain.domain.PlanStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Access to the {@code plan} table. All status-changing statements must run
 * inside {@code EvidenceService} transactions that also write the evidence
 * event.
 */
@Repository
public class PlanDao {

    private final JdbcTemplate jdbc;

    public PlanDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<PlanRow> MAPPER = (rs, n) -> new PlanRow(
            rs.getLong("id"),
            rs.getString("plan_no"),
            PlanStatus.valueOf(rs.getString("status")),
            rs.getString("title"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        );

    /** Raw mutable carrier used inside transactions. */
    public record PlanRow(long id, String planNo, PlanStatus status, String title,
                          Instant createdAt, Instant updatedAt) {
    }

    public long insert(String planNo, PlanStatus status, String title, Instant createdAt) {
        return jdbc.queryForObject("""
                INSERT INTO plan (plan_no, status, title, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id
                """, Long.class, planNo, status.name(), title,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
    }

    /** Locks the plan row for the duration of the current transaction. */
    public PlanRow lockById(long planId) {
        List<PlanRow> rows = jdbc.query(
                "SELECT * FROM plan WHERE id = ? FOR UPDATE", MAPPER, planId);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.get(0);
    }

    public PlanRow findById(long planId) {
        List<PlanRow> rows = jdbc.query("SELECT * FROM plan WHERE id = ?", MAPPER, planId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public PlanRow findByPlanNo(String planNo) {
        List<PlanRow> rows = jdbc.query("SELECT * FROM plan WHERE plan_no = ?", MAPPER, planNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<PlanRow> findAll() {
        return jdbc.query("SELECT * FROM plan ORDER BY id", MAPPER);
    }

    /** Ids of plans that currently have zero evidence events (legacy data). */
    public List<Long> findIdsWithoutEvents() {
        return jdbc.queryForList("""
                SELECT p.id FROM plan p
                WHERE NOT EXISTS (SELECT 1 FROM plan_evidence_event e WHERE e.plan_id = p.id)
                ORDER BY p.id
                """, Long.class);
    }

    public void updateStatus(long planId, PlanStatus status, Instant updatedAt) {
        int rows = jdbc.update(
                "UPDATE plan SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Timestamp.from(updatedAt), planId);
        if (rows != 1) {
            throw new IllegalStateException("Plan row vanished during update: " + planId);
        }
    }
}
