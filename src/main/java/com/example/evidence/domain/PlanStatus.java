package com.example.evidence.domain;

/**
 * Lifecycle states of a maintenance plan.
 *
 * <pre>
 *   DRAFT --start--> ISSUED --confirm--> CONFIRMED --receipt--> RECEIPTED --writeOff--> WRITTEN_OFF
 *                                            \--reject--> REJECTED
 *   Legacy plans may be anchored in any of these states (including terminals) via ANCHOR events.
 * </pre>
 */
public enum PlanStatus {
    DRAFT,
    ISSUED,
    CONFIRMED,
    RECEIPTED,
    REJECTED,
    WRITTEN_OFF;

    public boolean isTerminal() {
        return this == REJECTED || this == WRITTEN_OFF;
    }
}
