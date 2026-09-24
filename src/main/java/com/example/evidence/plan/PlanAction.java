package com.example.evidence.plan;

import com.example.evidence.domain.EventType;
import com.example.evidence.domain.PlanStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Allowed maintenance-plan actions and their state-machine semantics. */
public enum PlanAction {

    START("STARTED", PlanStatus.DRAFT, PlanStatus.ISSUED),
    CONFIRM("CONFIRMED", PlanStatus.ISSUED, PlanStatus.CONFIRMED),
    RECEIPT("RECEIPTED", PlanStatus.CONFIRMED, PlanStatus.RECEIPTED),
    REJECT("REJECTED", PlanStatus.CONFIRMED, PlanStatus.REJECTED),
    WRITE_OFF("WRITTEN_OFF", PlanStatus.RECEIPTED, PlanStatus.WRITTEN_OFF);

    private static final Map<PlanStatus, Set<PlanAction>> ALLOWED = new EnumMap<>(PlanStatus.class);

    static {
        for (PlanAction action : values()) {
            ALLOWED.computeIfAbsent(action.from, s -> EnumSet.noneOf(PlanAction.class)).add(action);
        }
    }

    private final String eventTypeName;
    private final PlanStatus from;
    private final PlanStatus to;

    PlanAction(String eventTypeName, PlanStatus from, PlanStatus to) {
        this.eventTypeName = eventTypeName;
        this.from = from;
        this.to = to;
    }

    public EventType eventType() {
        return EventType.valueOf(eventTypeName);
    }

    public PlanStatus from() {
        return from;
    }

    public PlanStatus to() {
        return to;
    }

    public static Optional<PlanAction> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean isAllowedFrom(PlanStatus current) {
        return ALLOWED.getOrDefault(current, Set.of()).contains(this);
    }
}
