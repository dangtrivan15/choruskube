package com.choruskube.core.model.enums;

/**
 * Computed (never persisted) dependency-readiness signal for a Story/Task node in the Roadmap
 * Graph View response. Derived at read time by walking the full chain of incoming "blocking" edges
 * ({@code work_item_dependency}) backward from the item, not just its direct blocker(s): {@code
 * BLOCKED} if any item reachable that way is not yet {@code done} — even when the item's own
 * direct blocker has itself been marked done but something further upstream in the chain has not —
 * {@code READY} otherwise (including items with no incoming blocking edges at all). The walk is
 * bounded to the requesting Epic's own Story/Task set: a blocker that lives in a different Epic
 * still gates readiness at that hop via its own status, but its own further upstream chain (in the
 * other Epic) is not followed. See {@code TransitiveReadinessResolver}.
 */
public enum Readiness {
    READY,
    BLOCKED
}
