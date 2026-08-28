package com.choruskube.core.dto;

import java.time.Instant;
import java.util.List;

/**
 * Returned by every endpoint on {@code /api/v1/autopilot} and published as the STOMP payload on
 * every change, so a subscriber renders the panel from the event alone rather than refetching
 * into a race with the transaction that produced it.
 *
 * <p>Returned with {@code engaged = false} and {@code maxParallel = 1} when no {@code autopilot}
 * row exists at all: absence means "never configured", and the read path must not insert one.
 *
 * @param inFlight runs of this Autopilot occupying a slot — {@code pending} or {@code running}
 *     only. Parked runs cost nothing and are reported in {@code awaitingYou} instead.
 * @param slots how many more Tasks the next tick may start, {@code maxParallel - inFlight}
 * @param nextUp the ordered ready frontier, capped — what a tick would start next
 * @param whyIdle human-readable reasons the Autopilot is not starting work, in the form
 *     {@code "Epic 'Billing' — no tasks defined"}.
 * @param awaitingYou runs parked on a human — {@code awaiting_human}, {@code live_chat},
 *     {@code paused}
 * @param needsAttention runs in {@code awaiting_retry}: failed, held for seven days, and never
 *     retried by the Autopilot
 */
public record AutopilotStatusResponse(
        boolean engaged,
        int maxParallel,
        int inFlight,
        int slots,
        List<AutopilotTaskRef> nextUp,
        List<String> whyIdle,
        List<AutopilotTaskRef> awaitingYou,
        List<AutopilotTaskRef> needsAttention,
        int consecutiveFailures,
        String disengagedReason,
        Instant lastTickAt) {}
