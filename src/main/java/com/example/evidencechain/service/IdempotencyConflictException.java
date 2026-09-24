package com.example.evidencechain.service;

/**
 * Same idempotency key was reused for two different commands on one plan.
 */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
