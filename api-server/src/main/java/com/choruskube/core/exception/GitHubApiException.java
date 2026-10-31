package com.choruskube.core.exception;

/**
 * Thrown when the GitHub API answers with a status the caller did not ask for. The status is a
 * field rather than only a fragment of the message, because callers have to tell a revoked
 * credential or a deleted repository (401/403/404) apart from a rate limit or an outage (429/5xx):
 * the first kind needs a human, the second is worth retrying. Interpolating the status into a
 * message and parsing it back out is not a classification a compiler can check.
 *
 * <p><strong>The message never carries the response body.</strong> GitHub echoes parts of the
 * request in some error payloads, the {@code Authorization} header among them, and this message
 * travels to the log and to a UI panel. The status and the {@code owner/repo} slug are all a human
 * needs to act, and neither is a secret.
 *
 * <p>No {@code GlobalExceptionHandler} mapping, deliberately. This stays an unmapped
 * {@code RuntimeException} → 500 for the control plane's remaining callers (the token mint,
 * {@code createIssue}, {@code compareCommits}, {@code deleteRef}), none of which reach a controller
 * without classifying the status themselves first.
 */
public class GitHubApiException extends RuntimeException implements GitHubRateLimited {

    private final int status;
    private final String ownerRepo;
    private final GitHubRateLimitHints rateLimitHints;

    public GitHubApiException(int status, String ownerRepo, GitHubRateLimitHints rateLimitHints) {
        super("GitHub returned " + status + " for " + ownerRepo);
        this.status = status;
        this.ownerRepo = ownerRepo;
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
}
