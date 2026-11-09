package com.choruskube.core.exception;

/**
 * A failure that came back from GitHub with enough of the response to say whether it was a rate
 * limit.
 *
 * <p>An interface rather than an {@code instanceof} check, because an implementor can arrive buried
 * in a cause chain: the credential resolver is a seam, so a {@link GitHubTokenMintException} from
 * minting an installation token surfaces wrapped in whatever the implementation threw. A classifier
 * that walks the chain looking for one interface finds every implementor; one that enumerates
 * classes silently misses whichever was added last.
 */
public interface GitHubRateLimited {

    GitHubRateLimitHints getRateLimitHints();
}
