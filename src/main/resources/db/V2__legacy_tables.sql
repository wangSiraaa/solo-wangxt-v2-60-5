-- Pre-existing ("legacy") maintenance plans. The table is deliberately kept
-- untouched by the anchoring migration: historical records are never deleted
-- or rewritten, only snapshotted into the evidence chain.

CREATE TABLE IF NOT EXISTS legacy_plan (
    id         BIGINT PRIMARY KEY,
    plan_no    VARCHAR(64)  NOT NULL,
    title      VARCHAR(255) NOT NULL,
    status     VARCHAR(32)  NOT NULL,
    created_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ,
    extra      JSONB
);
