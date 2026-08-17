package com.choruskube.core.service;

/**
 * How an {@link EpicReadinessAssembler} assembly authorizes the cross-Epic blockers it has to
 * resolve. The distinction is entirely about what the caller can be checked <em>against</em>, and
 * it is not interchangeable: the public path reads a request-scoped tenant context, which only a
 * caller serving an HTTP request actually has.
 *
 * <p>Package-private, like the assembler itself — every caller lives in this package.
 */
enum ReadinessAuthMode {

    /** Request-scoped caller: cross-Epic blockers are checked with {@code checkOrgAccess}. */
    PUBLIC,

    /**
     * Agent / {@code /internal/**} JOB_SECRET caller: no tenant context, so blockers are checked
     * against the calling {@code workflow_run}'s own org.
     */
    INTERNAL_RUN,

    /**
     * Autopilot timer thread: no tenant context and no run either, so blockers are checked against
     * the {@code autopilot}'s own org — org derived from data rather than from a caller's token.
     */
    AUTOPILOT
}
