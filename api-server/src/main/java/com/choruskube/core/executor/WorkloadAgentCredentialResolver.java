package com.choruskube.core.executor;

import java.util.UUID;

/**
 * Attaches the agent credentials (Claude token, registry login) that a deployment brokers for a
 * run's workload. A deployment that brokers credentials for only some of where it runs decides,
 * per run, from where the run executes.
 *
 * <p>A Worker that holds its own credentials must receive none from here — see {@code
 * docs/decisions/2026-10-13---02-data-plane-credential-rule.md}.
 *
 * <p>The default is {@link NoWorkloadAgentCredentialResolver}.
 */
public interface WorkloadAgentCredentialResolver {

    /**
     * @param runId the run whose workload is being prepared
     * @param runsClaude whether the node being launched runs Claude
     * @return the credentials to attach, or {@link WorkloadAgentCredentials#NONE} when this
     *     deployment has nothing to attach — never {@code null}. Must not return a Claude token
     *     when {@code runsClaude} is false. May throw, and the throw fails the preparation rather
     *     than degrading to no credentials.
     */
    WorkloadAgentCredentials resolve(UUID runId, boolean runsClaude);
}
