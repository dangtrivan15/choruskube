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
 * @param maxAwaitingHuman the ceiling on runs parked on a human ({@code awaiting_human} +
 *     {@code paused}) that the start loop stops against; {@code 0} means unlimited
 * @param inFlight runs of this Autopilot occupying a slot — {@code pending} or {@code running}
 *     only. Parked runs cost nothing and are reported in {@code awaitingYou} instead.
 * @param slots how many more Tasks the next tick may start, {@code maxParallel - inFlight}
 * @param awaitingHuman how many runs currently occupy the {@code maxAwaitingHuman} ceiling —
 *     scope-wide occupancy through the same {@code AutopilotSlotCounter} seam as {@code inFlight},
 *     not this Autopilot's own attributed count like {@code awaitingYou} below (see
 *     {@code docs/decisions/2026-09-06---03-autopilot-parallelism-counts-scope-not-attribution.md})
 * @param nextUp the ordered ready frontier, capped — what a tick would start next
 * @param whyIdle human-readable reasons the Autopilot is not starting work, in the form
 *     {@code "Epic 'Billing' — no tasks defined"}.
 * @param awaitingYou runs parked on a human — {@code awaiting_human}, {@code paused}
 * @param needsAttention runs in {@code awaiting_retry}: failed, held for seven days, and never
 *     retried by the Autopilot
 * @param heldTasks Tasks left {@code in_progress} by a run that has finished. Nothing moves them:
 *     the ready frontier only considers {@code backlog} Tasks, so until a human re-opens one it is
 *     invisible to the Autopilot and blocks everything downstream of it. {@code runId}/{@code
 *     status} name the finished run, since which one it was decides whether re-opening or
 *     completing is the right answer.
 */
public record AutopilotStatusResponse(
        boolean engaged,
        int maxParallel,
        int maxAwaitingHuman,
        int inFlight,
        int slots,
        int awaitingHuman,
        List<AutopilotTaskRef> nextUp,
        List<String> whyIdle,
        List<AutopilotTaskRef> awaitingYou,
        List<AutopilotTaskRef> needsAttention,
        List<AutopilotTaskRef> heldTasks,
        int consecutiveFailures,
        String disengagedReason,
        Instant lastTickAt) {}
