package com.example.evidence.chain;

import java.time.Instant;
import java.util.Map;

/** One row of plan_evidence_event as seen by the service and verification layers. */
public record EvidenceEvent(
        long id,
        long planId,
        long seq,
        String eventType,
        String stateBefore,
        String stateAfter,
        Map<String, Object> payload,
        String requestId,
        String requestFingerprint,
        String prevHash,
        String eventHash,
        Instant createdAt) {
}
