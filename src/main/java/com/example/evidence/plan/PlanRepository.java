package com.example.evidence.plan;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class PlanRepository {

    private final JdbcClient jdbc;

    public PlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String planNo, String title, String status, String source, Long legacyRef, Instant now) {
        return jdbc.sql("""
                        INSERT INTO plan (plan_no, title, status, source, legacy_ref, created_at, updated_at)
                        VALUES (:planNo, :title, :status, :source, :legacyRef, :now, :now)
                        RETURNING id
                        """)
                .param("planNo", planNo)
                .param("title", title)
                .param("status", status)
                .param("source", source)
                .param("legacyRef", legacyRef)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .single();
    }

    /**
     * Insert a plan preserving the original historical timestamps. Used only by
     * the legacy anchoring migration; the legacy row itself is never modified.
     */
    public long insertLegacy(String planNo, String title, String status, long legacyRef,
                             Instant createdAt, Instant updatedAt) {
        return jdbc.sql("""
                        INSERT INTO plan (plan_no, title, status, source, legacy_ref, created_at, updated_at)
                        VALUES (:planNo, :title, :status, 'LEGACY', :legacyRef, :createdAt, :updatedAt)
                        RETURNING id
                        """)
                .param("planNo", planNo)
                .param("title", title)
                .param("status", status)
                .param("legacyRef", legacyRef)
                .param("createdAt", Timestamp.from(createdAt))
                .param("updatedAt", Timestamp.from(updatedAt))
                .query(Long.class)
                .single();
    }

    /**
     * Pessimistic row lock; must be called inside a transaction.
     * Serializes concurrent operations on the same plan so seq allocation can
     * never race.
     */
    public Optional<PlanRow> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT id, plan_no, title, status, source, legacy_ref, created_at, updated_at "
                        + "FROM plan WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query((rs, n) -> PlanRow.from(rs))
                .optional();
    }

    public Optional<PlanRow> findById(long id) {
        return jdbc.sql("SELECT id, plan_no, title, status, source, legacy_ref, created_at, updated_at "
                        + "FROM plan WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> PlanRow.from(rs))
                .optional();
    }

    public Optional<PlanRow> findByPlanNo(String planNo) {
        return jdbc.sql("SELECT id, plan_no, title, status, source, legacy_ref, created_at, updated_at "
                        + "FROM plan WHERE plan_no = :planNo")
                .param("planNo", planNo)
                .query((rs, n) -> PlanRow.from(rs))
                .optional();
    }

    public Optional<PlanRow> findByLegacyRef(long legacyRef) {
        return jdbc.sql("SELECT id, plan_no, title, status, source, legacy_ref, created_at, updated_at "
                        + "FROM plan WHERE legacy_ref = :legacyRef")
                .param("legacyRef", legacyRef)
                .query((rs, n) -> PlanRow.from(rs))
                .optional();
    }

    public long updateStatus(long id, String status, Instant now) {
        return jdbc.sql("UPDATE plan SET status = :status, updated_at = :now WHERE id = :id")
                .param("status", status)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .update();
    }

    public record PlanRow(long id, String planNo, String title, String status, String source,
                          Long legacyRef, Instant createdAt, Instant updatedAt) {
        static PlanRow from(java.sql.ResultSet rs) throws java.sql.SQLException {
            return new PlanRow(
                    rs.getLong("id"),
                    rs.getString("plan_no"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("source"),
                    (Long) rs.getObject("legacy_ref"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant());
        }
    }
}
