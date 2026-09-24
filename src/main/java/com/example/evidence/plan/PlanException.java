package com.example.evidence.plan;

/** Client-facing validation / conflict errors. Mapped to HTTP status in the web layer. */
public class PlanException extends RuntimeException {

    private final String code;
    private final int httpStatus;

    public PlanException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static PlanException notFound(String message) {
        return new PlanException("NOT_FOUND", message, 404);
    }

    public static PlanException invalidTransition(String message) {
        return new PlanException("INVALID_TRANSITION", message, 409);
    }

    public static PlanException idempotencyConflict(String message) {
        return new PlanException("IDEMPOTENCY_CONFLICT", message, 409);
    }

    public static PlanException invalidRequest(String message) {
        return new PlanException("INVALID_REQUEST", message, 400);
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
