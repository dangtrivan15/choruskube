package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DecisionOptionsResolverTest {

    private final DecisionOptionsResolver resolver = new DecisionOptionsResolver();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final UUID AI = UUID.randomUUID();
    private static final UUID SCRIPT = UUID.randomUUID();
    private static final UUID GATE = UUID.randomUUID();
    private static final UUID HUB = UUID.randomUUID();

    /** Snapshot with an AI node, a script node, a human gate, and (optionally) a Supervisor. */
    private JsonNode snapshot(boolean withHub) throws Exception {
        String hubNode = withHub
                ? ",{\"template_node_id\":\"" + HUB + "\",\"label\":\"supervisor\","
                        + "\"executor_type\":\"human\",\"config_overrides\":{\"routing_hub\":true}}"
                : "";
        return mapper.readTree("{\"nodes\":["
                + "{\"template_node_id\":\"" + AI + "\",\"label\":\"code_review\",\"executor_type\":\"ai\"},"
                + "{\"template_node_id\":\"" + SCRIPT + "\",\"label\":\"test\",\"executor_type\":\"script\"},"
                + "{\"template_node_id\":\"" + GATE + "\",\"label\":\"final_approval\","
                + "\"executor_type\":\"human\",\"config_overrides\":{\"terminal_decisions\":[\"approved\"]}}"
                + hubNode
                + "],\"edges\":["
                + "{\"source_node_id\":\"" + AI + "\",\"target_node_id\":\"" + SCRIPT
                + "\",\"condition\":\"approved\"},"
                + "{\"source_node_id\":\"" + AI + "\",\"target_node_id\":\"" + AI
                + "\",\"condition\":\"revised\"},"
                + "{\"source_node_id\":\"" + GATE + "\",\"target_node_id\":\"" + AI
                + "\",\"condition\":\"rereview\"}"
                + "]}");
    }

    @Test
    void aiNodeGainsEscalateWhenTemplateHasSupervisor() throws Exception {
        assertThat(resolver.resolve(snapshot(true), AI)).containsExactly("approved", "revised", "escalate");
    }

    @Test
    void aiNodeHasNoEscalateWhenTemplateHasNoSupervisor() throws Exception {
        assertThat(resolver.resolve(snapshot(false), AI)).containsExactly("approved", "revised");
    }

    @Test
    void scriptNodeNeverGainsEscalate() throws Exception {
        assertThat(resolver.resolve(snapshot(true), SCRIPT)).isEmpty();
    }

    @Test
    void humanGateNeverGainsEscalateButKeepsTerminalDecisions() throws Exception {
        assertThat(resolver.resolve(snapshot(true), GATE)).containsExactly("rereview", "approved");
    }

    @Test
    void supervisorOffersRouteToEveryNonHubNodeAndNotItself() throws Exception {
        assertThat(resolver.resolve(snapshot(true), HUB))
                .containsExactly("route:code_review", "route:test", "route:final_approval");
    }

    @Test
    void routeTargetLabelParsesOnlyRoutePrefixedDecisions() {
        assertThat(DecisionOptionsResolver.routeTargetLabel("route:implement")).isEqualTo("implement");
        assertThat(DecisionOptionsResolver.routeTargetLabel("approved")).isNull();
        assertThat(DecisionOptionsResolver.routeTargetLabel(null)).isNull();
    }

    @Test
    void findRoutingHubReturnsNullWhenAbsent() throws Exception {
        assertThat(resolver.findRoutingHub(snapshot(false))).isNull();
        assertThat(resolver.findRoutingHub(snapshot(true)).get("label").asText())
                .isEqualTo("supervisor");
    }

    @Test
    void isRoutingHubStringTrueWhenRoutingHubDeclared() throws Exception {
        assertThat(DecisionOptionsResolver.isRoutingHub("{\"routing_hub\": true}", mapper))
                .isTrue();
    }

    @Test
    void isRoutingHubStringFalseWhenMissingEmptyOrMalformed() {
        assertThat(DecisionOptionsResolver.isRoutingHub(null, mapper)).isFalse();
        assertThat(DecisionOptionsResolver.isRoutingHub("{}", mapper)).isFalse();
        assertThat(DecisionOptionsResolver.isRoutingHub("{oops", mapper)).isFalse();
    }

    @Test
    void isRoutingHubRejectsTruthyNonBooleanShapes() {
        // Jackson's asBoolean(false) coerces the string "true" and non-zero numbers to true; the
        // orchestrator (a real .(bool) type assertion) and RunDag.tsx (=== true) both reject
        // those shapes, so the Java side must match or a template becomes a hub to one component
        // and not the other two.
        assertThat(DecisionOptionsResolver.isRoutingHub("{\"routing_hub\": \"true\"}", mapper))
                .isFalse();
        assertThat(DecisionOptionsResolver.isRoutingHub("{\"routing_hub\": 1}", mapper))
                .isFalse();
    }

    @Test
    void resolveTreatsStringTrueRoutingHubAsNotAHub() throws Exception {
        // Same coercion rejection, exercised through the snapshot-node path used at decision
        // time (the private isRoutingHub(JsonNode) overload) rather than the entity-side string
        // path above.
        JsonNode snapshot = mapper.readTree("{\"nodes\":["
                + "{\"template_node_id\":\"" + AI + "\",\"label\":\"code_review\",\"executor_type\":\"ai\"},"
                + "{\"template_node_id\":\"" + HUB + "\",\"label\":\"supervisor\",\"executor_type\":\"human\","
                + "\"config_overrides\":{\"routing_hub\":\"true\"}}"
                + "],\"edges\":[]}");

        assertThat(resolver.findRoutingHub(snapshot)).isNull();
        assertThat(resolver.resolve(snapshot, AI)).doesNotContain("escalate");
    }
}
