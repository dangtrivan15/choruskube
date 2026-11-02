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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.mockito.stubbing.Answer;
import org.springframework.boot.ApplicationRunner;

/**
 * SHA-256 of everything a template seeder writes, taken by running it against in-memory
 * repositories. A seeder never re-seeds a stored version, so whatever moves this digest without a
 * version bump reaches fresh databases only, and existing ones keep the old template silently.
 *
 * <p>JSON columns are compared by content, and nodes and edges by label rather than insertion
 * order, so reformatting or reordering the seeder's code moves nothing.
 */
final class SeededTemplateDigest {

    @FunctionalInterface
    interface SeederFactory {
        ApplicationRunner create(
                GraphTemplateRepository templateRepo,
                NodeDefinitionRepository nodeDefRepo,
                TemplateNodeRepository templateNodeRepo,
                TemplateEdgeRepository edgeRepo,
                ObjectMapper objectMapper);
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private SeededTemplateDigest() {}

    static String sha256(SeederFactory factory) throws Exception {
        List<GraphTemplate> templates = new ArrayList<>();
        Map<UUID, NodeDefinition> definitions = new HashMap<>();
        List<TemplateNode> nodes = new ArrayList<>();
        List<TemplateEdge> edges = new ArrayList<>();

        // An unstubbed finder answers Optional.empty(), so the seeder sees a database without its
        // version and writes the whole template.
        GraphTemplateRepository templateRepo = mock(GraphTemplateRepository.class);
        NodeDefinitionRepository nodeDefRepo = mock(NodeDefinitionRepository.class);
        TemplateNodeRepository templateNodeRepo = mock(TemplateNodeRepository.class);
        TemplateEdgeRepository edgeRepo = mock(TemplateEdgeRepository.class);
        when(templateRepo.save(any(GraphTemplate.class))).thenAnswer(saving(GraphTemplate::setId, templates::add));
        when(nodeDefRepo.save(any(NodeDefinition.class)))
                .thenAnswer(saving(NodeDefinition::setId, (NodeDefinition nd) -> definitions.put(nd.getId(), nd)));
        when(templateNodeRepo.save(any(TemplateNode.class))).thenAnswer(saving(TemplateNode::setId, nodes::add));
        when(edgeRepo.save(any(TemplateEdge.class))).thenAnswer(saving(TemplateEdge::setId, edges::add));

        factory.create(templateRepo, nodeDefRepo, templateNodeRepo, edgeRepo, new ObjectMapper())
                .run(null);

        if (templates.size() != 1) {
            throw new IllegalStateException("Expected the seeder to write one template, got " + templates.size());
        }
        String canonical = JSON.writeValueAsString(canonical(templates.get(0), definitions, nodes, edges));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static <T> Answer<T> saving(BiConsumer<T, UUID> setId, Consumer<T> record) {
        return invocation -> {
            T entity = invocation.getArgument(0);
            setId.accept(entity, UUID.randomUUID());
            record.accept(entity);
            return entity;
        };
    }

    private static Map<String, Object> canonical(
            GraphTemplate template,
            Map<UUID, NodeDefinition> definitions,
            List<TemplateNode> nodes,
            List<TemplateEdge> edges) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("graphId", template.getGraphId());
        t.put("name", template.getName());
        t.put("description", template.getDescription());
        t.put("inputSchema", json(template.getInputSchema()));
        t.put("promptInputKey", template.getPromptInputKey());
        t.put("system", template.isSystem());
        out.put("template", t);

        Map<UUID, String> labelById = new HashMap<>();
        nodes.forEach(n -> labelById.put(n.getId(), n.getLabel()));

        List<Map<String, Object>> nodeEntries = new ArrayList<>();
        for (TemplateNode node : nodes) {
            NodeDefinition nd = definitions.get(node.getNodeDefinitionId());
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("name", nd.getName());
            d.put("executorType", nd.getExecutorType().name());
            d.put("prompt", nd.getPromptTemplate());
            d.put("timeoutSeconds", nd.getTimeoutSeconds());
            d.put("model", nd.getModel());
            d.put("image", nd.getImage());
            d.put("skills", json(nd.getSkills()));
            d.put("inputSpec", json(nd.getInputSpec()));
            d.put("outputSpec", json(nd.getOutputSpec()));
            d.put("secrets", json(nd.getSecrets()));

            Map<String, Object> n = new LinkedHashMap<>();
            n.put("label", node.getLabel());
            n.put("entrypoint", node.isEntrypoint());
            n.put("configOverrides", json(node.getConfigOverrides()));
            n.put("requiredInputArtifacts", json(node.getRequiredInputArtifacts()));
            n.put("definition", d);
            nodeEntries.add(n);
        }
        nodeEntries.sort(Comparator.comparing(n -> (String) n.get("label")));
        out.put("nodes", nodeEntries);

        List<String> edgeEntries = new ArrayList<>();
        for (TemplateEdge edge : edges) {
            edgeEntries.add(labelById.get(edge.getSourceNodeId()) + " -> " + labelById.get(edge.getTargetNodeId())
                    + " [" + edge.getCondition() + "]");
        }
        edgeEntries.sort(Comparator.naturalOrder());
        out.put("edges", edgeEntries);
        return out;
    }

    private static Object json(String text) {
        if (text == null) {
            return null;
        }
        try {
            return JSON.readValue(text, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Seeded a column that is not JSON: " + text, e);
        }
    }
}
