package com.choruskube.core.exception;

/**
 * Thrown when GitHub refuses to merge a pull request (405, 409 or 422) rather than failing the
 * request outright. {@link #getReason()} is GitHub's {@code message} field only — capped and with
 * every occurrence of the token scrubbed — because the merge service wraps this into the
 * user-facing {@link PullRequestMergeException}, and nothing else in this exception's own message
 * is safe to show a reviewer.
 */
public class GitHubMergeRefusedException extends GitHubApiException {

    private final String reason;

    public GitHubMergeRefusedException(
            int status, String ownerRepo, int prNumber, String reason, GitHubRateLimitHints rateLimitHints) {
        super(status, ownerRepo, prNumber, rateLimitHints);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
