package com.example.evidencechain.domain;

/**
 * Types of evidence events. Every business event that moves a plan's state
 * produces exactly one evidence event inside the same database transaction.
 * ANCHOR events are produced only by historical-data initialization.
 */
public enum EventType {
    CREATE,
    ANCHOR,
    START,
    CONFIRMATION,
    MOCK_RECEIPT,
    REJECTION,
    WRITEOFF
}
