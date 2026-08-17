package com.choruskube.core.exception;

/**
 * Thrown when the GitHub API answers with a status the caller did not ask for. The status is a
 * field rather than only a fragment of the message, because {@code PullRequestStateService} has to
 * tell a revoked credential or a deleted repository (401/403/404) apart from a rate limit or an
 * outage (429/5xx): the first kind needs a human and disengages the Autopilot, the second is simply
 * retried on the next tick. Interpolating the status into a message and parsing it back out is not
 * a classification a compiler can check.
 *
 * <p><strong>The message never carries the response body.</strong> GitHub echoes parts of the
 * request in some error payloads, the {@code Authorization} header among them, and this message
 * travels to the log and — through the Autopilot's {@code disengagedReason} — to a UI panel. The
 * status, the {@code owner/repo} slug and the pull request number are all a human needs to act, and
 * none of them is a secret.
 *
 * <p>No {@code GlobalExceptionHandler} mapping, deliberately: nothing throws this on a request
 * thread. It is raised inside a scheduled reconciler that handles it, and the handler's generic
 * {@code RuntimeException} → 500 is the right answer for the case where one ever escapes to a
 * controller.
 */
public class GitHubApiException extends RuntimeException implements GitHubRateLimited {

    private final int status;
    private final String ownerRepo;
    private final int prNumber;
    private final GitHubRateLimitHints rateLimitHints;

    /**
     * For a response whose headers were not read, and for the many tests that only care about the
     * status. Equivalent to supplying {@link GitHubRateLimitHints#NONE}, which classifies as "not a
     * rate limit" — the safe direction, since a 403 then keeps its old persistent meaning.
     */
    public GitHubApiException(int status, String ownerRepo, int prNumber) {
        this(status, ownerRepo, prNumber, GitHubRateLimitHints.NONE);
    }

    public GitHubApiException(int status, String ownerRepo, int prNumber, GitHubRateLimitHints rateLimitHints) {
        super("GitHub returned " + status + " for " + ownerRepo + "#" + prNumber);
        this.status = status;
        this.ownerRepo = ownerRepo;
        this.prNumber = prNumber;
        this.rateLimitHints = rateLimitHints == null ? GitHubRateLimitHints.NONE : rateLimitHints;
    }

    @Override
    public GitHubRateLimitHints getRateLimitHints() {
        return rateLimitHints;
    }

    public int getStatus() {
        return status;
    }

    public String getOwnerRepo() {
        return ownerRepo;
    }

    public int getPrNumber() {
        return prNumber;
    }
}
