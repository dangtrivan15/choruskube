package com.choruskube.core.exception;

/**
 * The three GitHub response headers that say "you are being rate limited", parsed and kept — and
 * nothing else from the response.
 *
 * <p><strong>Why headers and never the body.</strong> GitHub answers a secondary rate limit with
 * <em>403</em>, the same status it uses for a credential that genuinely lacks access. Status alone
 * therefore cannot tell "wait" from "a human must act", and the difference matters: the first
 * clears itself in seconds, the second stops the Autopilot until somebody fixes a credential. The
 * distinguishing signal is only in the headers. The body would also carry it, and the body is
 * exactly what {@link GitHubApiException} refuses to keep — GitHub echoes the request, {@code
 * Authorization} header included, in some error payloads, and these values reach a log and a UI
 * panel. Three parsed integers cannot leak a token; a retained response cannot promise that.
 *
 * <p>This is a whitelist, not a header bag. Keeping the raw headers would put the request echo back
 * within reach of the next person to write a log line.
 *
 * @param retryAfterSeconds {@code retry-after} — GitHub's own "wait this long", sent on secondary
 *     rate limits. Null when absent or unparsable.
 * @param remaining {@code x-ratelimit-remaining} — zero means the primary quota is spent. Null when
 *     absent or unparsable.
 * @param resetEpochSeconds {@code x-ratelimit-reset} — when the primary quota refills. Carried for
 *     logs and diagnosis; deliberately not part of {@link #indicatesRateLimit()}, since a reset
 *     time is present on ordinary successful calls too.
 */
public record GitHubRateLimitHints(Integer retryAfterSeconds, Integer remaining, Long resetEpochSeconds) {

    /** Nothing was parsed — the caller learns only the status. */
    public static final GitHubRateLimitHints NONE = new GitHubRateLimitHints(null, null, null);

    /**
     * Whether the response positively says this was a rate limit.
     *
     * <p>Positive signal only, and that direction is the whole safety property. Absent, malformed
     * or unrecognised headers answer false, so a 403 stays persistent and still stops the Autopilot.
     * The worst case is failing to notice a rate limit — an Autopilot stopped by something that
     * would have cleared, which is where this started. The alternative direction would let a
     * genuinely revoked credential look transient and be retried forever in silence.
     */
    public boolean indicatesRateLimit() {
        return retryAfterSeconds != null || (remaining != null && remaining == 0);
    }
}
