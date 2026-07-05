package com.choruskube.core.service;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GraphSnapshotBuilder {

    private static final Logger logger = LoggerFactory.getLogger(GraphSnapshotBuilder.class);

    private final TemplateNodeRepository templateNodeRepo;
    private final NodeDefinitionRepository nodeDefRepo;
    private final TemplateEdgeRepository edgeRepo;
    private final GitRepoRepository gitRepoRepo;
    private final GraphTemplateRepository graphTemplateRepo;
    private final SoftwareProjectRepository softwareProjectRepo;
    private final ObjectMapper objectMapper;

    public GraphSnapshotBuilder(
            TemplateNodeRepository templateNodeRepo,
            NodeDefinitionRepository nodeDefRepo,
            TemplateEdgeRepository edgeRepo,
            GitRepoRepository gitRepoRepo,
            GraphTemplateRepository graphTemplateRepo,
            SoftwareProjectRepository softwareProjectRepo,
            ObjectMapper objectMapper) {
        this.templateNodeRepo = templateNodeRepo;
        this.nodeDefRepo = nodeDefRepo;
        this.edgeRepo = edgeRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.graphTemplateRepo = graphTemplateRepo;
        this.softwareProjectRepo = softwareProjectRepo;
        this.objectMapper = objectMapper;
    }

    private JsonNode parseJsonField(String json, String fallback) {
        try {
            return objectMapper.readTree(json != null && !json.isBlank() ? json : fallback);
        } catch (Exception e) {
            try {
                return objectMapper.readTree(fallback);
            } catch (Exception ex) {
                return objectMapper.createObjectNode();
            }
        }
    }

    /**
     * Builds a graph snapshot on-demand for an existing workflow run.
     * Uses the pinned graphTemplateId to ensure stable node IDs
     * across template version changes.
     */
    @Transactional(readOnly = true)
    public String buildSnapshotForRun(WorkflowRun run) {
        UUID templateId = run.getGraphTemplateId();

        List<TemplateEdge> edges = edgeRepo.findByGraphTemplateId(templateId);

        Map<String, Object> inputs;
        try {
            inputs = objectMapper.readValue(run.getInputs(), new TypeReference<>() {});
        } catch (Exception e) {
            inputs = Map.of();
        }

        return buildSnapshot(templateId, edges, inputs);
    }

    @Transactional(readOnly = true)
    public String buildSnapshot(UUID graphTemplateId, List<TemplateEdge> edges, Map<String, Object> inputs) {
        // Resolve git repo(s) from run inputs — supports both single and multi-repo
        ResolvedRepos resolved = resolveGitRepos(graphTemplateId, inputs);
        List<GitRepo> repos = resolved.repos();
        SoftwareProject project = resolved.project();
        GitRepo gitRepo = repos.isEmpty() ? null : repos.get(0);

        List<TemplateNode> templateNodes = templateNodeRepo.findByGraphTemplateId(graphTemplateId);

        // Extract secrets: prefer git repo's secrets, fall back to inputs
        JsonNode secretsFromInputs = null;
        if (gitRepo != null) {
            secretsFromInputs = parseJsonField(gitRepo.getSecrets(), "[]");
        } else if (inputs != null && inputs.containsKey("secrets")) {
            secretsFromInputs = objectMapper.valueToTree(inputs.get("secrets"));
        }

        ArrayNode nodesArray = objectMapper.createArrayNode();
        for (TemplateNode tn : templateNodes) {
            NodeDefinition nd = nodeDefRepo
                    .findById(tn.getNodeDefinitionId())
                    .orElseThrow(() -> new RuntimeException("NodeDefinition not found: " + tn.getNodeDefinitionId()));

            ObjectNode node = objectMapper.createObjectNode();
            node.put("template_node_id", tn.getId().toString());
            node.put("label", tn.getLabel());
            node.put("executor_type", nd.getExecutorType().name());
            node.put("image", nd.getImage());
            node.put("prompt_template", nd.getPromptTemplate());
            node.put("model", nd.getModel());
            if (nd.getIterationCap() != null) {
                node.put("iteration_cap", nd.getIterationCap());
            }
            node.set("input_spec", parseJsonField(nd.getInputSpec(), "{}"));
            node.set("output_spec", parseJsonField(nd.getOutputSpec(), "{}"));
            node.put("timeout_seconds", nd.getTimeoutSeconds());
            if (secretsFromInputs != null && ExecutorType.ai.equals(nd.getExecutorType())) {
                node.set("secrets", secretsFromInputs);
            } else {
                node.set("secrets", parseJsonField(nd.getSecrets(), "[]"));
            }
            node.set("skills", parseJsonField(nd.getSkills(), "[]"));
            node.put("is_entrypoint", tn.isEntrypoint());

            try {
                JsonNode overrides = objectMapper.readTree(tn.getConfigOverrides());
                overrides.fields().forEachRemaining(entry -> {
                    node.set(entry.getKey(), entry.getValue());
                });
            } catch (Exception e) {
                // empty or invalid overrides
            }

            if (tn.getConfigOverrides() != null && !tn.getConfigOverrides().isBlank()) {
                try {
                    JsonNode rawOverrides = objectMapper.readTree(tn.getConfigOverrides());
                    node.set("config_overrides", rawOverrides);
                } catch (Exception e) {
                    // invalid JSON
                }
            }

            nodesArray.add(node);
        }

        ArrayNode edgesArray = objectMapper.createArrayNode();
        for (TemplateEdge te : edges) {
            ObjectNode edge = objectMapper.createObjectNode();
            edge.put("template_edge_id", te.getId().toString());
            edge.put("source_node_id", te.getSourceNodeId().toString());
            edge.put("target_node_id", te.getTargetNodeId().toString());
            edge.put("condition", te.getCondition());
            edgesArray.add(edge);
        }

        // Resolve enable_docker from SoftwareProject (preferred) or GitRepo
        boolean enableDocker;
        if (project != null) {
            enableDocker = project.getRuntimeRequirements().enableDocker();
        } else if (gitRepo != null) {
            enableDocker = gitRepo.isEnableDocker();
        } else if (inputs != null && inputs.containsKey("enable_docker")) {
            enableDocker = Boolean.parseBoolean(inputs.get("enable_docker").toString());
        } else {
            enableDocker = false;
        }

        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.set("nodes", nodesArray);
        snapshot.set("edges", edgesArray);
        snapshot.put("enable_docker", enableDocker);

        // Inject repo fields into inputs so {run.*} template variables still resolve
        Map<String, Object> enrichedInputs = new HashMap<>();
        if (inputs != null) {
            enrichedInputs.putAll(inputs);
        }
        if (gitRepo != null) {
            enrichedInputs.put("repo_url", gitRepo.getUrl());
            if (gitRepo.getTestCommand() != null) {
                enrichedInputs.put("test_command", gitRepo.getTestCommand());
            }
            if (gitRepo.getAgentImage() != null) {
                enrichedInputs.put("agent_image", gitRepo.getAgentImage());
            }
        }
        // SoftwareProject's image is the source of truth for software_project_id inputs
        // (RepoGroup uses group-level image, not first member's).
        if (project != null) {
            String projectImage = project.getRuntimeRequirements().agentImage();
            if (projectImage != null) {
                enrichedInputs.put("agent_image", projectImage);
            } else {
                enrichedInputs.remove("agent_image");
            }
        }
        if (!enrichedInputs.isEmpty()) {
            snapshot.set("inputs", objectMapper.valueToTree(enrichedInputs));
        }

        // Add repos array to snapshot (multi-repo metadata for orchestrator/agent)
        if (!repos.isEmpty()) {
            ArrayNode reposArray = objectMapper.createArrayNode();
            for (GitRepo r : repos) {
                ObjectNode repoNode = objectMapper.createObjectNode();
                repoNode.put("id", r.getId().toString());
                repoNode.put("url", r.getUrl());
                repoNode.put("name", deriveRepoName(r.getUrl()));
                if (r.getTestCommand() != null) {
                    repoNode.put("test_command", r.getTestCommand());
                }
                if (r.getAgentImage() != null) {
                    repoNode.put("agent_image", r.getAgentImage());
                }
                reposArray.add(repoNode);
            }
            snapshot.set("repos", reposArray);
        }

        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize graph snapshot", e);
        }
    }

    /**
     * Scans the template's input schema for software_project_id and git_repo fields.
     * Returns an ordered list of resolved GitRepo entities, plus the SoftwareProject (if any) for
     * runtime-requirement overrides.
     */
    private ResolvedRepos resolveGitRepos(UUID graphTemplateId, Map<String, Object> inputs) {
        if (inputs == null) return new ResolvedRepos(List.of(), null);

        // Try schema-driven discovery for software_project_id fields
        GraphTemplate template = graphTemplateRepo.findById(graphTemplateId).orElse(null);
        if (template != null && template.getInputSchema() != null) {
            try {
                JsonNode schema = objectMapper.readTree(template.getInputSchema());
                if (schema.isArray()) {
                    for (JsonNode field : schema) {
                        String type = field.path("type").asText("");
                        String name = field.path("name").asText("");
                        if ("software_project_id".equals(type) && inputs.containsKey(name)) {
                            try {
                                UUID projectId =
                                        UUID.fromString(inputs.get(name).toString());
                                SoftwareProject project =
                                        softwareProjectRepo.findById(projectId).orElse(null);
                                if (project != null) {
                                    return new ResolvedRepos(project.resolveRepos(), project);
                                }
                            } catch (IllegalArgumentException e) {
                                // invalid UUID — fall through to other branches
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn(
                        "Failed to resolve git repos from input schema for template {}: {}",
                        graphTemplateId,
                        e.getMessage());
            }
        }

        // Legacy single-repo fallback
        GitRepo single = resolveGitRepo(inputs);
        if (single != null) return new ResolvedRepos(List.of(single), null);
        return new ResolvedRepos(List.of(), null);
    }

    private record ResolvedRepos(List<GitRepo> repos, SoftwareProject project) {}

    private GitRepo resolveGitRepo(Map<String, Object> inputs) {
        if (inputs == null || !inputs.containsKey("git_repo_id")) {
            return null;
        }
        try {
            UUID gitRepoId = UUID.fromString(inputs.get("git_repo_id").toString());
            return gitRepoRepo.findById(gitRepoId).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String deriveRepoName(String url) {
        return com.choruskube.core.util.RepoNameUtil.deriveRepoName(url);
    }
}
