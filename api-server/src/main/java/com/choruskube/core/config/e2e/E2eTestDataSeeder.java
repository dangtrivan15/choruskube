package com.choruskube.core.config.e2e;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.service.RepoGroupService;
import com.choruskube.core.util.RepoNameUtil;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds mock node definitions and E2E test graph templates when the "e2e" profile is active.
 *
 * <p>This replaces the shell-based {@code e2e/setup-test-data.sh} template provisioning that
 * previously called the now-removed mutation endpoints on GraphTemplateController.
 *
 * <p>Templates seeded: linear pipeline, parallel fan-out, human gate, conditional routing,
 * retry loop. Each uses mock node definitions with configurable commands.
 */
@Component
@Profile("e2e")
@Order(10)
public class E2eTestDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(E2eTestDataSeeder.class);

    private static final String GRAPH_ID_LINEAR = "e2e-linear-pipeline";
    private static final String GRAPH_ID_PARALLEL = "e2e-parallel-fanout";
    private static final String GRAPH_ID_GATE = "e2e-human-gate";
    private static final String GRAPH_ID_CONDITIONAL = "e2e-conditional-routing";
    private static final String GRAPH_ID_RETRY = "e2e-retry-loop";
    private static final String GRAPH_ID_MULTI_REPO = "e2e-multi-repo-pipeline";
    private static final String GRAPH_ID_DIND = "e2e-dind-isolation";
    // failure_pipeline: a single entrypoint node that deterministically exits 1, so the
    // run + node both reach "failed" — exercises failure-status rendering (failure-handling.spec.ts).
    private static final String GRAPH_ID_FAILURE = "e2e-failure-pipeline";
    // spec_and_plan_gate: a v23 spec gate whose outgoing edges (approved/rereview/redraft)
    // drive the three-button decision UI (human-gates.spec.ts v23 case).
    private static final String GRAPH_ID_SPEC_GATE = "e2e-spec-and-plan-gate";
    // review_conflict_gate: exercises self-detected review-conflict escalation (script node
    // self-escalates via need_human_decision:review_conflict after a fixed number of
    // iterations) and human escalation gate with rereview back-edge
    // (self-detected-review-conflict feature).
    private static final String GRAPH_ID_REVIEW_CONFLICT_GATE = "e2e-review-conflict-gate";
    // roadmap_candidate_gate: mirrors BaseRoadmapProvisionerSeeder's v13 production shape
    // (analyzer -> human gate with terminal_decisions + materialize) using a script node
    // in place of the real AI analyzer, so roadmap-candidate-gate.spec.ts can exercise the
    // structured candidate-breakdown gate (Decisions 1-5) without a live Claude call.
    private static final String GRAPH_ID_ROADMAP_CANDIDATE_GATE = "e2e-roadmap-candidate-gate";
    // many_artifacts: single-node template whose entrypoint writes 40 small output files
    // via mock-agent.sh's "many_artifacts" scenario, for artifact-viewer-layout.spec.ts's
    // "content pane collapses when a node has many files" regression coverage.
    private static final String GRAPH_ID_MANY_ARTIFACTS = "e2e-many-artifacts";

    // Bumped to 3 so step_2's requiredInputArtifacts declaration re-seeds — run() early-returns
    // when a template at the current VERSION already exists, so an edit without a bump is a no-op
    // against any environment whose database survived the previous boot.
    private static final int VERSION = 3;

    private static final String E2E_REPO_URL = "https://github.com/e2e-test/mock-repo";
    private static final String E2E_SECONDARY_REPO_URL = "https://github.com/e2e-test/mock-frontend";
    private static final String E2E_DIND_REPO_URL = "https://github.com/e2e-test/dind-repo";

    private final GraphTemplateRepository templateRepo;
    private final NodeDefinitionRepository nodeDefRepo;
    private final TemplateNodeRepository templateNodeRepo;
    private final TemplateEdgeRepository edgeRepo;
    private final GitRepoRepository gitRepoRepo;
    private final RepoGroupService repoGroupService;

    @Value("${E2E_AGENT_IMAGE}")
    private String agentImage;

    @Value("${E2E_MOCK_SCRIPT_PATH:/workspace/repo/scripts/mock-agent.sh}")
    private String mockScriptPath;

    public E2eTestDataSeeder(
            GraphTemplateRepository templateRepo,
            NodeDefinitionRepository nodeDefRepo,
            TemplateNodeRepository templateNodeRepo,
            TemplateEdgeRepository edgeRepo,
            GitRepoRepository gitRepoRepo,
            RepoGroupService repoGroupService) {
        this.templateRepo = templateRepo;
        this.nodeDefRepo = nodeDefRepo;
        this.templateNodeRepo = templateNodeRepo;
        this.edgeRepo = edgeRepo;
        this.gitRepoRepo = gitRepoRepo;
        this.repoGroupService = repoGroupService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (templateRepo.findByGraphIdAndVersion(GRAPH_ID_LINEAR, VERSION).isPresent()) {
            log.info("E2eTestDataSeeder: E2E templates already exist — skipping seed");
            return;
        }

        log.info("E2eTestDataSeeder: seeding E2E test data (agentImage={}, mockScript={})", agentImage, mockScriptPath);

        // Create mock git repos for proposal / run tests (both seeded up-front so
        // downstream seed steps — e.g. the demo RepoGroup — can reference them).
        seedGitRepo();
        seedSecondaryGitRepo();

        // Seed a 2-repo SoftwareProject (RepoGroup) referencing both repos above,
        // so E2E flows can exercise the SoftwareProject hierarchy dispatch path.
        seedDemoRepoGroup();

        // Create shared mock node definitions
        NodeDefinition mockSuccess = createNodeDef("mock-success", ExecutorType.script, null, 300);
        NodeDefinition mockFailure = createNodeDef("mock-failure", ExecutorType.script, null, 300);
        NodeDefinition mockTimeout = createNodeDef("mock-timeout", ExecutorType.script, null, 120);
        NodeDefinition mockSlow = createNodeDef("mock-slow", ExecutorType.script, null, 600);
        NodeDefinition mockFlaky = createNodeDef("mock-flaky", ExecutorType.script, null, 300);
        NodeDefinition mockGate = createNodeDef("mock-gate", ExecutorType.human, null, 1800);

        seedLinearPipeline(mockSuccess);
        seedParallelFanout(mockSuccess, mockSlow);
        seedHumanGate(mockSuccess, mockGate);
        seedConditionalRouting(mockSuccess);
        seedRetryLoop(mockSuccess, mockFlaky);
        // A script node cannot fail at the status level — its exit code becomes a routing
        // decision (see entrypoint.sh). To drive a REAL failed node we use a node that hangs
        // and is killed by its node timeout: the executor activity times out, the node reaches
        // status "failed" with an errorMessage, and the run parks in awaiting_retry. 60s is the
        // floor allowed by the chk_timeout_seconds constraint (V14: 0 or 60..86400).
        NodeDefinition mockHang = createNodeDef("mock-hang", ExecutorType.script, null, 60);
        seedFailurePipeline(mockHang);
        seedSpecAndPlanGate(mockSuccess, mockGate);

        // Multi-repo template (uses repos seeded above)
        seedMultiRepoPipeline(mockSuccess, mockGate);

        // DinD isolation template (uses a dedicated git repo with enableDocker=true)
        seedDindGitRepo();
        NodeDefinition mockDindIsolation = createNodeDef("mock-dind-isolation", ExecutorType.script, null, 120);
        NodeDefinition mockDindNetwork = createNodeDef("mock-dind-network", ExecutorType.script, null, 120);
        seedDindIsolationTemplate(mockDindIsolation, mockDindNetwork);

        // Self-escalating review + human gate template: script node deterministically
        // submits "revised" for a fixed number of iterations, then self-escalates via
        // need_human_decision:review_conflict, routing to the human gate. Using
        // ExecutorType.script avoids launching real Claude pods in E2E while still
        // exercising the self-detected-conflict escalation path end-to-end.
        NodeDefinition mockAiSelfEscalating = createNodeDef("mock-ai-self-escalating", ExecutorType.script, null, 1800);
        seedReviewConflictHumanGateTemplate(mockAiSelfEscalating, mockGate, mockSuccess);

        seedRoadmapCandidateGate(mockSuccess, mockGate);

        seedManyArtifacts(mockSuccess);

        log.info("E2eTestDataSeeder: seeded 3 git repos, 1 repo group, 11 node definitions, and 12 E2E templates");
    }

    private void seedDemoRepoGroup() {
        // The group is part of the run-once seed (gated by the LINEAR template check at run() entry),
        // so don't add a per-name idempotency guard here.
        UUID r1 = gitRepoRepo
                .findByUrl(E2E_REPO_URL)
                .orElseThrow(() -> new IllegalStateException("seedGitRepo() must run before seedDemoRepoGroup()"))
                .getId();
        UUID r2 = gitRepoRepo
                .findByUrl(E2E_SECONDARY_REPO_URL)
                .orElseThrow(
                        () -> new IllegalStateException("seedSecondaryGitRepo() must run before seedDemoRepoGroup()"))
                .getId();
        repoGroupService.createInternal(
                "demo-stack",
                agentImage,
                "Demo two-repo project for E2E SoftwareProject hierarchy coverage",
                java.util.List.of(r1, r2));
    }

    private void seedGitRepo() {
        if (gitRepoRepo.findByUrl(E2E_REPO_URL).isPresent()) return;
        GitRepo repo = new GitRepo();
        repo.setUrl(E2E_REPO_URL);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(E2E_REPO_URL));
        repo.setDefaultBranch("main");
        repo.setSecrets("[]");
        gitRepoRepo.save(repo);
    }

    private void seedSecondaryGitRepo() {
        if (gitRepoRepo.findByUrl(E2E_SECONDARY_REPO_URL).isPresent()) return;
        GitRepo repo = new GitRepo();
        repo.setUrl(E2E_SECONDARY_REPO_URL);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(E2E_SECONDARY_REPO_URL));
        repo.setDefaultBranch("main");
        repo.setSecrets("[]");
        gitRepoRepo.save(repo);
    }

    private void seedDindGitRepo() {
        if (gitRepoRepo.findByUrl(E2E_DIND_REPO_URL).isPresent()) return;
        GitRepo repo = new GitRepo();
        repo.setUrl(E2E_DIND_REPO_URL);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(E2E_DIND_REPO_URL));
        repo.setDefaultBranch("main");
        repo.setSecrets("[]");
        repo.setEnableDocker(true);
        gitRepoRepo.save(repo);
    }

    // --- DinD Isolation Pipeline: dind-isolation-step -> dind-network-step ---

    private void seedDindIsolationTemplate(NodeDefinition mockDindIsolation, NodeDefinition mockDindNetwork) {
        String inputSchema =
                "[{\"name\":\"git_repo_id\",\"label\":\"Git Repository\",\"type\":\"git_repo_id\",\"required\":true}]";

        GraphTemplate t = new GraphTemplate();
        t.setGraphId(GRAPH_ID_DIND);
        t.setVersion(VERSION);
        t.setName("e2e-dind-isolation");
        t.setDescription(
                "E2E test: DinD isolation verification — checks docker ps isolation and API server reachability");
        t.setInputSchema(inputSchema);
        t.setSystem(false);
        t = templateRepo.save(t);

        TemplateNode isolationStep =
                createNode(t, mockDindIsolation, "dind-isolation-step", true, cmd("dind_isolation"));
        TemplateNode networkStep =
                createNode(t, mockDindNetwork, "dind-network-step", false, cmd("dind_network_connectivity"));

        createEdge(t, isolationStep, networkStep, null);
    }

    // --- Multi-Repo Pipeline: spec -> review -> [impl_repo_1 || impl_repo_2] -> code_review -> create_prs ---

    private void seedMultiRepoPipeline(NodeDefinition mockSuccess, NodeDefinition mockGate) {
        String inputSchema =
                "[{\"name\":\"software_project_id\",\"label\":\"Software Project\",\"type\":\"software_project_id\",\"required\":true},"
                        + "{\"name\":\"feature_request\",\"label\":\"Feature Description\",\"type\":\"textarea\",\"required\":true,\"default\":\"Test multi-repo feature\"}]";

        GraphTemplate t = new GraphTemplate();
        t.setGraphId(GRAPH_ID_MULTI_REPO);
        t.setVersion(VERSION);
        t.setName("e2e-multi-repo-pipeline");
        t.setDescription("E2E test: multi-repo pipeline with parallel implementation and PR creation");
        t.setInputSchema(inputSchema);
        t.setSystem(false);
        t = templateRepo.save(t);

        TemplateNode specNode = createNode(t, mockSuccess, "spec_node", true, cmd("success --artifact spec-done"));
        TemplateNode reviewGate = createNode(
                t,
                mockGate,
                "review_gate",
                false,
                "{}",
                "[{\"template_node_label\":\"spec_node\",\"artifacts\":[{\"name\":\"spec-done\",\"description\":\"Mock spec artifact\"}]}]");
        TemplateNode implRepo1 = createNode(
                t,
                mockSuccess,
                "implement_repo_1",
                false,
                "{\"command\": \""
                        + mockScriptPath
                        + " success --artifact impl-repo1-done\", \"needs_branch\": \"true\"}");
        TemplateNode implRepo2 = createNode(
                t,
                mockSuccess,
                "implement_repo_2",
                false,
                "{\"command\": \""
                        + mockScriptPath
                        + " success --artifact impl-repo2-done\", \"needs_branch\": \"true\"}");
        TemplateNode codeReview = createNode(
                t,
                mockGate,
                "code_review",
                false,
                "{}",
                "[{\"template_node_label\":\"implement_repo_1\",\"artifacts\":[{\"name\":\"impl-repo1-done\",\"description\":\"Mock repo-1 implementation artifact\"}]},{\"template_node_label\":\"implement_repo_2\",\"artifacts\":[{\"name\":\"impl-repo2-done\",\"description\":\"Mock repo-2 implementation artifact\"}]}]");
        TemplateNode createPrs = createNode(t, mockSuccess, "create_prs", false, cmd("multi_repo_pr"));

        // Edges — fan-out to parallel implementation, fan-in to review
        createEdge(t, specNode, reviewGate, null);
        createEdge(t, reviewGate, implRepo1, "approved");
        createEdge(t, reviewGate, implRepo2, "approved");
        createEdge(t, reviewGate, specNode, "rejected");
        createEdge(t, implRepo1, codeReview, null); // fan-in
        createEdge(t, implRepo2, codeReview, null); // fan-in
        createEdge(t, codeReview, createPrs, "approved");
        createEdge(t, codeReview, implRepo1, "rejected");
        createEdge(t, codeReview, implRepo2, "rejected");
    }

    // --- Linear Pipeline: step_1 -> step_2 -> step_3 ---

    private void seedLinearPipeline(NodeDefinition mockSuccess) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_LINEAR,
                "e2e-linear-pipeline",
                "E2E test: three sequential mock-success nodes; step_2 asserts step_1's artifact "
                        + "was materialised under /workspace/in");

        TemplateNode step1 = createNode(t, mockSuccess, "step_1", true, cmd("success --artifact step-1-done"));
        // step_2 declares step_1's output and asserts it landed on disk. Without this, the whole
        // required_input_artifacts path — manifest resolution, config.json plumbing, the
        // entrypoint's download loop — runs as a no-op in E2E and can regress unnoticed.
        TemplateNode step2 = createNode(
                t,
                mockSuccess,
                "step_2",
                false,
                cmd("success --artifact step-2-done --expect-input step_1/step-1-done"),
                "[{\"template_node_label\":\"step_1\",\"artifacts\":[{\"name\":\"step-1-done\","
                        + "\"description\":\"step_1's output artifact\",\"required\":true}]}]");
        TemplateNode step3 = createNode(t, mockSuccess, "step_3", false, cmd("success --artifact step-3-done"));

        createEdge(t, step1, step2, null);
        createEdge(t, step2, step3, null);
    }

    // --- Parallel Fan-out: start -> {branch_a, branch_b, branch_c} -> merge ---

    private void seedParallelFanout(NodeDefinition mockSuccess, NodeDefinition mockSlow) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_PARALLEL, "e2e-parallel-fanout", "E2E test: fan-out to 3 branches then fan-in merge");

        TemplateNode start = createNode(t, mockSuccess, "start", true, cmd("success --artifact start-done"));
        TemplateNode branchA = createNode(t, mockSuccess, "branch_a", false, cmd("success --artifact branch-a-done"));
        TemplateNode branchB =
                createNode(t, mockSlow, "branch_b", false, cmd("slow --delay 5 --artifact branch-b-done"));
        TemplateNode branchC = createNode(t, mockSuccess, "branch_c", false, cmd("success --artifact branch-c-done"));
        TemplateNode merge = createNode(t, mockSuccess, "merge", false, cmd("success --artifact merge-done"));

        createEdge(t, start, branchA, null);
        createEdge(t, start, branchB, null);
        createEdge(t, start, branchC, null);
        createEdge(t, branchA, merge, null);
        createEdge(t, branchB, merge, null);
        createEdge(t, branchC, merge, null);
    }

    // --- Human Gate: draft -> review_gate --(approved)--> publish / --(rejected)--> revise ---

    private void seedHumanGate(NodeDefinition mockSuccess, NodeDefinition mockGate) {
        GraphTemplate t =
                createTemplate(GRAPH_ID_GATE, "e2e-human-gate", "E2E test: human gate with approved/rejected routing");

        TemplateNode draft = createNode(t, mockSuccess, "draft", true, cmd("success --artifact draft-output"));
        TemplateNode gate = createNode(
                t,
                mockGate,
                "review_gate",
                false,
                "{}",
                "[{\"template_node_label\":\"draft\",\"artifacts\":[{\"name\":\"draft-output\",\"description\":\"Mock draft artifact\"}]}]");
        TemplateNode publish = createNode(t, mockSuccess, "publish", false, cmd("success --artifact published"));
        TemplateNode revise = createNode(t, mockSuccess, "revise", false, cmd("success --artifact revision-needed"));

        createEdge(t, draft, gate, null);
        createEdge(t, gate, publish, "approved");
        createEdge(t, gate, revise, "rejected");
    }

    // --- Conditional Routing: analyze -> router --(passed)--> deploy / --(failed)--> fix ---

    private void seedConditionalRouting(NodeDefinition mockSuccess) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_CONDITIONAL,
                "e2e-conditional-routing",
                "E2E test: script auto-decision conditional routing (passed/failed)");

        TemplateNode analyze = createNode(t, mockSuccess, "analyze", true, cmd("success --artifact analysis-done"));
        TemplateNode router = createNode(t, mockSuccess, "router", false, cmd("success --artifact router-passed"));
        TemplateNode deploy = createNode(t, mockSuccess, "deploy", false, cmd("success --artifact deployed"));
        TemplateNode fix = createNode(t, mockSuccess, "fix", false, cmd("success --artifact fix-applied"));

        createEdge(t, analyze, router, null);
        createEdge(t, router, deploy, "passed");
        createEdge(t, router, fix, "failed");
    }

    // --- Retry Loop: start -> flaky_task --(passed)--> done / --(failed)--> flaky_task ---

    private void seedRetryLoop(NodeDefinition mockSuccess, NodeDefinition mockFlaky) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_RETRY, "e2e-retry-loop", "E2E test: retry loop with flaky task that succeeds on 3rd attempt");

        TemplateNode start = createNode(t, mockSuccess, "start", true, cmd("success --artifact start-done"));
        TemplateNode flaky = createNode(t, mockFlaky, "flaky_task", false, cmd("flaky --succeed-after 3"));
        TemplateNode done = createNode(t, mockSuccess, "done", false, cmd("success --artifact all-done"));

        createEdge(t, start, flaky, null);
        createEdge(t, flaky, flaky, "failed");
        createEdge(t, flaky, done, "passed");
    }

    // --- Failure Pipeline: a single entrypoint node that hangs until its node timeout ---
    //
    // The node runs `mock-agent.sh timeout` (sleeps forever). Its node timeout is 60s, so the
    // executor activity times out, the orchestrator marks the node "failed" (with an
    // errorMessage), and the run parks in "awaiting_retry" (dag_executor offers a 7-day retry
    // window on any failed node). Deterministic: MaximumAttempts is 1, so no retries fire.

    private void seedFailurePipeline(NodeDefinition mockHang) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_FAILURE,
                "e2e-failure-pipeline",
                "E2E test: entrypoint node hangs and is killed by its 60s node timeout — node status "
                        + "'failed', run parks in awaiting_retry");

        createNode(t, mockHang, "failing_step", true, cmd("timeout"));
    }

    // --- Spec-and-Plan Gate (v23): draft -> approve_spec_and_plan
    //       --(approved)--> implement
    //       --(rereview)--> draft (back-edge)
    //       --(redraft)--> draft  (back-edge)
    //
    // The three outgoing edge conditions are what PendingGateService surfaces as
    // decisionOptions, which makes the approvals UI render Approve / Re-review / Redraft
    // (and NO Reject) — the v23 spec-gate contract under test.

    private void seedSpecAndPlanGate(NodeDefinition mockSuccess, NodeDefinition mockGate) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_SPEC_GATE,
                "e2e-spec-and-plan-gate",
                "E2E test: v23 spec-and-plan gate with approved/rereview/redraft decisions");

        TemplateNode draft =
                createNode(t, mockSuccess, "draft_spec_and_plan", true, cmd("success --artifact spec-and-plan"));
        TemplateNode gate = createNode(
                t,
                mockGate,
                "approve_spec_and_plan",
                false,
                "{}",
                "[{\"template_node_label\":\"draft_spec_and_plan\",\"artifacts\":[{\"name\":\"spec-and-plan\",\"description\":\"Mock spec-and-plan artifact\"}]}]");
        TemplateNode implement = createNode(t, mockSuccess, "implement", false, cmd("success --artifact implemented"));

        createEdge(t, draft, gate, null);
        createEdge(t, gate, implement, "approved");
        createEdge(t, gate, draft, "rereview");
        createEdge(t, gate, draft, "redraft");
    }

    // --- Review Conflict + Human Gate: ai_review --(revised)--> ai_review (self-loop)
    //                                --(approved)--> done
    //                                --(need_human_decision:review_conflict)--> human_gate
    //                       human_gate --(rereview)--> ai_review
    //                                  --(approved)--> done

    private void seedReviewConflictHumanGateTemplate(
            NodeDefinition mockAiSelfEscalating, NodeDefinition mockGate, NodeDefinition mockSuccess) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_REVIEW_CONFLICT_GATE,
                "e2e-review-conflict-gate",
                "E2E test: self-detected review-conflict escalation with human escalation gate and rereview back-edge");

        // gate_approve --decision revised --escalate-after 3 --escalate-decision
        // need_human_decision:review_conflict submits "revised" for the first 2 iterations,
        // then self-escalates to need_human_decision:review_conflict on the 3rd — the key
        // behavior under test (the reviewer, not the API server, decides to escalate).
        TemplateNode aiReview = createNode(
                t,
                mockAiSelfEscalating,
                "ai_review",
                true,
                cmd(
                        "gate_approve --decision revised --escalate-after 3 --escalate-decision need_human_decision:review_conflict"));
        TemplateNode humanGate = createNode(t, mockGate, "human_gate", false, "{}");
        TemplateNode done = createNode(t, mockSuccess, "done", false, cmd("success --artifact conflict-gate-done"));

        // AI node edges: self-loop on revised, approve out, escalate to human gate
        createEdge(t, aiReview, aiReview, "revised");
        createEdge(t, aiReview, done, "approved");
        createEdge(t, aiReview, humanGate, "need_human_decision:review_conflict");

        // Human gate edges: rereview sends back to AI, approve exits
        createEdge(t, humanGate, aiReview, "rereview");
        createEdge(t, humanGate, done, "approved");
    }

    // --- Roadmap Candidate Gate: draft_candidates -> review_candidates
    //       --(approved)--> [terminal_decisions, no downstream node — Decision 2]
    //       --(rejected)--> draft_candidates (back-edge)
    //
    // Mirrors BaseRoadmapProvisionerSeeder's real "roadmap_analyzer" / "roadmap_human_gate"
    // v13 shape: the analyzer node uploads roadmap_analysis.md + the structured
    // roadmap_candidates.json (Decision 1) that the human gate declares as required input
    // artifacts; the gate's config_overrides carry the same "terminal_decisions": ["approved"]
    // and "materialize": "roadmap_candidates" pair RunService.signalHumanDecision keys off of
    // (Decisions 2/3), so approving here drives the SAME deterministic materialization path a
    // real Roadmap Provisioner run does — just with a script-node analyzer stand-in
    // (mock-agent.sh's "roadmap_candidates" scenario) instead of a live Claude call.

    private void seedRoadmapCandidateGate(NodeDefinition mockSuccess, NodeDefinition mockGate) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_ROADMAP_CANDIDATE_GATE,
                "e2e-roadmap-candidate-gate",
                "E2E test: structured Roadmap Provisioner candidate-breakdown gate with terminal-decision materialization");

        TemplateNode analyzer = createNode(t, mockSuccess, "draft_candidates", true, cmd("roadmap_candidates"));
        TemplateNode gate = createNode(
                t,
                mockGate,
                "review_candidates",
                false,
                "{\"terminal_decisions\":[\"approved\"],\"materialize\":\"roadmap_candidates\"}",
                "[{\"template_node_label\":\"draft_candidates\",\"artifacts\":[{\"name\":\"roadmap_analysis.md\",\"description\":\"Mock roadmap analysis\"},"
                        + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Structured candidate Epic/Story/Task breakdown\"}]}]");

        createEdge(t, analyzer, gate, null);
        createEdge(t, gate, analyzer, "rejected");
        // Human Gate "approved" has no outgoing edge — it's a terminal_decisions entry
        // (Decision 2) instead, so the run completes right here, same as production v13.
    }

    // --- Many Artifacts: single node producing many output files (artifact viewer layout) ---
    //
    // Single-node, no edges: the entrypoint runs mock-agent.sh's "many_artifacts" scenario
    // (writes 40 small distinct files to /workspace/out), giving artifact-viewer-layout.spec.ts
    // a node execution with a realistic-to-large artifact count to drive the file-switcher
    // pill row's bounded/scrollable layout (Decision 1) via ArtifactBrowser's list.

    private void seedManyArtifacts(NodeDefinition mockSuccess) {
        GraphTemplate t = createTemplate(
                GRAPH_ID_MANY_ARTIFACTS,
                "e2e-many-artifacts",
                "E2E test: single node producing many output files (artifact viewer layout regression)");

        createNode(t, mockSuccess, "produce_files", true, cmd("many_artifacts --count 40"));
    }

    // --- Helpers ---

    private String cmd(String args) {
        return "{\"command\": \"" + mockScriptPath + " " + args + "\"}";
    }

    private NodeDefinition createNodeDef(String name, ExecutorType executorType, String promptTemplate, int timeout) {
        NodeDefinition nd = new NodeDefinition();
        nd.setName(name);
        nd.setExecutorType(executorType);
        nd.setImage(agentImage);
        nd.setPromptTemplate(promptTemplate);
        nd.setTimeoutSeconds(timeout);
        nd.setSkills("[]");
        nd.setInputSpec("{}");
        nd.setOutputSpec("{}");
        nd.setSecrets("[]");
        return nodeDefRepo.save(nd);
    }

    private GraphTemplate createTemplate(String graphId, String name, String description) {
        GraphTemplate t = new GraphTemplate();
        t.setGraphId(graphId);
        t.setVersion(VERSION);
        t.setName(name);
        t.setDescription(description);
        t.setInputSchema("[]");
        t.setSystem(false);
        return templateRepo.save(t);
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
