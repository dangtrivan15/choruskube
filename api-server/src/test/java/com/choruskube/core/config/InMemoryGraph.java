package com.choruskube.core.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A {@link TemplateWriter} over in-memory repositories, for reading a graph's wiring without a database. */
final class InMemoryGraph {

    final TemplateWriter writer;
    final GraphTemplate template = new GraphTemplate();

    private final List<NodeDefinition> definitions = new ArrayList<>();
    private final List<TemplateNode> nodes = new ArrayList<>();
    private final List<TemplateEdge> edges = new ArrayList<>();

    InMemoryGraph() {
        NodeDefinitionRepository nodeDefRepo = mock(NodeDefinitionRepository.class);
        TemplateNodeRepository templateNodeRepo = mock(TemplateNodeRepository.class);
        TemplateEdgeRepository edgeRepo = mock(TemplateEdgeRepository.class);
        when(nodeDefRepo.save(any(NodeDefinition.class))).thenAnswer(inv -> {
            NodeDefinition nd = inv.getArgument(0);
            nd.setId(UUID.randomUUID());
            definitions.add(nd);
            return nd;
        });
        when(templateNodeRepo.save(any(TemplateNode.class))).thenAnswer(inv -> {
            TemplateNode tn = inv.getArgument(0);
            tn.setId(UUID.randomUUID());
            nodes.add(tn);
            return tn;
        });
        when(edgeRepo.save(any(TemplateEdge.class))).thenAnswer(inv -> {
            TemplateEdge te = inv.getArgument(0);
            edges.add(te);
            return te;
        });
        writer = new TemplateWriter(mock(GraphTemplateRepository.class), nodeDefRepo, templateNodeRepo, edgeRepo);
        template.setId(UUID.randomUUID());
    }

    List<NodeDefinition> definitions() {
        return definitions;
    }

    List<TemplateNode> nodes() {
        return nodes;
    }

    List<TemplateEdge> edges() {
        return edges;
    }

    TemplateNode node(String label) {
        return nodes.stream()
                .filter(n -> label.equals(n.getLabel()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No node labelled " + label));
    }

    NodeDefinition definition(String label) {
        UUID id = node(label).getNodeDefinitionId();
        return definitions.stream()
                .filter(d -> id.equals(d.getId()))
                .findFirst()
                .orElseThrow();
    }
}
