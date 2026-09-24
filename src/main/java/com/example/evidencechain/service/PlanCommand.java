package com.example.evidencechain.service;

import com.example.evidencechain.domain.EventType;

/**
 * Command describing one requested lifecycle operation.
 *
 * @param idempotencyKey required client-generated key; replays with the same
 *                       key return the original result instead of appending
 *                       a new event or business record
 */
public record PlanCommand(
        long planId,
        EventType eventType,
        String operator,
        String remark,
        String channel,
        String receiptPayload,
        String reason,
        String idempotencyKey
) {

    public static PlanCommand start(long planId, String operator, String key) {
        return new PlanCommand(planId, EventType.START, operator, null,
                null, null, null, key);
    }

    public static PlanCommand confirm(long planId, String operator, String remark, String key) {
        return new PlanCommand(planId, EventType.CONFIRMATION, operator, remark,
                null, null, null, key);
    }

    public static PlanCommand receipt(long planId, String channel, String payload, String key) {
        return new PlanCommand(planId, EventType.MOCK_RECEIPT, "system",
                null, channel, payload, null, key);
    }

    public static PlanCommand reject(long planId, String operator, String reason, String key) {
        return new PlanCommand(planId, EventType.REJECTION, operator,
                null, null, null, reason, key);
    }

    public static PlanCommand writeoff(long planId, String operator, String remark, String key) {
        return new PlanCommand(planId, EventType.WRITEOFF, operator, remark,
                null, null, null, key);
    }
}
