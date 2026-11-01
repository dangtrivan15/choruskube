package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The e2e seeder reuses this wiring with per-label mock config; the merge must stay per label. */
class TaskDeliveryGraphTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void extraConfigMergesIntoItsLabelOnly() throws Exception {
        InMemoryGraph graph = new InMemoryGraph();
        TaskDeliveryGraph.seed(
                graph.writer,
                graph.template,
                Map.of(
                        TaskDeliveryGraph.FINAL_APPROVAL,
                        "{\"command\": \"mock final\", \"story_version_recheck\": {\"route\": \"redraft\"}}"));

        JsonNode finalApproval =
                JSON.readTree(graph.node(TaskDeliveryGraph.FINAL_APPROVAL).getConfigOverrides());
        assertThat(finalApproval.get("command").asText()).isEqualTo("mock final");
        assertThat(finalApproval.get("story_version_recheck")).isEqualTo(JSON.readTree("{\"route\": \"redraft\"}"));
        assertThat(finalApproval.get("merge_pull_requests").asText()).isEqualTo("squash");
        assertThat(finalApproval.get("terminal_decisions")).isEqualTo(JSON.readTree("[\"approved\"]"));

        for (String other : new String[] {
            TaskDeliveryGraph.ANALYZE,
            TaskDeliveryGraph.APPROVE_TASK_SPEC,
            TaskDeliveryGraph.IMPLEMENT,
            TaskDeliveryGraph.CODE_REVIEW
        }) {
            assertThat(graph.node(other).getConfigOverrides()).as(other).doesNotContain("mock final");
        }
    }

    @Test
    void unknownLabelIsRejectedBeforeAnythingIsWritten() {
        InMemoryGraph graph = new InMemoryGraph();
        assertThatThrownBy(() -> TaskDeliveryGraph.seed(graph.writer, graph.template, Map.of("draft_spec", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft_spec");
        assertThat(graph.definitions()).isEmpty();
        assertThat(graph.nodes()).isEmpty();
    }
}
