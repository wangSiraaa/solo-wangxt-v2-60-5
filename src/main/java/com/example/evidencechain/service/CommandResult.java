package com.example.evidencechain.service;

import com.example.evidencechain.domain.PlanStatus;

/**
 * Result of applying (or replaying) a command.
 *
 * @param replayed true when this call was a duplicate idempotent replay; no
 *                 new business record and no new evidence event was created
 * @param businessRecordId id of the confirmation/receipt/rejection/writeoff
 *                         row; null for START
 */
public record CommandResult(
        long planId,
        PlanStatus fromStatus,
        PlanStatus toStatus,
        int seq,
        String eventHash,
        String prevHash,
        Long businessRecordId,
        boolean replayed
) {
}
