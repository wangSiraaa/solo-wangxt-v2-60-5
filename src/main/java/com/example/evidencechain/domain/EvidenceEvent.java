package com.example.evidencechain.domain;

import java.time.Instant;

/**
 * One immutable evidence record. Rows are append-only; any UPDATE/DELETE
 * against plan_evidence_event is detectable by verification.
 */
public record EvidenceEvent(
        Long id,
        Long planId,
        int seq,
        EventType eventType,
        PlanStatus fromStatus,
        PlanStatus toStatus,
        String prevHash,
        String eventHash,
        String payloadJson,
        String payloadHash,
        String idempotencyKey,
        boolean anchor,
        Instant eventTime,
        Instant createdAt
) {
    /** SHA-256 of the genesis anchor: prevHash is literally this string. */
    public static final String GENESIS_HASH = "0".repeat(64);
}
