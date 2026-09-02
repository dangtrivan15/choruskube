package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The fallback for "this deployment has no placement policy" lives here and nowhere else:
 * an absent bean means the configured namespace and queue. A resolver that exists always
 * answers for real, which is why the interface has no default methods.
 */
class RunPlacementServiceTest {

    private static final String NS = "choruskube";
    private static final String QUEUE = "choruskube";

    @Test
    void placeFor_noResolver_usesTheConfiguredNamespaceAndQueue() {
        RunPlacementService service = new RunPlacementService(Optional.empty(), NS, QUEUE);

        assertThat(service.placeFor(UUID.randomUUID())).isEqualTo(new RunPlacement(NS, QUEUE));
    }

    @Test
    void placeFor_resolverPresent_returnsItsAnswer() {
        UUID runId = UUID.randomUUID();
        RunPlacementResolver resolver = mock(RunPlacementResolver.class);
        when(resolver.placeFor(runId)).thenReturn(new RunPlacement("tenant-ns", "fleet-x"));

        RunPlacementService service = new RunPlacementService(Optional.of(resolver), NS, QUEUE);

        assertThat(service.placeFor(runId)).isEqualTo(new RunPlacement("tenant-ns", "fleet-x"));
    }

    @Test
    void namespaces_noResolver_isTheConfiguredNamespaceAlone() {
        RunPlacementService service = new RunPlacementService(Optional.empty(), NS, QUEUE);

        assertThat(service.namespaces()).containsExactly(NS);
    }

    @Test
    void namespaces_alwaysIncludesTheConfiguredNamespace() {
        RunPlacementResolver resolver = mock(RunPlacementResolver.class);
        when(resolver.namespaces()).thenReturn(Set.of("tenant-ns"));

        RunPlacementService service = new RunPlacementService(Optional.of(resolver), NS, QUEUE);

        // A roster that omitted it would stop the orchestrator serving runs already in flight
        // there, which no run of a customer Fleet can compensate for.
        assertThat(service.namespaces()).containsExactlyInAnyOrder(NS, "tenant-ns");
    }

    @Test
    void runPlacement_blankNamespace_isRejected() {
        assertThatThrownBy(() -> new RunPlacement("  ", QUEUE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runPlacement_blankTaskQueue_isRejected() {
        assertThatThrownBy(() -> new RunPlacement(NS, "")).isInstanceOf(IllegalArgumentException.class);
    }
}
