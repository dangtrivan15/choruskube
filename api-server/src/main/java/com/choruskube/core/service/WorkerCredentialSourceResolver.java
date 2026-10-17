package com.choruskube.core.service;

import com.choruskube.core.model.WorkerCredentialSource;

/**
 * Answers which credential mode the Worker behind a given credential declared at registration.
 *
 * <p>Callers must only invoke {@link #sourceOf} after {@link WorkerAuthorizer#requireMayActOn}
 * has already accepted the credential — this method only answers the mode, it does not itself
 * authenticate. The default is {@link SingleFleetWorkerCredentialSourceResolver}, this server's
 * single-Fleet fallback; a deployment that mints a credential per Worker supplies its own
 * implementation as a bean, replacing that default the same way {@link WorkerRegistrar} and
 * {@link WorkerAuthorizer} are replaced.
 */
public interface WorkerCredentialSourceResolver {

    /**
     * @param workerCredential the bearer token the Worker presented
     * @throws com.choruskube.core.exception.ForbiddenException if the credential is unknown or
     *     blank
     */
    WorkerCredentialSource sourceOf(String workerCredential);
}
