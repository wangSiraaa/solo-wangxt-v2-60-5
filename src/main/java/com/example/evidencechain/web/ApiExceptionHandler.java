package com.example.evidencechain.web;

import com.example.evidencechain.service.IdempotencyConflictException;
import com.example.evidencechain.service.IllegalTransitionException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps domain failures to HTTP codes. Crucially, none of these handlers
 * commit anything: the service transaction is already rolled back by the
 * time the handler runs, so a rejected request leaves neither a state
 * change nor an evidence event behind.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<Map<String, Object>> illegalTransition(IllegalTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("ILLEGAL_TRANSITION", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> idempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body("IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<Map<String, Object>> duplicateKey(DuplicateKeyException ex) {
        // Unique-constraint races: plan_no or (plan_id, idempotency_key).
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(body("DUPLICATE_KEY", "The request violates a uniqueness constraint: "
                        + rootMessage(ex)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(body("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        // Last-resort mapping for programmer errors (e.g. unknown enum action).
        return ResponseEntity.internalServerError()
                .body(body("INTERNAL_ERROR", ex.getMessage() == null ? ex.getClass().getSimpleName()
                        : ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fields.put(error.getField(), error.getDefaultMessage()));
        Map<String, Object> body = body("VALIDATION_FAILED", "Request validation failed");
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    private Map<String, Object> body(String code, String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("errorCode", code);
        map.put("message", message);
        return map;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
