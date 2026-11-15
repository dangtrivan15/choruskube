package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepoDependencyGraphTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");

    @Test
    void noEdges_noCycle() {
        assertThat(RepoDependencyGraph.findCycle(Map.of())).isEmpty();
    }

    @Test
    void chain_noCycle() {
        assertThat(RepoDependencyGraph.findCycle(Map.of(A, Set.of(B), B, Set.of(C))))
                .isEmpty();
    }

    @Test
    void twoCycle_returnsPath() {
        assertThat(RepoDependencyGraph.findCycle(Map.of(A, Set.of(B), B, Set.of(A))))
                .contains(List.of(A, B, A));
    }

    @Test
    void longCycle_found() {
        assertThat(RepoDependencyGraph.findCycle(Map.of(A, Set.of(B), B, Set.of(C), C, Set.of(A))))
                .contains(List.of(A, B, C, A));
    }

    @Test
    void diamond_noCycle() {
        assertThat(RepoDependencyGraph.findCycle(Map.of(A, Set.of(B, C), B, Set.of(D), C, Set.of(D))))
                .isEmpty();
    }
}
