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
 * <p>Not a {@link GitHubApiException}: that one names an {@code owner/repo} and a pull request
 * number in its message because a human needs both to act, and neither exists for a token mint.
 * Inventing placeholder values to reuse the type would put fiction in an operator-facing string.
 * The shared part — was this a rate limit? — is the {@link GitHubRateLimited} interface, which is
 * all the classifier looks for.
 *
 * <p><strong>The message carries the installation id and nothing else</strong>, for the same reason
 * {@link GitHubApiException} omits the body: an error payload from GitHub can echo the request,
 * {@code Authorization} header included, and this text reaches a log and the Autopilot's {@code
 * disengagedReason}.
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
