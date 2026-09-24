package com.example.evidencechain.service;

/**
 * Thrown when a command cannot be applied from the plan's current state.
 * No state change and no evidence event is produced (transaction rolls back).
 */
public class IllegalTransitionException extends RuntimeException {

    public IllegalTransitionException(String message) {
        super(message);
    }
}
