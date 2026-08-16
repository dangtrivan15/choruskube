package com.choruskube.core.exception;

/**
 * Thrown when no usable GitHub credential can be produced for a repository — nothing configured, a
 * private key that cannot be read, an installation token that cannot be minted. Distinct from
 * {@link GitHubApiException} because there was no API call to get a status from, and a caller that
 * has to decide between "stop automating" and "try again in two minutes" cannot make that decision
 * from a bare {@link IllegalStateException} that a dozen unrelated things also throw.
 *
 * <p>Raised by the caller of {@link com.choruskube.core.credential.GitHubCredentialResolver}, at
 * that one call site, rather than by the resolver itself: the resolver is an OSS seam with
 * implementations outside this repository, so narrowing at the point of use classifies every
 * implementation's failure without changing any of their contracts.
 *
 * <p><strong>The message carries the repository and nothing else.</strong> The cause is chained for
 * a debugger, but its own text is never interpolated here — a credential resolver's failure can
 * quote whatever the identity provider said, and this message reaches the Autopilot's {@code
 * disengagedReason} and therefore a UI panel.
 */
public class GitHubCredentialUnavailableException extends RuntimeException {

    private final String ownerRepo;

    public GitHubCredentialUnavailableException(String ownerRepo, Throwable cause) {
        super("Could not resolve a GitHub credential for " + ownerRepo, cause);
        this.ownerRepo = ownerRepo;
    }

    public String getOwnerRepo() {
        return ownerRepo;
    }
}
