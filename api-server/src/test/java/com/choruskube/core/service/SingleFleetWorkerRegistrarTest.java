package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.dto.WorkerRegisterRequest;
import com.choruskube.core.dto.WorkerRegisterResponse;
import com.choruskube.core.exception.ForbiddenException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SingleFleetWorkerRegistrarTest {

    private static final String TOKEN = "ckf_self_hosted_secret";

    private final SingleFleetWorkerRegistrar registrar = new SingleFleetWorkerRegistrar("ns-1", "queue-1", TOKEN);

    private static WorkerRegisterRequest request(String hostname) {
        return new WorkerRegisterRequest(hostname, UUID.randomUUID(), Map.of());
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
}
