package com.choruskube.core.exception;

/**
 * A failure that came back from GitHub with enough of the response to say whether it was a rate
 * limit.
 *
 * <p>An interface rather than two {@code instanceof} checks at the one call site, because the two
 * implementors are reached by different routes and only one of them is obvious.
 * {@link GitHubApiException} comes straight out of the pull-request read.
 * {@link GitHubTokenMintException} arrives buried in a cause chain — the credential resolver is a
 * seam, so a failure minting an installation token surfaces as
 * {@link GitHubCredentialUnavailableException} wrapping whatever the implementation threw. A
 * classifier that walks the chain looking for one interface finds both; one that enumerates classes
 * silently misses whichever was added last.
 */
public interface GitHubRateLimited {

    GitHubRateLimitHints getRateLimitHints();
}
