package com.choruskube.core.config;

import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class TemplateWriter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GraphTemplateRepository templateRepo;
    private final NodeDefinitionRepository nodeDefRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final TemplateEdgeRepository edgeRepo;

    public TemplateWriter(
            GraphTemplateRepository templateRepo,
            NodeDefinitionRepository nodeDefRepo,
            TemplateNodeRepository templateNodeRepo,
            TemplateEdgeRepository edgeRepo) {
        this.templateRepo = templateRepo;
        this.nodeDefRepo = nodeDefRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.edgeRepo = edgeRepo;
    }

    public GraphTemplate template(GraphTemplate template) {
        return templateRepo.save(template);
    }

    public NodeDefinition nodeDef(
            String name, ExecutorType type, String prompt, int timeoutSeconds, String model, String outputSpec) {
        NodeDefinition nd = new NodeDefinition();
        nd.setName(name);
        nd.setExecutorType(type);
        nd.setPromptTemplate(prompt);
        nd.setTimeoutSeconds(timeoutSeconds);
        nd.setModel(model);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec(outputSpec == null ? "{}" : outputSpec);
        nd.setSecrets("[]");
        return nodeDefRepo.save(nd);
    }

    public TemplateNode node(
            GraphTemplate t,
            NodeDefinition nd,
            String label,
            boolean entrypoint,
            String configOverrides,
            String requiredInputArtifacts) {
        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(t.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel(label);
        tn.setEntrypoint(entrypoint);
        tn.setConfigOverrides(configOverrides);
        tn.setRequiredInputArtifacts(requiredInputArtifacts);
        return templateNodeRepo.save(tn);
    }

    public TemplateEdge edge(GraphTemplate t, TemplateNode from, TemplateNode to, String condition) {
        TemplateEdge te = new TemplateEdge();
        te.setGraphTemplateId(t.getId());
        te.setSourceNodeId(from.getId());
        te.setTargetNodeId(to.getId());
        te.setCondition(condition);
        return edgeRepo.save(te);
    }

    /** Shallow: a nested object in {@code extraJson} replaces the base's whole one, not merged into it. */
    public static String mergeConfig(String baseJson, String extraJson) {
        if (extraJson == null) {
            return baseJson;
        }
        ObjectNode merged = asObject(baseJson);
        merged.setAll(asObject(extraJson));
        return merged.toString();
    }

    private static ObjectNode asObject(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Not valid JSON: " + json, e);
        }
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Not a JSON object: " + json);
        }
        return object;
    }
}
