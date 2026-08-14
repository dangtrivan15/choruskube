package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GraphValidationServiceTest {

    private final GraphValidationService service = new GraphValidationService(new ObjectMapper());

    @Test
    void validLinearGraph() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var nodes = List.of(makeNode(nodeA, "A", true), makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void noEntrypointDefined() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var nodes = List.of(makeNode(nodeA, "A", false), makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("entrypoint"));
    }

    @Test
    void multipleEntrypoints() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();

        var nodes = List.of(makeNode(nodeA, "A", true), makeNode(nodeB, "B", true), makeNode(nodeC, "C", false));
        var edges = List.of(makeEdge(nodeA, nodeC, null), makeEdge(nodeB, nodeC, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Multiple entrypoint"));
    }

    @Test
    void unreachableNode() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();

        var nodes = List.of(makeNode(nodeA, "A", true), makeNode(nodeB, "B", false), makeNode(nodeC, "C", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null), makeEdge(nodeC, nodeC, "retry"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("C") && e.contains("terminal"));
    }

    @Test
    void cycleWithExit() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();

        var nodes = List.of(makeNode(nodeA, "A", true), makeNode(nodeB, "B", false), makeNode(nodeC, "C", false));
        var edges = List.of(
                makeEdge(nodeA, nodeB, null), makeEdge(nodeB, nodeC, "approved"), makeEdge(nodeB, nodeA, "rejected"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void pureCycleNoTerminal() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var nodes = List.of(makeNode(nodeA, "A", true), makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null), makeEdge(nodeB, nodeA, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("terminal"));
    }

    @Test
    void cycleWithTerminalDecisionsIsValid() {
        // Mirrors the Roadmap Provisioner v13 shape (Decision 2): analyzer -> gate,
        // gate --rejected--> analyzer (back-edge), gate has no outgoing "approved" edge —
        // instead config_overrides.terminal_decisions declares "approved" ends the run
        // right there. Without Rule 3 recognizing terminal_decisions, this graph has zero
        // nodes with no outgoing edges and fails "No terminal node found" even though the
        // graph is a legitimate, completable pipeline.
        UUID analyzer = UUID.randomUUID();
        UUID gate = UUID.randomUUID();

        var gateNode = makeNode(gate, "gate", false);
        gateNode.setConfigOverrides("{\"terminal_decisions\":[\"approved\"]}");
        var nodes = List.of(makeNode(analyzer, "analyzer", true), gateNode);
        var edges = List.of(makeEdge(analyzer, gate, null), makeEdge(gate, analyzer, "rejected"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void emptyTerminalDecisionsDoesNotCountAsTerminal() {
        UUID analyzer = UUID.randomUUID();
        UUID gate = UUID.randomUUID();

        var gateNode = makeNode(gate, "gate", false);
        gateNode.setConfigOverrides("{\"terminal_decisions\":[]}");
        var nodes = List.of(makeNode(analyzer, "analyzer", true), gateNode);
        var edges = List.of(makeEdge(analyzer, gate, null), makeEdge(gate, analyzer, "rejected"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("terminal"));
    }

    @Test
    void unreachableFromEntrypoint() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        UUID nodeC = UUID.randomUUID();

        // A is entrypoint, A→B. C exists but has no incoming edge from A or B.
        // C→B exists, so C can reach terminal B, but C is unreachable from entrypoint A.
        var nodes = List.of(makeNode(nodeA, "A", true), makeNode(nodeB, "B", false), makeNode(nodeC, "C", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("C") && e.contains("not reachable from entrypoint"));
    }

    @Test
    void configOverrides_validTimeoutSeconds() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var a = makeNode(nodeA, "A", true);
        a.setConfigOverrides("{\"timeout_seconds\": 300}");
        var nodes = List.of(a, makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void configOverrides_zeroTimeoutSecondsIsValid() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var a = makeNode(nodeA, "A", true);
        a.setConfigOverrides("{\"timeout_seconds\": 0}");
        var nodes = List.of(a, makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void configOverrides_tooLowTimeoutSeconds() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var a = makeNode(nodeA, "A", true);
        a.setConfigOverrides("{\"timeout_seconds\": 30}");
        var nodes = List.of(a, makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("timeout_seconds") && e.contains("60"));
    }

    @Test
    void configOverrides_tooHighTimeoutSeconds() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var a = makeNode(nodeA, "A", true);
        a.setConfigOverrides("{\"timeout_seconds\": 100000}");
        var nodes = List.of(a, makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("timeout_seconds") && e.contains("86400"));
    }

    @Test
    void configOverrides_nonNumericTimeoutSeconds() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var a = makeNode(nodeA, "A", true);
        a.setConfigOverrides("{\"timeout_seconds\": \"not_a_number\"}");
        var nodes = List.of(a, makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("timeout_seconds") && e.contains("number"));
    }

    @Test
    void configOverrides_invalidJson() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var a = makeNode(nodeA, "A", true);
        a.setConfigOverrides("not valid json");
        var nodes = List.of(a, makeNode(nodeB, "B", false));
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("invalid config_overrides JSON"));
    }

    @Test
    void configOverrides_emptyOrNullSkipped() {
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();

        var a = makeNode(nodeA, "A", true);
        a.setConfigOverrides("{}");
        var b = makeNode(nodeB, "B", false);
        b.setConfigOverrides(null);
        var nodes = List.of(a, b);
        var edges = List.of(makeEdge(nodeA, nodeB, null));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void routingHubIsExemptFromReachabilityAndTerminalRules() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID hub = UUID.randomUUID();

        var hubNode = makeNode(hub, "supervisor", false);
        hubNode.setConfigOverrides("{\"routing_hub\": true}");
        var nodes = List.of(makeNode(a, "A", true), makeNode(b, "B", false), hubNode);
        var edges = List.of(makeEdge(a, b, null));

        var result = service.validate(nodes, edges);
        assertThat(result.errors()).isEmpty();
        assertThat(result.valid()).isTrue();
    }

    @Test
    void routingHubMayNotHaveEdges() {
        UUID a = UUID.randomUUID();
        UUID hub = UUID.randomUUID();

        var hubNode = makeNode(hub, "supervisor", false);
        hubNode.setConfigOverrides("{\"routing_hub\": true}");
        var nodes = List.of(makeNode(a, "A", true), hubNode);
        var edges = List.of(makeEdge(a, hub, "escalate"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("supervisor") && e.contains("no edges"));
    }

    @Test
    void atMostOneRoutingHub() {
        UUID a = UUID.randomUUID();
        UUID hub1 = UUID.randomUUID();
        UUID hub2 = UUID.randomUUID();

        var h1 = makeNode(hub1, "sup1", false);
        h1.setConfigOverrides("{\"routing_hub\": true}");
        var h2 = makeNode(hub2, "sup2", false);
        h2.setConfigOverrides("{\"routing_hub\": true}");
        var nodes = List.of(makeNode(a, "A", true), h1, h2);

        var result = service.validate(nodes, List.of());
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("at most one"));
    }

    @Test
    void routingHubIsNotCountedAsATerminalNodeForOthers() {
        // A → B → A is a cycle: neither is terminal. If the hub were left in the terminal set,
        // it would be the graph's only terminal and the errors would be about A and B failing to
        // reach it. Excluding it means the graph has no terminal at all, which is the error we
        // assert — so this test distinguishes the fixed behaviour from the unfixed.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID hub = UUID.randomUUID();

        var hubNode = makeNode(hub, "supervisor", false);
        hubNode.setConfigOverrides("{\"routing_hub\": true}");
        var nodes = List.of(makeNode(a, "A", true), makeNode(b, "B", false), hubNode);
        var edges = List.of(makeEdge(a, b, null), makeEdge(b, a, "loop"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("No terminal node found"));
    }

    @Test
    void edgeConditionEqualToEscalateIsRejected() {
        // graph.go's EvaluateEdges resolves an `escalate` result before any edge inspection, so
        // an edge literally conditioned on "escalate" could never fire — silently shadowed by
        // the routing-hub interpretation instead of erroring.
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        var nodes = List.of(makeNode(a, "A", true), makeNode(b, "B", false));
        var edges = List.of(makeEdge(a, b, "Escalate"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("A") && e.contains("reserved"));
    }

    @Test
    void edgeConditionPrefixedRouteIsRejected() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        var nodes = List.of(makeNode(a, "A", true), makeNode(b, "B", false));
        var edges = List.of(makeEdge(a, b, "ROUTE:final_approval"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("A") && e.contains("reserved"));
    }

    @Test
    void ordinaryEdgeConditionsAreUnaffectedByReservedVocabularyRule() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        var nodes = List.of(makeNode(a, "A", true), makeNode(b, "B", false));
        var edges = List.of(makeEdge(a, b, "approved"));

        var result = service.validate(nodes, edges);
        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    private TemplateNode makeNode(UUID id, String label, boolean entrypoint) {
        var node = new TemplateNode();
        node.setId(id);
        node.setLabel(label);
        node.setEntrypoint(entrypoint);
        return node;
    }

    private TemplateEdge makeEdge(UUID sourceId, UUID targetId, String condition) {
        var edge = new TemplateEdge();
        edge.setSourceNodeId(sourceId);
        edge.setTargetNodeId(targetId);
        edge.setCondition(condition);
        return edge;
    }
}
