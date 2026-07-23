package com.choruskube.core.model.enums;

/**
 * Computed (never persisted) dependency-readiness signal for a Story/Task node in the Roadmap
 * Graph View response (Decision 2). Derived at read time from that item's DIRECT incoming
 * "blocking" edges ({@code work_item_dependency}) and the current status of each blocker: {@code
 * BLOCKED} if any direct blocker is not yet {@code done}, {@code READY} otherwise (including items
 * with no incoming blocking edges at all).
 */
public enum Readiness {
    READY,
    BLOCKED
}
