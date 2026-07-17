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
    static final int VERSION = 12;

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
              reviewer will approve or reject your recommendations in the next step,
              and only then will approved features be created by a separate node.

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
            proposed features, each with the five sections above.""";

    private static final String FEATURE_CREATOR_PROMPT = """
            You are creating roadmap work items based on an approved analysis. Each
            approved feature becomes a fully startable Epic -> Story -> Task chain:
            one Epic, containing one Story, containing one Task (the same 1:1
            decomposition depth used today — see Caveat 1 of the work-hierarchy spec).

            Approved analysis:
            {input.roadmap_analyzer.result}

            Repositories are cloned under /workspace/repo/<name>/.

            For each approved feature in the analysis, run all three CLIs in
            sequence — create-proposal, then create-story, then create-task:

              1. create-proposal --title "Feature title" --description "Detailed description" --motivation "Why this matters"
                 Creates the Epic. Capture its "id" from the JSON response.

              2. create-story --epic-id <epic-id-from-step-1> --title "Feature title" --description "Detailed description"
                 Creates a Story under that Epic. Capture its "id" from the JSON response.

              3. create-task --epic-id <epic-id-from-step-1> --story-id <story-id-from-step-2> --title "Feature title" --description "Detailed description"
                 Creates a Task under that Story. This is the level a human later
                 starts as a workflow run — without this step the feature is
                 recorded but not yet startable.

            IMPORTANT:
            - Always use the create-proposal, create-story, and create-task CLIs.
              Do NOT call the API directly.
            - The Epic's --description MUST include the user story AND acceptance
              criteria from the analysis. Format them clearly with markdown headings.
              Reuse the same description text for the Story and Task unless the
              analysis suggests a more specific breakdown.
            - The Epic's --motivation MUST focus on user impact and business value,
              NOT on technical benefits like "cleaner architecture" or "better performance".
            - Do NOT add implementation details, technology choices, or architectural
              suggestions to any description or motivation. These will be determined
              by the Feature Development workflow.
            - create-story and create-task do not take a --motivation flag — only
              the Epic (create-proposal) carries motivation (Decision 4 of the
              work-hierarchy spec: software_project_id and motivation live on Epic).
            - An Epic can span ONE or TWO repositories. If the approved analysis
              identifies a feature that clearly needs changes in two repos (for
              example, "add push notifications" requiring backend + frontend changes),
              pass `--repo` twice on the create-proposal call:
                create-proposal --title "..." --description "..." --motivation "..." \\
                  --repo <primary-repo-name> --repo <secondary-repo-name>
              Repo names are the subdirectory names under /workspace/repo/<name>/ and
              are listed under `repos[]` in /workspace/config.json.
              If unsure, default to a single --repo (the primary repo, same as today).
            - Never pass more than two --repo arguments.

            Steps:
            1. Parse the approved features from the analysis
            2. For each feature, compose a description that includes:
               - The user story (As a... I want... So that...)
               - The acceptance criteria (Given/When/Then)
            3. Create the Epic via create-proposal, then the Story via create-story,
               then the Task via create-task, threading the ids returned by each
               step into the next
            4. Verify each of the three creations was successful (exit code 0, JSON response)
            5. Save a summary of created Epics/Stories/Tasks as /workspace/out/feature_summary.md""";

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
                "{\"files\":[{\"name\":\"roadmap_analysis.md\",\"required\":true,\"description\":\"Analysis of roadmap proposals for human review\"}]}");
        nodeDefRepo.save(analyzer);

        NodeDefinition humanGate = createNodeDef("Roadmap Human Gate", ExecutorType.human, null, 86400);

        NodeDefinition featureCreator =
                createNodeDef("Roadmap Feature Creator", ExecutorType.ai, FEATURE_CREATOR_PROMPT, 1800);
        featureCreator.setOutputSpec(
                "{\"files\":[{\"name\":\"feature_summary.md\",\"required\":true,\"description\":\"Summary of features created from proposals\"}]}");
        nodeDefRepo.save(featureCreator);

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
        //   [Analyzer] → [Human Gate] → [Feature Creator]
        //   (x=50)       (x=350)        (x=650)
        //   All at y=150 for a single-row layout
        TemplateNode tnAnalyzer =
                createNode(template, analyzer, "roadmap_analyzer", true, "{\"loop_group\": \"proposal-review\"}");
        TemplateNode tnHumanGate = createNode(
                template,
                humanGate,
                "roadmap_human_gate",
                false,
                "{\"loop_group\": \"proposal-review\"}",
                "[{\"template_node_label\":\"roadmap_analyzer\",\"artifacts\":[{\"name\":\"roadmap_analysis.md\",\"description\":\"Analysis of roadmap proposals for human review\"}]}]");
        TemplateNode tnFeatureCreator = createNode(template, featureCreator, "roadmap_feature_creator", false, "{}");

        // Create edges
        // Analyzer → Human Gate (unconditional)
        createEdge(template, tnAnalyzer, tnHumanGate, null);
        // Human Gate → Feature Creator (approved)
        createEdge(template, tnHumanGate, tnFeatureCreator, "approved");
        // Human Gate → Analyzer (rejected — loops back with review history)
        createEdge(template, tnHumanGate, tnAnalyzer, "rejected");

        log.info(
                "BaseRoadmapProvisionerSeeder: seeded template graphId='{}' v{}: 3 node definitions, 3 template nodes, 3 edges",
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
