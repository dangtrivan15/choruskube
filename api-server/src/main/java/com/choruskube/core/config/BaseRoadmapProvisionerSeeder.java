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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(3)
public class BaseRoadmapProvisionerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaseRoadmapProvisionerSeeder.class);

    static final String GRAPH_ID = GraphIds.ROADMAP_PROVISIONER;
    static final int VERSION = 13;

    private static final String TEMPLATE_NAME = "Roadmap Provisioner";

    private static final String INPUT_SCHEMA = """
            [
              {"name":"software_project_id","label":"Software Project","type":"software_project_id","required":true},
              {"name":"project_context","label":"Project Context","type":"textarea","required":false,"default":""}
            ]
            """;

    private static final String ANALYZER_PROMPT = """
            You are a product-minded analyst discovering features for a project's roadmap.
            Your goal is to identify user-facing improvements framed as Agile Epics or
            User Stories — NOT technical implementation tasks.

            Project context (may be empty — if so, discover project details from the codebase):
            {run.project_context}

            Repositories are cloned under /workspace/repo/<name>/ — one subdirectory per
            repo in this run. Discover them by listing that directory. Per-repo metadata
            is available in /workspace/config.json under the "repos" array. When repos
            are independent, you may explore them in parallel by dispatching Task
            subagents — one per repo — and consolidating their findings.

            Read the codebase(s) to understand the project's purpose, who its users are,
            what workflows it supports, and where the user experience has gaps or
            friction.

            Retrieve the current feature proposals using the CLI:
              list-proposals

            IMPORTANT — YOUR ROLE IS ANALYSIS ONLY:
            - You MUST NOT create, modify, or delete any proposals. Do NOT use the
              create-proposal CLI or call any API endpoints that mutate state.
            - Your sole output is a written analysis recommending features. A human
              reviewer will approve or reject your recommendations in the next step;
              only on approval are roadmap items created, deterministically, from
              the structured breakdown described below — not by a separate AI step.

            Your task:
            - Identify 3-5 high-value features that are not already proposed
            - Frame each feature from the USER'S perspective, not the developer's
            - For each feature, provide ALL of the following:

              1. **Title**: A concise, user-facing name (e.g., "Bulk Team Member Import",
                 not "Add CSV parsing endpoint")
              2. **User Story**: "As a [persona], I want [capability], so that [benefit]"
              3. **Acceptance Criteria**: 3-5 testable conditions using Given/When/Then
                 format that define when the feature is complete
              4. **Motivation**: Why this feature matters — who benefits, what pain it
                 solves, what business value it delivers
              5. **Priority**: High / Medium / Low with a brief justification based on
                 user impact

            DO NOT prescribe implementation details, technology choices, or architectural
            decisions. Focus on WHAT the user needs and WHY, not HOW to build it.
            The implementation approach will be determined by a separate workflow.

            BAD example: "Add a Redis cache layer for API responses to reduce latency"
            GOOD example: "As a user, I want pages to load in under 2 seconds, so that
            I can work efficiently without waiting"

            {review_history}

            Save your analysis as /workspace/out/roadmap_analysis.md with a structured list of
            proposed features, each with the five sections above.

            IN ADDITION, save a structured candidate breakdown as
            /workspace/out/roadmap_candidates.json — a JSON array of candidate Epics,
            each with a variable number of Stories, each with a variable number of
            Tasks, reflecting an ACTUAL decomposition of that feature (not a
            mechanical 1:1 wrapper — a small feature might be one Story with one
            Task; a larger one might be three Stories with two or three Tasks each).
            This is what a human reviewer will see and edit before approving — it is
            the authoritative structured form of your analysis, not a duplicate of
            the markdown.

            Each element of the array must match this shape exactly:
              {
                "title": "Concise, user-facing Epic title",
                "description": "The user story AND acceptance criteria, in markdown",
                "motivation": "Why this matters — user impact and business value only",
                "repos": ["repo-name", ...],
                "priority": "High" | "Medium" | "Low",
                "stories": [
                  {
                    "title": "Story title",
                    "description": "Story description",
                    "tasks": [
                      {"title": "Task title", "description": "Task description"}
                    ]
                  }
                ]
              }

            Rules for roadmap_candidates.json:
            - One array element per proposed feature (same features as the markdown).
            - "repos" and "priority" are reviewer context only (which repos this
              feature likely touches, and a rough triage signal) — they are not
              created as roadmap fields, so keep them brief.
            - At most 8 Stories per Epic, and at most 8 Tasks per Story. If a feature
              needs more, it's a sign it should be split into two candidate Epics
              instead of one deeply nested one.
            - Every Epic needs at least one Story, and every Story needs at least
              one Task, so the result is startable end-to-end.
            - Do NOT add implementation details, technology choices, or architectural
              suggestions to any description or motivation — same rule as the
              markdown analysis.
            - The file must be valid JSON (a top-level array) and nothing else.""";

    private final GraphTemplateRepository templateRepo;
    private final NodeDefinitionRepository nodeDefRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final TemplateEdgeRepository edgeRepo;
    private final ObjectMapper objectMapper;

    public BaseRoadmapProvisionerSeeder(
            GraphTemplateRepository templateRepo,
            NodeDefinitionRepository nodeDefRepo,
            TemplateNodeRepository templateNodeRepo,
            TemplateEdgeRepository edgeRepo,
            ObjectMapper objectMapper) {
        this.templateRepo = templateRepo;
        this.nodeDefRepo = nodeDefRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.edgeRepo = edgeRepo;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        var existing = templateRepo.findByGraphIdAndVersion(GRAPH_ID, VERSION);
        if (existing.isPresent()) {
            var stored = objectMapper.readValue(existing.get().getInputSchema(), new TypeReference<Object>() {});
            var expected = objectMapper.readValue(INPUT_SCHEMA, new TypeReference<Object>() {});
            if (!stored.equals(expected)) {
                throw new IllegalStateException("BaseRoadmapProvisionerSeeder: inputSchema has diverged for graphId='"
                        + GRAPH_ID + "' version=" + VERSION
                        + ". Update the version or reconcile the schema.");
            }
            log.info(
                    "BaseRoadmapProvisionerSeeder: template graphId='{}' v{} already exists — skipping seed",
                    GRAPH_ID,
                    VERSION);
            return;
        }

        log.info("BaseRoadmapProvisionerSeeder: seeding template graphId='{}' v{}", GRAPH_ID, VERSION);

        seedTemplate();
    }

    private void seedTemplate() {
        // Create node definitions
        NodeDefinition analyzer = createNodeDef("Roadmap Analyzer", ExecutorType.ai, ANALYZER_PROMPT, 1800);
        analyzer.setOutputSpec(
                "{\"files\":[{\"name\":\"roadmap_analysis.md\",\"required\":true,\"description\":\"Analysis of roadmap proposals for human review\"},"
                        + "{\"name\":\"roadmap_candidates.json\",\"required\":true,\"description\":\"Structured candidate Epic/Story/Task breakdown for human review\"}]}");
        nodeDefRepo.save(analyzer);

        NodeDefinition humanGate = createNodeDef("Roadmap Human Gate", ExecutorType.human, null, 86400);

        // Create template
        GraphTemplate template = new GraphTemplate();
        template.setGraphId(GRAPH_ID);
        template.setVersion(VERSION);
        template.setName(TEMPLATE_NAME);
        template.setDescription("Automated feature discovery, human review, and roadmap population workflow");
        template.setInputSchema(INPUT_SCHEMA);
        template.setSystem(true);
        template = templateRepo.save(template);

        // Create template nodes in a horizontal pipeline layout
        //   [Analyzer] → [Human Gate]
        //   (x=50)       (x=350)
        //   Both at y=150 for a single-row layout. There is no third "Feature
        //   Creator" node in v13 (Decision 2/3): "approved" is a terminal
        //   decision on the Human Gate — the API server materializes the
        //   reviewed candidate breakdown itself, in the same request that
        //   handles the decision signal, instead of handing off to a second
        //   AI agent.
        TemplateNode tnAnalyzer =
                createNode(template, analyzer, "roadmap_analyzer", true, "{\"loop_group\": \"proposal-review\"}");
        TemplateNode tnHumanGate = createNode(
                template,
                humanGate,
                "roadmap_human_gate",
                false,
                "{\"loop_group\": \"proposal-review\",\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_candidates\"}",
                "[{\"template_node_label\":\"roadmap_analyzer\",\"artifacts\":[{\"name\":\"roadmap_analysis.md\",\"description\":\"Analysis of roadmap proposals for human review\"},"
                        + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Structured candidate Epic/Story/Task breakdown for human review\"}]}]");

        // Create edges
        // Analyzer → Human Gate (unconditional)
        createEdge(template, tnAnalyzer, tnHumanGate, null);
        // Human Gate → Analyzer (rejected — loops back with review history)
        createEdge(template, tnHumanGate, tnAnalyzer, "rejected");
        // Human Gate "approved" has no outgoing edge — it's a terminal_decisions
        // entry (Decision 2) instead, so the run completes right here.

        log.info(
                "BaseRoadmapProvisionerSeeder: seeded template graphId='{}' v{}: 2 node definitions, 2 template nodes, 2 edges",
                GRAPH_ID,
                VERSION);
    }

    private NodeDefinition createNodeDef(
            String name, ExecutorType executorType, String promptTemplate, int timeoutSeconds) {
        NodeDefinition nd = new NodeDefinition();
        nd.setName(name);
        nd.setExecutorType(executorType);
        nd.setPromptTemplate(promptTemplate);
        nd.setTimeoutSeconds(timeoutSeconds);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        return nodeDefRepo.save(nd);
    }

    private TemplateNode createNode(
            GraphTemplate template, NodeDefinition nd, String label, boolean entrypoint, String configOverrides) {
        return createNode(template, nd, label, entrypoint, configOverrides, null);
    }

    private TemplateNode createNode(
            GraphTemplate template,
            NodeDefinition nd,
            String label,
            boolean entrypoint,
            String configOverrides,
            String requiredInputArtifacts) {
        TemplateNode tn = new TemplateNode();
        tn.setGraphTemplateId(template.getId());
        tn.setNodeDefinitionId(nd.getId());
        tn.setLabel(label);
        tn.setEntrypoint(entrypoint);
        tn.setConfigOverrides(configOverrides);
        tn.setRequiredInputArtifacts(requiredInputArtifacts);
        return templateNodeRepo.save(tn);
    }

    private TemplateEdge createEdge(
            GraphTemplate template, TemplateNode source, TemplateNode target, String condition) {
        TemplateEdge te = new TemplateEdge();
        te.setGraphTemplateId(template.getId());
        te.setSourceNodeId(source.getId());
        te.setTargetNodeId(target.getId());
        te.setCondition(condition);
        return edgeRepo.save(te);
    }
}
