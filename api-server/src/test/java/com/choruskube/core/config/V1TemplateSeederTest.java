package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class V1TemplateSeederTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private BaseFeatureDevSeeder seeder;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private TemplateEdgeRepository edgeRepo;

    @Test
    void seedsTemplateOnFirstRun() {
        // The seeder runs automatically on startup (ApplicationRunner)
        var template = templateRepo.findByGraphIdAndVersion(
                GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION);
        assertThat(template).isPresent();
        // v37 removes every need_human_decision:* edge (7 of them) in favor of the
        // edgeless Supervisor routing hub: 8 template nodes, 12 edges (down from 19).
        assertThat(templateNodeRepo.findByGraphTemplateId(template.get().getId()))
                .hasSize(8);
        assertThat(edgeRepo.findByGraphTemplateId(template.get().getId())).hasSize(12);
    }

    @Test
    void idempotentSecondRun() throws Exception {
        // Run seeder again — the version short-circuit MUST prevent a second seed,
        // otherwise we would also leak a duplicate set of NodeDefinition rows
        // (created fresh per version, no longer reused by name).
        seeder.run(null);
        assertThat(templateRepo.findByGraphIdAndVersion(
                        GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION))
                .isPresent();
        var templates = templateRepo.findAll().stream()
                .filter(t -> "Feature Development".equals(t.getName()))
                .toList();
        assertThat(templates).hasSize(1);
        // Exactly one set of NodeDefinitions for the current version — no duplicates
        // from the re-seed attempt.
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Implement".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void templateHasCorrectInputSchema() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        assertThat(template.getInputSchema()).contains("feature_request");
        assertThat(template.getInputSchema()).doesNotContain("repo_url");
        assertThat(template.getInputSchema()).doesNotContain("test_command");
        assertThat(template.getInputSchema()).doesNotContain("agent_image");
    }

    @Test
    void featureRequestFieldUsesTextareaType() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        assertThat(template.getInputSchema()).contains("\"type\": \"textarea\"");
    }

    @Test
    void nodeDefinitionsAreCreated() {
        // NodeDefinition rows are scoped per template version. In a fresh test DB
        // only the current version has been seeded, so there is exactly one row
        // per name. In databases that have been upgraded across versions, the
        // count would be N — one set per seeded version, each pinned by its own
        // TemplateNode references.
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Draft Spec & Plan".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Spec Review".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
        // v35 retires the dedicated Push & Create PR node — Implement and
        // Code Review now own PR creation/refresh themselves.
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Push & Create PR".equals(nd.getName()))
                        .count())
                .as("v35 must not seed a Push & Create PR NodeDefinition")
                .isZero();
    }

    @Test
    void implementAndCodeReviewCarryNeedsPrConfigOverride() {
        // v35: PR creation/refresh moves into Implement and Code Review, gated
        // by needs_pr the same way needs_branch already gates branch provisioning.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var implement = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var codeReview = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        assertThat(implement.getConfigOverrides()).contains("\"needs_pr\": \"true\"");
        assertThat(codeReview.getConfigOverrides()).contains("\"needs_pr\": \"true\"");
    }

    @Test
    void entrypointIsSetCorrectly() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var entrypoints = nodes.stream().filter(n -> n.isEntrypoint()).toList();
        assertThat(entrypoints).hasSize(1);
        assertThat(entrypoints.get(0).getLabel()).isEqualTo("draft_spec_and_plan");
    }

    @Test
    void buildRunningNodesGetABuildSizedTimeout() {
        // code_review runs the same cold `npm ci` + typecheck + lint pass as implement,
        // so it needs implement's budget rather than the 1800s schema default — on the
        // default it dies on StartToClose once the diff under review grows.
        assertThat(timeoutSecondsFor("code_review")).isEqualTo(10800);
        assertThat(timeoutSecondsFor("code_review")).isEqualTo(timeoutSecondsFor("implement"));
    }

    private int timeoutSecondsFor(String label) {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var node = templateNodeRepo.findByGraphTemplateId(template.getId()).stream()
                .filter(n -> label.equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        return nodeDefRepo.findById(node.getNodeDefinitionId()).orElseThrow().getTimeoutSeconds();
    }

    @Test
    void specReviewHasOnlyApprovedAndRevisedEdges() {
        // v37: the three need_human_decision:* suffix variants are gone. spec_review now
        // escalates out-of-band via `escalate` to the edgeless Supervisor instead of an edge.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        var specReview = nodes.stream()
                .filter(n -> "spec_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        var outgoing = edges.stream()
                .filter(e -> e.getSourceNodeId().equals(specReview.getId()))
                .toList();
        assertThat(outgoing).hasSize(2);

        var approveSpecAndPlan = nodes.stream()
                .filter(n -> "approve_spec_and_plan".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        // approved → approve gate
        assertThat(outgoing.stream()
                        .filter(e -> "approved".equals(e.getCondition())
                                && e.getTargetNodeId().equals(approveSpecAndPlan.getId()))
                        .count())
                .isEqualTo(1);
        // revised self-loops back to spec_review
        assertThat(outgoing.stream()
                        .filter(e -> "revised".equals(e.getCondition())
                                && e.getTargetNodeId().equals(specReview.getId()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void approveSpecAndPlanSplitsIntoRereviewAndRedraft() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        var approve = nodes.stream()
                .filter(n -> "approve_spec_and_plan".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var specReview = nodes.stream()
                .filter(n -> "spec_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var draft = nodes.stream()
                .filter(n -> "draft_spec_and_plan".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        var outgoing = edges.stream()
                .filter(e -> e.getSourceNodeId().equals(approve.getId()))
                .toList();
        // approved → implement, rereview → spec_review, redraft → draft
        assertThat(outgoing).hasSize(3);
        assertThat(outgoing.stream()
                        .filter(e -> "rereview".equals(e.getCondition())
                                && e.getTargetNodeId().equals(specReview.getId()))
                        .count())
                .isEqualTo(1);
        assertThat(outgoing.stream()
                        .filter(e -> "redraft".equals(e.getCondition())
                                && e.getTargetNodeId().equals(draft.getId()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void codeReviewApprovedGoesToTest() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        var codeReview = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var test = nodes.stream()
                .filter(n -> "test".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        // v24: code_review approved routes to Test (the single deterministic test
        // gate, now placed AFTER Code Review so any commits Code Review pushed
        // during its self-loop are tested before reaching Final Approval).
        assertThat(edges.stream()
                        .filter(e -> e.getSourceNodeId().equals(codeReview.getId())
                                && "approved".equals(e.getCondition())
                                && e.getTargetNodeId().equals(test.getId()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void codeReviewHasOnlyApprovedAndRevisedEdges() {
        // v37: the Review Escalation node and its two need_human_decision:* edges are gone.
        // Code Review now escalates out-of-band via `escalate` to the edgeless Supervisor
        // instead of routing to a dedicated human gate.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        var codeReview = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var test = nodes.stream()
                .filter(n -> "test".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        var outgoing = edges.stream()
                .filter(e -> e.getSourceNodeId().equals(codeReview.getId()))
                .toList();
        assertThat(outgoing).hasSize(2);
        // revised self-loops back to code_review
        assertThat(outgoing.stream()
                        .filter(e -> "revised".equals(e.getCondition())
                                && e.getTargetNodeId().equals(codeReview.getId()))
                        .count())
                .isEqualTo(1);
        // approved → test
        assertThat(outgoing.stream()
                        .filter(e -> "approved".equals(e.getCondition())
                                && e.getTargetNodeId().equals(test.getId()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void v37DeclaresExactlyOneHumanRoutingHub() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var hubs = nodes.stream()
                .filter(n ->
                        n.getConfigOverrides() != null && n.getConfigOverrides().contains("\"routing_hub\""))
                .toList();

        assertThat(hubs).singleElement().satisfies(hub -> {
            assertThat(hub.getLabel()).isEqualTo("supervisor");
            var def = nodeDefRepo.findById(hub.getNodeDefinitionId()).orElseThrow();
            // Not enforced by GraphValidationService (it sees no NodeDefinition) — asserted here.
            assertThat(def.getExecutorType()).isEqualTo(ExecutorType.human);
        });
    }

    @Test
    void v37SupervisorHasNoEdges() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());
        var hubId = nodes.stream()
                .filter(n -> n.getLabel().equals("supervisor"))
                .findFirst()
                .orElseThrow()
                .getId();

        assertThat(edges)
                .noneMatch(e ->
                        e.getSourceNodeId().equals(hubId) || e.getTargetNodeId().equals(hubId));
    }

    @Test
    void v37HasNoNeedHumanDecisionEdgesLeft() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        assertThat(edges)
                .noneMatch(e -> e.getCondition() != null && e.getCondition().startsWith("need_human_decision"));
    }

    @Test
    void testPassedRoutesToFinalApprovalAndFailedRoutesToImplement() {
        // v24 invariant: the single Test gate, placed downstream of Code Review,
        // routes passed → Final Approval and failed → Implement. The failed edge
        // is unchanged from v23 (only its meaning shifts: in v24 it can fire on
        // either Implement-introduced OR Code-Review-introduced breakage).
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        var test = nodes.stream()
                .filter(n -> "test".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var finalApproval = nodes.stream()
                .filter(n -> "final_approval".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var implement = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        var outgoing = edges.stream()
                .filter(e -> e.getSourceNodeId().equals(test.getId()))
                .toList();
        assertThat(outgoing).as("Test has exactly two outgoing edges in v24").hasSize(2);

        assertThat(outgoing.stream()
                        .filter(e -> "passed".equals(e.getCondition())
                                && e.getTargetNodeId().equals(finalApproval.getId()))
                        .count())
                .as("test --passed--> final_approval")
                .isEqualTo(1);

        assertThat(outgoing.stream()
                        .filter(e -> "failed".equals(e.getCondition())
                                && e.getTargetNodeId().equals(implement.getId()))
                        .count())
                .as("test --failed--> implement")
                .isEqualTo(1);
    }

    @Test
    void v24HasNoTestBypassNode() {
        // v24 removes Test Bypass entirely. If real infra-only test failures
        // become a recurring pattern, a bypass can be added in a later version.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        assertThat(nodes.stream()
                        .filter(n -> "test_bypass".equals(n.getLabel()))
                        .count())
                .as("v24 must not seed a test_bypass TemplateNode")
                .isZero();
    }

    @Test
    void finalApprovalRereviewsBackToCodeReviewOnly() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        var finalApproval = nodes.stream()
                .filter(n -> "final_approval".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var codeReview = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        var outgoing = edges.stream()
                .filter(e -> e.getSourceNodeId().equals(finalApproval.getId()))
                .toList();
        // v35: rereview → code_review is the ONLY real outgoing edge.
        // `approved` no longer routes to a Push & Create PR node — it's declared as a
        // terminal_decisions entry on this node's config_overrides instead, ending the run.
        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.stream()
                        .filter(e -> "rereview".equals(e.getCondition())
                                && e.getTargetNodeId().equals(codeReview.getId()))
                        .count())
                .isEqualTo(1);
        assertThat(finalApproval.getConfigOverrides()).contains("\"terminal_decisions\": [\"approved\"]");
        // Final Approval must NOT have a redraft edge — once spec is approved,
        // discarding the implementation entirely is rare enough not to be routable.
        assertThat(outgoing.stream()
                        .filter(e -> "redraft".equals(e.getCondition()))
                        .count())
                .as("final_approval must not have a redraft edge")
                .isZero();
    }

    @Test
    void specReviewLoopGroupIsSpecReview() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var specReview = nodes.stream()
                .filter(n -> "spec_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        assertThat(specReview.getConfigOverrides()).contains("\"loop_group\": \"spec-review\"");
    }

    @Test
    void implementNodeHasSingleUnconditionalEdgeToCodeReview() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        var implement = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var codeReview = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        var outgoing = edges.stream()
                .filter(e -> e.getSourceNodeId().equals(implement.getId()))
                .toList();
        // v24: Implement has exactly one unconditional outbound edge → Code Review.
        // The api-server auto-sets decision="no_decision" for completed nodes
        // without conditional edges (InternalRunService.java:130-135), so the
        // Implement agent does NOT need to call report-result.
        assertThat(outgoing).hasSize(1);
        var only = outgoing.get(0);
        assertThat(only.getTargetNodeId()).isEqualTo(codeReview.getId());
        assertThat(only.getCondition())
                .as("implement → code_review must be unconditional")
                .isNull();
    }

    @Test
    void latestSeededVersionMatchesCurrentVersion() {
        // TaskService.start() resolves the Feature Dev template via
        // findFirstByGraphIdOrderByVersionDesc, so the highest-versioned seeded
        // row IS the default new runs use.
        var latest = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        assertThat(latest.getVersion()).isEqualTo(BaseFeatureDevSeeder.CURRENT_VERSION);
        assertThat(latest.getInputSchema()).contains("\"type\": \"software_project_id\"");
    }

    @Test
    void usesSoftwareProjectIdInputType() {
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        assertThat(template.getInputSchema()).contains("\"name\": \"software_project_id\"");
        assertThat(template.getInputSchema()).contains("\"type\": \"software_project_id\"");
        assertThat(template.getInputSchema()).doesNotContain("\"type\": \"git_repo_list\"");
    }

    @Test
    void testNodeIsScriptExecutorRunningRunAllTests() {
        // The Test node is a deterministic script gate that invokes run-all-tests
        // (which iterates each repo's test_command from /workspace/config.json).
        // It must NOT regress to an AI executor — that change in v16/v17 silently
        // no-op'd when test_command was missing from the agent's config.json.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var testNode = nodes.stream()
                .filter(n -> "test".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        var testNodeDef = nodeDefRepo.findById(testNode.getNodeDefinitionId()).orElseThrow();

        assertThat(testNodeDef.getExecutorType().name()).isEqualTo("script");
        assertThat(testNodeDef.getPromptTemplate()).isNull();
        assertThat(testNode.getConfigOverrides()).contains("\"command\": \"run-all-tests\"");
    }

    @Test
    void specDraftPromptDeclaresEightSectionStructure() {
        // The drafting prompt must enumerate the eight Part 1 sections + Part 2
        // by title so the agent produces the expected, human-reviewable structure
        // (Summary / Decisions / Architecture / Flow Diagrams / Expected Changed
        // Files / Testing Strategy / Caveats / Manual Operations).
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var draftNode = nodes.stream()
                .filter(n -> "draft_spec_and_plan".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var draftDef = nodeDefRepo.findById(draftNode.getNodeDefinitionId()).orElseThrow();

        assertThat(draftDef.getPromptTemplate())
                .contains("## 1. Summary")
                .contains("## 2. Decisions")
                .contains("## 3. Architecture")
                .contains("## 4. Flow Diagrams")
                .contains("## 5. Expected Changed Files")
                .contains("## 6. Testing Strategy")
                .contains("## 7. Caveats")
                .contains("## 8. Manual Operations")
                .contains("Part 2: Implementation Plan")
                .as("Diagram fence guidance must be present so the artifact viewer can render mermaid")
                .contains("```mermaid");
    }

    @Test
    void specReviewPromptHasFormatConformanceCheck() {
        // Spec Review must explicitly enforce the new Part 1 structure, including
        // cross-repo Architecture (not per-repo), per-repo Expected Changed Files,
        // mermaid-fenced diagrams, and Caveat blocks with Disposition tags.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var specReviewNode = nodes.stream()
                .filter(n -> "spec_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var specReviewDef =
                nodeDefRepo.findById(specReviewNode.getNodeDefinitionId()).orElseThrow();

        assertThat(specReviewDef.getPromptTemplate())
                .contains("Format conformance")
                .contains("Caveat hygiene")
                .contains("Decision soundness");
    }

    @Test
    void implementPromptDistinguishesPart1AndPart2() {
        // Implement must understand Part 1 is context (read first) and Part 2 is
        // the actionable plan (execute step by step).
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var implementNode = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var implementDef =
                nodeDefRepo.findById(implementNode.getNodeDefinitionId()).orElseThrow();

        assertThat(implementDef.getPromptTemplate())
                .contains("Part 1 (Specification")
                .contains("Part 2 (Implementation Plan)")
                .contains("EXECUTE Part 2 step by step");
    }

    @Test
    void implementPromptSurfacesManualOperationsAndCaveats() {
        // v35: PR creation moved from the retired Push & Create PR node
        // into Implement itself, so Implement's own "Opening and updating pull
        // requests" section must carry the same PR-body content rules that node used
        // to own — copy Manual Operations into PR bodies, list Caveats, and
        // flag any unresolved "Needs human decision" caveats.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var implementNode = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var implementDef =
                nodeDefRepo.findById(implementNode.getNodeDefinitionId()).orElseThrow();

        assertThat(implementDef.getPromptTemplate())
                .as("Implement must have access to the spec content to build PR bodies from it")
                .contains("{input.draft_spec_and_plan.result}")
                .as("PR body must surface Manual Operations under a clearly-labelled heading")
                .contains("## ⚠️ Manual Operations Required")
                .as("PR body must surface Caveats")
                .contains("## Caveats & Known Limitations")
                .as("PR body must conditionally surface any unresolved 'Needs human decision' caveats")
                .contains("## ❓ Open Decisions for Reviewer");
    }

    @Test
    void implementPromptEnforcesRepositoryVisibilityIsolation() {
        // v35 regression guard, carried over from the retired Push & Create PR node's
        // own v26 guard: PR text must never be written before a repo's visibility is
        // resolved, unknown visibility must fail safe to PUBLIC (never to private), and
        // a PUBLIC repo's PR may only link/name other PUBLIC repos' PRs — an empty
        // companion section is omitted, never narrated with a "none" note that would
        // itself disclose that companions were withheld.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var implementNode = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var prompt = nodeDefRepo
                .findById(implementNode.getNodeDefinitionId())
                .orElseThrow()
                .getPromptTemplate();

        assertThat(prompt)
                .as("the agent cannot filter by visibility it never resolved")
                .contains("gh repo view <owner/repo> --json visibility")
                .as("visibility must be resolved before any PR text is written")
                .contains("Resolve the repo's visibility FIRST")
                // Asserted as separate fragments: the prompt is a Java text block, so the
                // sentence wraps and no single-line literal spans it.
                .as("unknown visibility must fail safe to public, never to private")
                .contains("if the command fails or the answer is unclear")
                .contains("(fail-safe)");

        assertThat(prompt)
                .as("a public PR may only link other public PRs — a URL alone discloses a private repo")
                .contains("list ONLY the other PRs opened this pass")
                .as("an empty companion section must be omitted, not narrated — a 'none'"
                        + " note discloses that companions were withheld")
                .contains("Do NOT write \"none\"")
                .as("Manual Operations must be scoped and generalized for a public repo, not copied verbatim")
                .contains("include ONLY the operations that apply");
    }

    @Test
    void codeReviewPromptKeepsPullRequestsCurrentAndOnlyLinksOneDirectionally() {
        // v35: Code Review refreshes PRs Implement (or an earlier
        // Code Review pass) already opened, and opens a fallback PR itself for a repo
        // nobody has touched yet — but must NOT edit an already-open sibling PR to add
        // a backlink to a new one it just opened.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var codeReviewNode = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var prompt = nodeDefRepo
                .findById(codeReviewNode.getNodeDefinitionId())
                .orElseThrow()
                .getPromptTemplate();

        assertThat(prompt)
                .as(
                        "Code Review must build the known-PRs set from both Implement's and its own prior pass's pr_urls.txt")
                .contains("Build the known-PRs set first")
                .as("Code Review must re-run register-pr to refresh an already-known PR after pushing to it")
                .contains("re-run `register-pr` to refresh ChorusKube's own")
                .as(
                        "the one-directional-only rule must be explicit so a fresh PR is never backlinked from an older one")
                .contains("One-directional only")
                .contains("do NOT `gh pr edit` the earlier,");
    }

    @Test
    void implementPromptHasNoRoutingDecisionBlock() {
        // v24 inverts v23's regression guard: in v24, Implement has a single
        // unconditional outbound edge to Code Review, so the prompt must NOT
        // ask the agent to call report-result with `test` or `request_test_bypass`.
        // The api-server auto-sets decision="no_decision" for unconditional exits.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var implementNode = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var implementDef =
                nodeDefRepo.findById(implementNode.getNodeDefinitionId()).orElseThrow();
        var prompt = implementDef.getPromptTemplate();

        assertThat(prompt)
                .as("v24 Implement prompt must NOT ask for the legacy routing decisions")
                .doesNotContain("report-result test")
                .doesNotContain("report-result request_test_bypass")
                .doesNotContain("## Routing Decision");
        // v35: the dedicated Push & Create PR node is retired — Implement now owns PR
        // creation itself (in the later "Opening and updating pull requests" section), but
        // the original v16/v23 guardrail this regression-guards is preserved unchanged: a
        // per-repo subagent dispatched during parallel implementation must still never call
        // `gh pr create` itself.
        assertThat(prompt)
                .as("v35 Implement prompt must still forbid PR creation from inside a per-repo subagent")
                .contains("Do NOT run `gh pr create`");
    }

    @Test
    void reviewNodeDefinitionPromptsHaveWhenToEscalateSectionAndNoNeedHumanDecisionVocabulary() {
        // Regression guard, updated for v37: escalation moved from graph edges
        // (need_human_decision:*) to the Supervisor's out-of-band `escalate` decision. Both
        // self-iterating review prompts must still check {review_history} for conflicts before
        // finalizing, but the trigger for escalating is now a short "When to escalate" section,
        // not a need_human_decision:* decision-tree branch or a routable edge condition. Neither
        // prompt should reference that retired vocabulary, nor the older retired cap/epoch one.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var specReviewNode = nodes.stream()
                .filter(n -> "spec_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var specReviewPrompt = nodeDefRepo
                .findById(specReviewNode.getNodeDefinitionId())
                .orElseThrow()
                .getPromptTemplate();

        var codeReviewNode = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var codeReviewPrompt = nodeDefRepo
                .findById(codeReviewNode.getNodeDefinitionId())
                .orElseThrow()
                .getPromptTemplate();

        assertThat(specReviewPrompt)
                .contains("## Review History & Conflict Check")
                .contains("## When to escalate")
                .contains("review_conflict")
                .doesNotContain("need_human_decision")
                .doesNotContain("iteration_in_epoch");

        assertThat(codeReviewPrompt)
                .contains("## Review History & Conflict Check")
                .contains("## When to escalate")
                .contains("review_conflict")
                .doesNotContain("need_human_decision")
                .doesNotContain("iteration_in_epoch");
    }

    @Test
    void currentVersionIsBumpedForDecisionsIndexRow() {
        // v39: IMPLEMENT_PROMPT now tells the agent to add its own row to
        // docs/decisions/README.md's index. v38 told it only to mark an entry it
        // supersedes, so on the common path — superseding nothing — the index stayed
        // empty and the supersession check had nothing to read. Verifies only that the
        // seeder actually bumped its version constant when it shipped this change.
        assertThat(BaseFeatureDevSeeder.CURRENT_VERSION).isEqualTo(39);
        assertThat(templateRepo.findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, 39))
                .isPresent();
    }

    @Test
    void draftSpecAndPlanAndImplementCarryStaticModelAndExpectedEffort() {
        // Draft Spec & Plan: static Opus model, xhigh effort (research node). Implement:
        // static Sonnet model, downshifted to "high" effort from the pre-v36 "xhigh".
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var draftNode = nodes.stream()
                .filter(n -> "draft_spec_and_plan".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var draftDef = nodeDefRepo.findById(draftNode.getNodeDefinitionId()).orElseThrow();
        assertThat(draftDef.getModel()).isEqualTo(ModelIds.MODEL_OPUS);
        assertThat(draftNode.getConfigOverrides()).contains("\"effort\": \"xhigh\"");

        var implementNode = nodes.stream()
                .filter(n -> "implement".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var implementDef =
                nodeDefRepo.findById(implementNode.getNodeDefinitionId()).orElseThrow();
        assertThat(implementDef.getModel()).isEqualTo(ModelIds.MODEL_SONNET);
        assertThat(implementNode.getConfigOverrides())
                .contains("\"effort\": \"high\"")
                .doesNotContain("\"effort\": \"xhigh\"");
    }

    @Test
    void specReviewAndCodeReviewCarryIterationAwareModelEffortKeys() {
        // spec_review/code_review no longer carry a static flat `effort` —
        // both iteration bands are declared via the four new config_overrides keys, read
        // by the orchestrator's dag_executor.go keyed on tracker.reviewPass.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var specReview = nodes.stream()
                .filter(n -> "spec_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var codeReview = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        for (var node : List.of(specReview, codeReview)) {
            assertThat(node.getConfigOverrides())
                    .as("%s config_overrides", node.getLabel())
                    .contains("\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\"")
                    .contains("\"effort_first_iteration\": \"xhigh\"")
                    .contains("\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\"")
                    .contains("\"effort_subsequent_iteration\": \"high\"");
        }
        // Neither node declares a static flat `effort` key anymore — the iteration-aware
        // keys fully replace it (the "remove any static effort key" instruction).
        assertThat(specReview.getConfigOverrides()).doesNotContain("\"effort\":");
        assertThat(codeReview.getConfigOverrides()).doesNotContain("\"effort\":");
    }
}
