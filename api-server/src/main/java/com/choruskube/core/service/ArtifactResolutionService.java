package com.choruskube.core.service;

import com.choruskube.core.dto.InputArtifactManifest;
import com.choruskube.core.dto.ResolvedArtifactEntry;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.ReviewerType;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ArtifactResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactResolutionService.class);

    /** Key under which an agent node records the object storage prefix holding its output files. */
    private static final String OUTPUT_PREFIX_KEY = "output";

    private final TemplateNodeRepository templateNodeRepo;
    private final NodeExecutionRepository nodeExecutionRepo;
    private final WorkflowRunRepository workflowRunRepo;
    private final GraphSnapshotBuilder snapshotBuilder;
    private final ObjectMapper objectMapper;
    private final DecisionOptionsResolver decisionOptionsResolver;

    public ArtifactResolutionService(
            TemplateNodeRepository templateNodeRepo,
            NodeExecutionRepository nodeExecutionRepo,
            WorkflowRunRepository workflowRunRepo,
            GraphSnapshotBuilder snapshotBuilder,
            ObjectMapper objectMapper,
            DecisionOptionsResolver decisionOptionsResolver) {
        this.templateNodeRepo = templateNodeRepo;
        this.nodeExecutionRepo = nodeExecutionRepo;
        this.workflowRunRepo = workflowRunRepo;
        this.snapshotBuilder = snapshotBuilder;
        this.objectMapper = objectMapper;
        this.decisionOptionsResolver = decisionOptionsResolver;
    }

    /**
     * Resolves required input artifacts for a human-gate template node in a run.
     *
     * <p>The Supervisor (a routing-hub node) declares no {@code required_input_artifacts} of its
     * own — it is template-agnostic — so it is resolved separately via {@link
     * #resolveEscalatingExecution}.
     *
     * @return List of resolved artifact groups; {@code List.of()} for the Supervisor when nothing
     *     has escalated yet; or {@code null} if no declarations exist (legacy mode).
     */
    public List<ResolvedArtifactGroup> resolveRequiredArtifacts(UUID templateNodeId, UUID runId) {
        TemplateNode gateNode = templateNodeRepo.findById(templateNodeId).orElse(null);
        if (gateNode == null) {
            return null;
        }
        // The Supervisor declares no required_input_artifacts — it is template-agnostic, so there
        // is nothing to declare ahead of time. Its one guaranteed input is whichever execution
        // escalated to it, surfaced as the platform-contract file the escalator is required to
        // author.
        if (DecisionOptionsResolver.isRoutingHub(gateNode.getConfigOverrides(), objectMapper)) {
            NodeExecution escalator = resolveEscalatingExecution(runId);
            if (escalator == null) {
                return List.of();
            }
            return List.of(new ResolvedArtifactGroup(
                    escalator.getId(),
                    escalator.getLabel() == null ? "" : escalator.getLabel(),
                    List.of(new ResolvedArtifactEntry(
                            "escalation.md", "Why this run was escalated to the Supervisor", true))));
        }
        if (gateNode.getRequiredInputArtifacts() == null) {
            return null;
        }

        try {
            JsonNode declarations = objectMapper.readTree(gateNode.getRequiredInputArtifacts());
            if (!declarations.isArray()) {
                return null;
            }

            UUID graphTemplateId = gateNode.getGraphTemplateId();
            if (graphTemplateId == null) {
                log.warn("TemplateNode {} has null graphTemplateId; skipping artifact resolution", templateNodeId);
                return null;
            }
            // Load all template nodes in the same template for label lookup
            List<TemplateNode> allTemplateNodes = templateNodeRepo.findByGraphTemplateId(graphTemplateId);
            Map<String, UUID> labelToTemplateNodeId = new HashMap<>();
            for (TemplateNode tn : allTemplateNodes) {
                labelToTemplateNodeId.put(tn.getLabel(), tn.getId());
            }

            // Load all completed executions in the run
            List<NodeExecution> completedExecs =
                    nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed);

            List<ResolvedArtifactGroup> groups = new ArrayList<>();
            for (JsonNode group : declarations) {
                String nodeLabel = group.path("template_node_label").asText(null);
                JsonNode artifactsNode = group.path("artifacts");
                if (nodeLabel == null || !artifactsNode.isArray()) {
                    log.warn(
                            "Skipping malformed required_input_artifacts group for template node {}: "
                                    + "missing template_node_label or non-array artifacts",
                            templateNodeId);
                    continue;
                }

                List<ResolvedArtifactEntry> entries = new ArrayList<>();
                for (JsonNode artifactNode : artifactsNode) {
                    String entryName = artifactNode.path("name").asText(null);
                    if (entryName == null || entryName.isBlank()) {
                        log.warn("Skipping artifact entry with missing name in template node {}", templateNodeId);
                        continue;
                    }
                    // Absent "required" means optional — an unflagged declaration must never harden
                    // into a pod abort, since several legitimately reference a prior iteration that
                    // does not exist on iteration 1.
                    entries.add(new ResolvedArtifactEntry(
                            entryName,
                            artifactNode.path("description").asText(null),
                            artifactNode.path("required").asBoolean(false)));
                }

                UUID sourceTemplateNodeId = labelToTemplateNodeId.get(nodeLabel);
                UUID resolvedExecId = null;
                if (sourceTemplateNodeId != null) {
                    // Find the latest-iteration completed execution for this template node in the run
                    resolvedExecId = completedExecs.stream()
                            .filter(e -> e.getTemplateNodeId().equals(sourceTemplateNodeId))
                            .max(Comparator.comparingInt(NodeExecution::getIteration))
                            .map(NodeExecution::getId)
                            .orElse(null);
                }

                groups.add(new ResolvedArtifactGroup(resolvedExecId, nodeLabel, entries));
            }

            return groups;
        } catch (Exception e) {
            log.warn("Failed to resolve required artifacts for template node {}: {}", templateNodeId, e.getMessage());
            return null;
        }
    }

    /**
     * The execution that paged the Supervisor: the most recently completed execution whose
     * decision is {@code escalate}. Selection is by {@code completed_at}, not iteration — the
     * Supervisor is re-entered many times per run and the relevant escalation is the one that just
     * happened, not the highest-numbered one.
     */
    NodeExecution resolveEscalatingExecution(UUID runId) {
        return nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed).stream()
                .filter(e -> DecisionOptionsResolver.ESCALATE_DECISION.equalsIgnoreCase(e.getDecision()))
                .max(Comparator.comparing(
                        NodeExecution::getCompletedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    /**
     * Resolves the files to materialise under {@code /workspace/in/} before this node execution's
     * agent starts, so the agent reads them off disk instead of hunting object storage.
     *
     * <p>Two arms, deliberately selecting differently:
     *
     * <ul>
     *   <li><b>Declared</b> — {@code required_input_artifacts} names a file and a source label;
     *       the source's {@code artifact_refs["output"]} supplies the directory prefix. Selection is
     *       latest-iteration, matching {@code resolveRequiredArtifacts}.
     *   <li><b>Passthrough</b> — a human gate's {@code artifact_refs} is already a
     *       filename → object path map, so it needs no declaration. This matters because gate
     *       attachments are arbitrary (a reviewer can attach anything) and cannot be declared when
     *       the template is authored. Selection is the gate that actually routed back to this node,
     *       not latest-iteration: a later gate round may carry no attachments at all, and picking it
     *       would silently drop the guidance that caused this re-run.
     * </ul>
     */
    public InputArtifactManifest resolveInputArtifactManifest(UUID runId, UUID nodeExecId) {
        Map<String, String> artifacts = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        NodeExecution exec = nodeExecutionRepo.findById(nodeExecId).orElse(null);
        if (exec == null) {
            log.warn("Node execution {} not found; returning empty input artifact manifest", nodeExecId);
            return new InputArtifactManifest(artifacts, required);
        }

        collectDeclaredArtifacts(runId, exec, artifacts, required);
        collectRoutingGateArtifacts(runId, exec, artifacts);

        return new InputArtifactManifest(artifacts, required);
    }

    /** Declared arm: {@code required_input_artifacts} × the source execution's output prefix. */
    private void collectDeclaredArtifacts(
            UUID runId, NodeExecution exec, Map<String, String> artifacts, List<String> required) {
        List<ResolvedArtifactGroup> groups = resolveRequiredArtifacts(exec.getTemplateNodeId(), runId);
        if (groups == null) {
            return;
        }
        for (ResolvedArtifactGroup group : groups) {
            if (group.nodeExecutionId() == null) {
                // Source node has not completed in this run yet — nothing to materialise.
                continue;
            }
            NodeExecution source =
                    nodeExecutionRepo.findById(group.nodeExecutionId()).orElse(null);
            if (source == null) {
                continue;
            }
            String prefix = readRef(source.getArtifactRefs(), OUTPUT_PREFIX_KEY);
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            if (!prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
            for (ResolvedArtifactEntry entry : group.artifacts()) {
                String key = group.nodeLabel() + "/" + entry.name();
                artifacts.put(key, prefix + entry.name());
                if (entry.required()) {
                    required.add(key);
                }
            }
        }
    }

    /**
     * Passthrough arm: attachments from the human gate whose decision routed back to this node.
     *
     * <p>Two ways a human gate can have routed work here, and the two arms below are genuinely
     * alternative — neither shares a precondition with the other, because the Supervisor's
     * execution row is not reliably a {@code reviewerType == human} row (see below):
     *
     * <ul>
     *   <li>An ordinary gate fires an edge, which the orchestrator records in {@code
     *       traversed_edge_ids}, so that case is an exact lookup rather than a re-evaluation of
     *       edge conditions, gated on {@code reviewerType == human} to exclude AI nodes that
     *       happen to share a traversed edge id space.
     *   <li>The Supervisor fires no edge at all, so it is matched instead on its {@code
     *       route:<label>} decision string, structurally confirmed by requiring the deciding
     *       execution's template node id to actually be the template's routing hub — not merely
     *       inferred from the decision string shape. This cannot be folded into the same {@code
     *       reviewerType == human} precondition as the edge-based arm: {@code reviewerType} is
     *       only ever set by {@code InternalRunService.writeReviewHistory}, which the orchestrator
     *       calls solely for nodes with a non-empty loop group, and the Supervisor has none by
     *       design (its loop-group context comes from the escalator, not itself) — so its
     *       execution row's {@code reviewerType} stays {@code null} in production.
     * </ul>
     */
    private void collectRoutingGateArtifacts(UUID runId, NodeExecution exec, Map<String, String> artifacts) {
        try {
            WorkflowRun run = workflowRunRepo.findById(runId).orElse(null);
            if (run == null) {
                return;
            }
            JsonNode snapshot = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));

            // Edges whose target is this node — the ones that could have routed work here via the
            // ordinary edge-firing path.
            Set<UUID> inboundEdgeIds = new HashSet<>();
            JsonNode edges = snapshot.get("edges");
            if (edges != null) {
                for (JsonNode edge : edges) {
                    UUID target = UUID.fromString(edge.get("target_node_id").asText());
                    if (target.equals(exec.getTemplateNodeId())) {
                        inboundEdgeIds.add(
                                UUID.fromString(edge.get("template_edge_id").asText()));
                    }
                }
            }

            // This node's own label, needed to recognise the Supervisor's route:<label> decision.
            // The Supervisor fires no edge, so a node it routed to — including one with no inbound
            // edges at all, e.g. the template's entrypoint — has nothing in traversed_edge_ids to
            // match; the decision string is the only record of where it sent the run.
            String myLabel = null;
            JsonNode nodes = snapshot.get("nodes");
            if (nodes != null) {
                for (JsonNode node : nodes) {
                    if (node.path("template_node_id")
                            .asText()
                            .equals(exec.getTemplateNodeId().toString())) {
                        myLabel = node.path("label").asText(null);
                        break;
                    }
                }
            }
            final String targetLabel = myLabel;

            // The routing hub's own template node id — required to structurally confirm a
            // route:<label> decision actually came from the hub, rather than trusting the decision
            // string shape alone.
            JsonNode hubNode = decisionOptionsResolver.findRoutingHub(snapshot);
            final UUID hubNodeId = hubNode == null
                    ? null
                    : UUID.fromString(hubNode.path("template_node_id").asText());

            NodeExecution gate =
                    nodeExecutionRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed).stream()
                            .filter(e -> (e.getReviewerType() == ReviewerType.human && traversedAny(e, inboundEdgeIds))
                                    || (hubNodeId != null
                                            && hubNodeId.equals(e.getTemplateNodeId())
                                            && targetLabel != null
                                            && targetLabel.equalsIgnoreCase(
                                                    DecisionOptionsResolver.routeTargetLabel(e.getDecision()))))
                            .max(Comparator.comparing(
                                    NodeExecution::getCompletedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                            .orElse(null);
            if (gate == null || gate.getLabel() == null) {
                return;
            }

            // A gate's artifact_refs is already filename → object path, so it maps straight across.
            Map<String, String> refs = readAllRefs(gate.getArtifactRefs());
            for (Map.Entry<String, String> ref : refs.entrySet()) {
                artifacts.put(gate.getLabel() + "/" + ref.getKey(), ref.getValue());
            }
        } catch (Exception e) {
            // Gate attachments are a convenience: the run log still carries every gate's feedback
            // text and attachment paths, so a failure here must not fail the node.
            log.warn(
                    "Failed to resolve routing-gate artifacts for node execution {}: {}", exec.getId(), e.getMessage());
        }
    }

    private boolean traversedAny(NodeExecution exec, Set<UUID> edgeIds) {
        UUID[] traversed = exec.getTraversedEdgeIds();
        if (traversed == null) {
            return false;
        }
        for (UUID id : traversed) {
            if (edgeIds.contains(id)) {
                return true;
            }
        }
        return false;
    }

    private String readRef(String artifactRefsJson, String key) {
        Map<String, String> refs = readAllRefs(artifactRefsJson);
        return refs.get(key);
    }

    private Map<String, String> readAllRefs(String artifactRefsJson) {
        if (artifactRefsJson == null || artifactRefsJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(artifactRefsJson);
            if (!node.isObject()) {
                return Map.of();
            }
            Map<String, String> refs = new LinkedHashMap<>();
            node.fields().forEachRemaining(f -> {
                if (f.getValue().isTextual()) {
                    refs.put(f.getKey(), f.getValue().asText());
                }
            });
            return refs;
        } catch (Exception e) {
            log.warn("Skipping unparseable artifact_refs: {}", e.getMessage());
            return Map.of();
        }
    }
}
