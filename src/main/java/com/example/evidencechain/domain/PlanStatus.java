package com.example.evidencechain.domain;

/**
 * Lifecycle states of a plan.
 *
 * <pre>
 * DRAFT -> STARTED -> CONFIRMED -> RECEIPTED -> WRITTEN_OFF
 *                        |             |
 *                        +------+------+
 *                               v
 *                            REJECTED
 * </pre>
 */
public enum PlanStatus {
    DRAFT,
    STARTED,
    CONFIRMED,
    RECEIPTED,
    REJECTED,
    WRITTEN_OFF
}
