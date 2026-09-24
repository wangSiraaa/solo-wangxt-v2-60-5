package com.example.evidencechain.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Read/write access for the legacy staging table used by safe anchoring.
 * Original legacy rows are never deleted or rewritten; they are marked with
 * the anchor event id once anchored.
 */
@Repository
public class LegacySnapshotDao {

    private final JdbcTemplate jdbc;

    public LegacySnapshotDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record LegacyRow(long id, String planNo, String status, String title,
                            Instant legacyCreatedAt, Instant migratedAt, Long anchorEventId) {
    }

    private static final RowMapper<LegacyRow> MAPPER = (rs, n) -> new LegacyRow(
            rs.getLong("id"),
            rs.getString("plan_no"),
            rs.getString("status"),
            rs.getString("title"),
            rs.getTimestamp("legacy_created_at").toInstant(),
            rs.getTimestamp("migrated_at") == null ? null : rs.getTimestamp("migrated_at").toInstant(),
            (Long) rs.getObject("anchor_event_id")
    );

    public long insert(String planNo, String status, String title, Instant legacyCreatedAt) {
        return jdbc.queryForObject("""
                INSERT INTO legacy_plan_snapshot (plan_no, status, title, legacy_created_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, Long.class, planNo, status, title, Timestamp.from(legacyCreatedAt));
    }

    public List<LegacyRow> findUnmigrated() {
        return jdbc.query("""
                SELECT * FROM legacy_plan_snapshot
                WHERE anchor_event_id IS NULL
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                """, MAPPER);
    }

    public void markMigrated(long snapshotId, long anchorEventId, Instant migratedAt) {
        jdbc.update("""
                UPDATE legacy_plan_snapshot
                SET migrated_at = ?, anchor_event_id = ?
                WHERE id = ? AND anchor_event_id IS NULL
                """, Timestamp.from(migratedAt), anchorEventId, snapshotId);
    }

    public List<LegacyRow> findAll() {
        return jdbc.query("SELECT * FROM legacy_plan_snapshot ORDER BY id", MAPPER);
    }
}
