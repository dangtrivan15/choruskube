package com.choruskube.core.service;

import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.model.WorkerCredentialSource;
import com.choruskube.core.repository.SingleFleetCredentialSourceRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Resolves the one question a Worker's own application calls need answered beyond authorization:
 * does this credential's Worker hold its own GitHub, Claude and registry credentials, or does it
 * ask the API server to broker them?
 *
 * <p>Delegates to a {@link WorkerCredentialSourceResolver}, resolved once at construction — a
 * deployment-supplied bean if one exists, otherwise {@link SingleFleetWorkerCredentialSourceResolver}.
 */
@Service
public class WorkerCredentialSources {

    private final WorkerCredentialSourceResolver resolver;

    public WorkerCredentialSources(
            ObjectProvider<WorkerCredentialSourceResolver> resolverProvider,
            SingleFleetCredentialSourceRepository repository,
            @Value("${worker.registration.token:}") String registrationToken) {
        this.resolver = resolverProvider.getIfAvailable(
                () -> new SingleFleetWorkerCredentialSourceResolver(registrationToken, repository));
    }

    public WorkerCredentialSource sourceOf(String credential) {
        // Defensive in the same style as the rest of this package: a blank credential never
        // reaches the fallback's own check, so every resolver sees the same failure for it.
        if (credential == null || credential.isBlank()) {
            throw new ForbiddenException("Unknown or revoked worker credential");
        }
        return resolver.sourceOf(credential);
    }

    public boolean holdsOwnCredentials(String credential) {
        return sourceOf(credential) == WorkerCredentialSource.LOCAL;
    }
}
