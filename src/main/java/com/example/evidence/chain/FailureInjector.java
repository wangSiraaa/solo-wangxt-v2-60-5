package com.example.evidence.chain;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only fault injection point. In production every method is a no-op
 * (nothing ever arms the trigger). Acceptance tests arm a one-shot trigger for
 * a precise point of a state-changing transaction to prove there is never a
 * half-written record ("state without evidence" or "evidence without state").
 */
@Component
public class FailureInjector {

    public enum Point {
        /** Fires after the evidence event row has been inserted, before the status update. */
        AFTER_EVIDENCE_INSERT,
        /** Fires after the status update, before commit. */
        AFTER_STATE_UPDATE
    }

    private final AtomicReference<Point> armed = new AtomicReference<>();

    /** Arm a single failure; consumed by the next matching injection point. */
    public void arm(Point point) {
        armed.set(point);
    }

    public void reset() {
        armed.set(null);
    }

    public void afterEvidenceInsert(long planId) {
        if (armed.get() == Point.AFTER_EVIDENCE_INSERT) {
            armed.set(null);
            throw new InjectedFailureException(
                    "injected failure after evidence insert for plan " + planId);
        }
    }

    public void afterStateUpdate(long planId) {
        if (armed.get() == Point.AFTER_STATE_UPDATE) {
            armed.set(null);
            throw new InjectedFailureException(
                    "injected failure after state update for plan " + planId);
        }
    }

    public static class InjectedFailureException extends RuntimeException {
        public InjectedFailureException(String message) {
            super(message);
        }
    }
}
