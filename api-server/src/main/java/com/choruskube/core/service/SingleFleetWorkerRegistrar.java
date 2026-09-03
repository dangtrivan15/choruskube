package com.choruskube.core.service;

import com.choruskube.core.dto.WorkerRegisterRequest;
import com.choruskube.core.dto.WorkerRegisterResponse;
import com.choruskube.core.exception.ForbiddenException;

/**
 * The default {@link WorkerRegistrar}: this server has exactly one Fleet, and it is the Temporal
 * namespace and task queue the server itself already uses.
 *
 * <p>That single-Fleet constraint is the whole class. Every Worker that presents the configured
 * token is told the same namespace and queue, so a Worker and a run can only ever meet in one
 * place — which is precisely why nothing here needs a Fleet table, provisioning, or per-Fleet
 * token minting. Serving more than one Fleet means replacing this bean, not extending it.
 *
 * <p>No Worker record is kept, so the response echoes the {@code instanceId} the Worker generated
 * rather than inventing an id nothing can later resolve. Revoking one Worker is therefore not
 * possible here; rotating {@code worker.registration.token} stops all of them.
 *
 * <p><b>Not a Spring bean.</b> {@code WorkerRegistrationController} holds it as the fallback
 * behind an {@code ObjectProvider}, the same arrangement as {@link ShufflingTickOrder}, so an
 * implementation replaces it by existing. {@code @ConditionalOnMissingBean} would be wrong: it is
 * only reliable inside auto-configuration, and here it would decide the Worker's fate on bean
 * scan order.
 */
public class SingleFleetWorkerRegistrar implements WorkerRegistrar {

    private final String temporalNamespace;
    private final String taskQueue;
    private final byte[] expectedTokenDigest;

    /**
     * @param registrationToken the shared secret every Worker must present. Blank configures no
     *     Fleet a Worker may join: registration then fails closed rather than making
     *     {@code /worker/register} anonymous on a server whose operator never set it.
     */
    public SingleFleetWorkerRegistrar(String temporalNamespace, String taskQueue, String registrationToken) {
        this.temporalNamespace = temporalNamespace;
        this.taskQueue = taskQueue;
        this.expectedTokenDigest =
                (registrationToken == null || registrationToken.isBlank()) ? null : TokenDigest.of(registrationToken);
    }

    @Override
    public WorkerRegisterResponse register(String fleetToken, WorkerRegisterRequest request) {
        requireConfiguredToken(fleetToken);
        // Blank Temporal token: an OSS Temporal runs without an authorizer, and the Worker must send
        // no credential at all rather than an empty one -- the SDK enables TLS on their mere presence.
        // Blank endpoint: the Worker already knows where Temporal is; there is only one.
        // Blank internal token: no Worker record is kept here, so there is nothing to mint against.
        return new WorkerRegisterResponse(request.instanceId(), temporalNamespace, taskQueue, "", 0L, "", "");
    }

    private void requireConfiguredToken(String presented) {
        byte[] expected = expectedTokenDigest;
        if (expected == null) {
            throw new ForbiddenException("Worker registration is not configured on this server");
        }
        if (!TokenDigest.matches(expected, presented)) {
            throw new ForbiddenException("Unknown or revoked fleet token");
        }
    }
}
