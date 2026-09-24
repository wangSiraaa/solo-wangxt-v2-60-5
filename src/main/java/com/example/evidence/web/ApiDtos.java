package com.example.evidence.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request/response views for the evidence-chain REST API. */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record CreatePlanRequest(String requestId, String planNo, String title) {
    }

    public record TransitionRequest(String requestId, String action, Map<String, Object> payload) {
    }

    public record PlanResponse(long id, String planNo, String title, String status,
                               String source, Long legacyRef, Instant createdAt, Instant updatedAt) {
    }

    public record EventResponse(long seq, String eventType, String stateBefore, String stateAfter,
                                Map<String, Object> payload, String requestId,
                                String prevHash, String eventHash, Instant createdAt,
                                boolean genesis) {
    }

    public record OperationResponse(boolean replayed, PlanResponse plan, EventResponse event) {
    }

    public record PlanChainResponse(PlanResponse plan, List<EventResponse> events) {
    }

    public record VerifyResponse(long planId, boolean valid, Long firstBrokenSeq,
                                 String reasonCode, String detail, List<String> verifiedEventHashes) {
    }

    public record VerifyAllResponse(boolean allValid, int planCount, List<VerifyResponse> results) {
    }

    public record AnchorItemResponse(long legacyId, long planId, String status, String detail) {
    }

    public record AnchorResponse(long totalLegacy, long anchored, long skipped, long failed,
                                 List<AnchorItemResponse> items) {
    }

    public record LegacySeedRequest(long id, String planNo, String title, String status,
                                    Instant createdAt, Instant updatedAt, String extra) {
    }

    public record ErrorResponse(String code, String message) {
    }
}
