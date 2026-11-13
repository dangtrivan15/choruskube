package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.dto.WorkerCredentialHealth;
import com.choruskube.core.dto.WorkerRegisterRequest;
import com.choruskube.core.dto.WorkerRegisterResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.model.CredentialHealthStatus;
import com.choruskube.core.model.CredentialKind;
import com.choruskube.core.model.WorkerCredentialSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SingleFleetWorkerRegistrarTest {

    private static final String TOKEN = "ckf_self_hosted_secret";

    private final SingleFleetWorkerRegistrar registrar = new SingleFleetWorkerRegistrar("ns-1", "queue-1", TOKEN);

    /** Every kind reported not held, the honest report for a Worker this test never gives one to. */
    private static List<WorkerCredentialHealth> notHeldHealth() {
        Instant now = Instant.now();
        return List.of(
                new WorkerCredentialHealth(CredentialKind.GITHUB, CredentialHealthStatus.NOT_HELD, null, now),
                new WorkerCredentialHealth(CredentialKind.CLAUDE, CredentialHealthStatus.NOT_HELD, null, now),
                new WorkerCredentialHealth(CredentialKind.REGISTRY, CredentialHealthStatus.NOT_HELD, null, now));
    }

    private static WorkerRegisterRequest request(String hostname) {
        return new WorkerRegisterRequest(
                hostname, UUID.randomUUID(), Map.of(), WorkerCredentialSource.LOCAL, notHeldHealth());
    }

    @Test
    void register_correctToken_returnsTheConfiguredNamespaceAndQueue() {
        WorkerRegisterResponse response = registrar.register(TOKEN, request("host-1"));

        assertThat(response.temporalNamespace()).isEqualTo("ns-1");
        assertThat(response.taskQueue()).isEqualTo("queue-1");
    }

    /**
     * The single-Fleet constraint itself: two Workers presenting the same token are told the same
     * place. A deployment that can answer differently per Worker is exactly what replacing this
     * bean buys.
     */
    @Test
    void register_twoDifferentWorkers_areSentToTheSameFleet() {
        WorkerRegisterResponse first = registrar.register(TOKEN, request("host-1"));
        WorkerRegisterResponse second = registrar.register(TOKEN, request("host-2"));

        assertThat(second.temporalNamespace()).isEqualTo(first.temporalNamespace());
        assertThat(second.taskQueue()).isEqualTo(first.taskQueue());
    }

    /**
     * A blank token is not an oversight: an OSS Temporal runs with no authorizer, and the Worker
     * must send no credential at all — the SDK enables TLS on the mere presence of one, whatever
     * it contains, and then fails its handshake against a plaintext frontend.
     */
    @Test
    void register_mintsNoCredentialAndPinsNoEndpoint() {
        WorkerRegisterResponse response = registrar.register(TOKEN, request("host-1"));

        assertThat(response.token()).isEmpty();
        assertThat(response.expiresInSeconds()).isZero();
        assertThat(response.endpoint()).isEmpty();
    }

    /** No Worker record exists to hand back an id from, so the Worker's own id is echoed. */
    @Test
    void register_echoesTheInstanceIdAsTheWorkerId() {
        WorkerRegisterRequest request = request("host-1");

        assertThat(registrar.register(TOKEN, request).workerId()).isEqualTo(request.instanceId());
    }

    @Test
    void register_unknownToken_isForbidden() {
        assertThatThrownBy(() -> registrar.register("ckf_not_the_secret", request("host-1")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("fleet token");
    }

    /**
     * A token that is a prefix of the configured one must not pass. Comparing fixed-width digests
     * rather than the tokens themselves is what makes length differences irrelevant here.
     */
    @Test
    void register_prefixOfTheConfiguredToken_isForbidden() {
        assertThatThrownBy(() -> registrar.register(TOKEN.substring(0, TOKEN.length() - 1), request("host-1")))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * Fails closed. An operator who never configured a token has a server that admits no Worker,
     * not one whose registration endpoint is anonymous.
     */
    @Test
    void register_whenNoTokenIsConfigured_refusesEveryWorker() {
        SingleFleetWorkerRegistrar unconfigured = new SingleFleetWorkerRegistrar("ns-1", "queue-1", "  ");

        assertThatThrownBy(() -> unconfigured.register("anything", request("host-1")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void register_nullToken_isForbiddenRatherThanNullPointer() {
        assertThatThrownBy(() -> registrar.register(null, request("host-1"))).isInstanceOf(ForbiddenException.class);
    }

    /**
     * A server that keeps no Worker records has nothing to store a minted credential against, so
     * it mints none. Blank means "keep the credential you already have", which for a Worker that
     * registered with a Fleet token is that token.
     */
    @Test
    void singleFleetRegistrationMintsNoInternalCredential() {
        WorkerRegisterResponse response = new SingleFleetWorkerRegistrar("ns", "queue", "tok")
                .register(
                        "tok",
                        new WorkerRegisterRequest(
                                "host", UUID.randomUUID(), Map.of(), WorkerCredentialSource.LOCAL, notHeldHealth()));

        assertThat(response.internalToken()).isEmpty();
    }

    /** This server holds no credential to broker, so a Worker that wants one is refused, not registered. */
    @Test
    void register_declaredControlPlane_isConflict() {
        WorkerRegisterRequest controlPlane = new WorkerRegisterRequest(
                "host-1", UUID.randomUUID(), Map.of(), WorkerCredentialSource.CONTROL_PLANE, notHeldHealth());

        assertThatThrownBy(() -> registrar.register(TOKEN, controlPlane))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("must hold its own credentials");
    }

    @Test
    void register_declaredLocal_isAccepted() {
        WorkerRegisterRequest local = new WorkerRegisterRequest(
                "host-1", UUID.randomUUID(), Map.of(), WorkerCredentialSource.LOCAL, notHeldHealth());

        WorkerRegisterResponse response = registrar.register(TOKEN, local);

        assertThat(response.temporalNamespace()).isEqualTo("ns-1");
        assertThat(response.taskQueue()).isEqualTo("queue-1");
    }

    /** The token check happens before the credential-source check, so a bad token is what surfaces first. */
    @Test
    void register_declaredControlPlane_withWrongToken_isForbiddenNotConflict() {
        WorkerRegisterRequest controlPlane = new WorkerRegisterRequest(
                "host-1", UUID.randomUUID(), Map.of(), WorkerCredentialSource.CONTROL_PLANE, notHeldHealth());

        assertThatThrownBy(() -> registrar.register("ckf_not_the_secret", controlPlane))
                .isInstanceOf(ForbiddenException.class);
    }
}
