package com.choruskube.core.exception;

/**
 * GitHub refused to mint an installation token, with a status.
 *
 * <p>Exists because minting is the <em>other</em> way a rate limit reaches this system, and it used
 * to be the blind one. A bare {@code RuntimeException} carrying an interpolated status could not be
 * told apart from a private key that will not parse, so every failure to produce a credential was
 * classified persistent and stopped the Autopilot — including a 403 secondary rate limit, which is
 * precisely what a busy installation hits, and precisely what would have cleared on its own. Fixing
 * only the pull-request read would have left this half in place, and this half fires at least as
 * often.
 *
 * <p>Whether this was a rate limit is answered by the {@link GitHubRateLimited} interface, which is
 * all a classifier looks for.
 *
 * <p><strong>The message carries the installation id and nothing else</strong>, never the response
 * body: an error payload from GitHub can echo the request, {@code Authorization} header included,
 * and this text reaches a log and the Autopilot's {@code disengagedReason}.
 */
public class GitHubTokenMintException extends RuntimeException implements GitHubRateLimited {

    private final int status;
    private final GitHubRateLimitHints rateLimitHints;

    public GitHubTokenMintException(int status, String installationId, GitHubRateLimitHints rateLimitHints) {
        super("GitHub returned " + status + " minting an installation token for installation " + installationId);
        this.status = status;
        this.rateLimitHints = rateLimitHints == null ? GitHubRateLimitHints.NONE : rateLimitHints;
    }

    public int getStatus() {
        return status;
    }

    @Override
    public GitHubRateLimitHints getRateLimitHints() {
        return rateLimitHints;
    }
}
