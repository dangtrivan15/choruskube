package com.choruskube.core.service;

import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.model.SingleFleetCredentialSource;
import com.choruskube.core.model.WorkerCredentialSource;
import com.choruskube.core.repository.SingleFleetCredentialSourceRepository;

/**
 * The default {@link WorkerCredentialSourceResolver}: this server has exactly one Fleet, so the
 * credential's mode is whatever was last declared against the one shared registration token.
 *
 * <p><b>Not a Spring bean.</b> Held as the fallback behind an {@code ObjectProvider} by {@link
 * WorkerCredentialSources}, the same arrangement as {@link SingleFleetWorkerRegistrar} and {@link
 * SingleFleetWorkerAuthorizer}.
 */
public class SingleFleetWorkerCredentialSourceResolver implements WorkerCredentialSourceResolver {

    private final byte[] expectedTokenDigest;
    private final SingleFleetCredentialSourceRepository repository;

    public SingleFleetWorkerCredentialSourceResolver(
            String registrationToken, SingleFleetCredentialSourceRepository repository) {
        this.expectedTokenDigest =
                (registrationToken == null || registrationToken.isBlank()) ? null : TokenDigest.of(registrationToken);
        this.repository = repository;
    }

    @Override
    public WorkerCredentialSource sourceOf(String credential) {
        byte[] expected = expectedTokenDigest;
        if (expected == null || !TokenDigest.matches(expected, credential)) {
            throw new ForbiddenException("Unknown or revoked worker credential");
        }
        // A fresh deployment, or one where nothing has registered against this token yet, has no
        // row to answer from. That is a different "nothing known" than the DTO's absent-field
        // rejection: here the credential is valid, only its declared mode is unrecorded.
        return repository
                .findById(TokenDigest.hex(credential))
                .map(SingleFleetCredentialSource::getCredentialSource)
                .orElse(WorkerCredentialSource.CONTROL_PLANE);
    }
}
