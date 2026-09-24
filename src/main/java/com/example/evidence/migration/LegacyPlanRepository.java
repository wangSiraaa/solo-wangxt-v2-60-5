package com.example.evidence.migration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class LegacyPlanRepository {

    private final JdbcClient jdbc;

    public LegacyPlanRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LegacyRow> findAll() {
        return jdbc.sql("SELECT id, plan_no, title, status, created_at, updated_at, extra "
                        + "FROM legacy_plan ORDER BY id ASC")
                .query((rs, n) -> new LegacyRow(
                        rs.getLong("id"),
                        rs.getString("plan_no"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("extra")))
                .list();
    }

    public Optional<LegacyRow> findById(long id) {
        return jdbc.sql("SELECT id, plan_no, title, status, created_at, updated_at, extra "
                        + "FROM legacy_plan WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new LegacyRow(
                        rs.getLong("id"),
                        rs.getString("plan_no"),
                        rs.getString("title"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("extra")))
                .optional();
    }

    public void insert(long id, String planNo, String title, String status,
                       Instant createdAt, Instant updatedAt, String extraJson) {
        jdbc.sql("""
                        INSERT INTO legacy_plan (id, plan_no, title, status, created_at, updated_at, extra)
                        VALUES (:id, :planNo, :title, :status, :createdAt, :updatedAt, CAST(:extra AS jsonb))
                        """)
                .param("id", id)
                .param("planNo", planNo)
                .param("title", title)
                .param("status", status)
                .param("createdAt", createdAt == null ? null : Timestamp.from(createdAt))
                .param("updatedAt", updatedAt == null ? null : Timestamp.from(updatedAt))
                .param("extra", extraJson == null ? "{}" : extraJsonJsonSafe(extraJson))
                .update();
    }

    private static String extraJsonJsonSafe(String s) {
        return s;
    }

    public record LegacyRow(long id, String planNo, String title, String status,
                            Instant createdAt, Instant updatedAt, String extraJson) {
    }
}
