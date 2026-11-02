package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The e2e seeder reuses this wiring with per-label mock config; the merge must stay per label. */
class StoryDesignGraphTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NodeDefinitionRepository nodeDefRepo = mock(NodeDefinitionRepository.class);
    private final TemplateNodeRepository templateNodeRepo = mock(TemplateNodeRepository.class);
    private final TemplateEdgeRepository edgeRepo = mock(TemplateEdgeRepository.class);
    private final List<TemplateNode> savedNodes = new ArrayList<>();

    private TemplateWriter writer;
    private GraphTemplate template;

    @BeforeEach
    void setUp() {
        when(nodeDefRepo.save(any(NodeDefinition.class))).thenAnswer(inv -> {
            NodeDefinition nd = inv.getArgument(0);
            nd.setId(UUID.randomUUID());
            return nd;
        });
        when(templateNodeRepo.save(any(TemplateNode.class))).thenAnswer(inv -> {
            TemplateNode tn = inv.getArgument(0);
            tn.setId(UUID.randomUUID());
            savedNodes.add(tn);
            return tn;
        });
        when(edgeRepo.save(any(TemplateEdge.class))).thenAnswer(inv -> inv.getArgument(0));
        writer = new TemplateWriter(mock(GraphTemplateRepository.class), nodeDefRepo, templateNodeRepo, edgeRepo);
        template = new GraphTemplate();
        template.setId(UUID.randomUUID());
    }

    private TemplateNode saved(String label) {
        return savedNodes.stream()
                .filter(n -> label.equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void extraConfigMergesIntoItsLabelOnly() throws Exception {
        StoryDesignGraph.seed(
                writer,
                template,
                Map.of(
                        StoryDesignGraph.DRAFT,
                        "{\"executor_type\": \"script\", \"command\": \"mock story_spec_draft\"}"));

        var draft = JSON.readTree(saved(StoryDesignGraph.DRAFT).getConfigOverrides());
        assertThat(draft.get("loop_group").asText()).isEqualTo("story-spec");
        assertThat(draft.get("effort").asText()).isEqualTo("xhigh");
        assertThat(draft.get("executor_type").asText()).isEqualTo("script");
        assertThat(draft.get("command").asText()).isEqualTo("mock story_spec_draft");

        assertThat(saved(StoryDesignGraph.REVIEW).getConfigOverrides()).doesNotContain("command");
        assertThat(saved(StoryDesignGraph.APPROVE).getConfigOverrides()).doesNotContain("command");
        assertThat(saved(StoryDesignGraph.SUPERVISOR).getConfigOverrides()).doesNotContain("command");
    }

    @Test
    void unknownLabelIsRejectedBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> StoryDesignGraph.seed(writer, template, Map.of("draft_spec", "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("draft_spec");
        verify(nodeDefRepo, never()).save(any());
    }
}
