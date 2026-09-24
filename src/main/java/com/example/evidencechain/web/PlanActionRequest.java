package com.example.evidencechain.web;

import jakarta.validation.constraints.NotBlank;

/**
 * Unified command request. {@code action} is one of:
 * START, CONFIRMATION, MOCK_RECEIPT, REJECTION, WRITEOFF.
 * Detail fields are used per action:
 * <ul>
 *   <li>CONFIRMATION/WRITEOFF: remark</li>
 *   <li>MOCK_RECEIPT: channel, receiptPayload</li>
 *   <li>REJECTION: reason</li>
 * </ul>
 */
public class PlanActionRequest {

    @NotBlank
    private String action;

    @NotBlank
    private String operator;

    private String remark;
    private String channel;
    private String receiptPayload;
    private String reason;

    @NotBlank
    private String idempotencyKey;

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getReceiptPayload() {
        return receiptPayload;
    }

    public void setReceiptPayload(String receiptPayload) {
        this.receiptPayload = receiptPayload;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
