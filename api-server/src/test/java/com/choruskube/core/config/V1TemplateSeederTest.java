package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
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
        assertThat(templateNodeRepo.findByGraphTemplateId(template.get().getId()))
                .hasSize(8);
        assertThat(edgeRepo.findByGraphTemplateId(template.get().getId())).hasSize(18);
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
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Push & Create PR".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
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
    void reviewNodeDefinitionsHaveIterationCaps() {
        // v23 self-iterating loops must terminate. Caps live on NodeDefinition because they
        // are a property of the reviewer's concept (Spec Review / Code Review), not the
        // template wiring. getValidDecisions reads the cap from the snapshot and restricts
        // the agent's choices to need_human_decision:iteration_cap once the cap is hit.
        var specReview = nodeDefRepo.findAll().stream()
                .filter(nd -> "Spec Review".equals(nd.getName()))
                .findFirst()
                .orElseThrow();
        var codeReview = nodeDefRepo.findAll().stream()
                .filter(nd -> "Code Review".equals(nd.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(specReview.getIterationCap()).isEqualTo(3);
        assertThat(codeReview.getIterationCap()).isEqualTo(5);
    }

    @Test
    void specReviewSelfIteratesAndEscalatesToHumanGate() {
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
        // v23: approved + 3 need_human_decision suffix variants + revised self-loop
        assertThat(outgoing).hasSize(5);

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
        // each need_human_decision suffix variant → approve gate
        for (String suffix : new String[] {"alternative_proposal", "iteration_cap", "uncertainty"}) {
            assertThat(outgoing.stream()
                            .filter(e -> ("need_human_decision:" + suffix).equals(e.getCondition())
                                    && e.getTargetNodeId().equals(approveSpecAndPlan.getId()))
                            .count())
                    .as("spec_review --need_human_decision:%s--> approve_spec_and_plan", suffix)
                    .isEqualTo(1);
        }
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
    void codeReviewSelfIteratesAndEscalatesViaSuffixVariants() {
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

        // revised self-loops back to code_review
        assertThat(edges.stream()
                        .filter(e -> e.getSourceNodeId().equals(codeReview.getId())
                                && "revised".equals(e.getCondition())
                                && e.getTargetNodeId().equals(codeReview.getId()))
                        .count())
                .isEqualTo(1);
        // v24: Code Review's iteration_cap and uncertainty exits route to Test
        // (single test gate), not Final Approval. Test will then route passed →
        // Final Approval or failed → Implement.
        for (String suffix : new String[] {"iteration_cap", "uncertainty"}) {
            assertThat(edges.stream()
                            .filter(e -> e.getSourceNodeId().equals(codeReview.getId())
                                    && ("need_human_decision:" + suffix).equals(e.getCondition())
                                    && e.getTargetNodeId().equals(test.getId()))
                            .count())
                    .as("code_review --need_human_decision:%s--> test", suffix)
                    .isEqualTo(1);
        }
        // Code Review must NOT emit alternative_proposal — the spec is fixed by contract.
        assertThat(edges.stream()
                        .filter(e -> e.getSourceNodeId().equals(codeReview.getId())
                                && "need_human_decision:alternative_proposal".equals(e.getCondition()))
                        .count())
                .as("code_review must not have an alternative_proposal edge")
                .isZero();
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
        // approved → push_create_pr, rereview → code_review (no redraft option)
        assertThat(outgoing).hasSize(2);
        assertThat(outgoing.stream()
                        .filter(e -> "rereview".equals(e.getCondition())
                                && e.getTargetNodeId().equals(codeReview.getId()))
                        .count())
                .isEqualTo(1);
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
    void pushPrPromptSurfacesManualOperationsAndCaveats() {
        // Push & Create PR must reach back to the spec and the spec review so it
        // can copy §8 Manual Operations into PR bodies, list §7 Caveats, and flag
        // any unresolved "Needs human decision" caveats prominently.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var pushPrNode = nodes.stream()
                .filter(n -> "push_create_pr".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var pushPrDef = nodeDefRepo.findById(pushPrNode.getNodeDefinitionId()).orElseThrow();

        assertThat(pushPrDef.getPromptTemplate())
                .as("Push PR must have access to the spec content")
                .contains("{input.draft_spec_and_plan.result}")
                .as("Push PR must have access to the spec reviewer's notes for caveat resolution context")
                .contains("{input.spec_review.result}")
                .as("PR body must surface Manual Operations under a clearly-labelled heading")
                .contains("## ⚠️ Manual Operations Required")
                .as("PR body must surface Caveats from §7")
                .contains("## Caveats & Known Limitations")
                .as("PR body must conditionally surface any unresolved 'Needs human decision' caveats")
                .contains("## ❓ Open Decisions for Reviewer");
    }

    @Test
    void pushPrPromptEnforcesRepositoryVisibilityIsolation() {
        // v26 regression guard. Before v26 the prompt ordered the agent to copy §8
        // into every PR "do not filter by repo" and to cross-link every PR to every
        // other one. In a run spanning repos of mixed visibility that mandates a
        // leak: the public repo's PR ends up linking a non-public repo's PR and
        // republishing its rollout steps. The agent was obeying its prompt, so the
        // fix has to live in the prompt.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var pushPrNode = nodes.stream()
                .filter(n -> "push_create_pr".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var prompt = nodeDefRepo
                .findById(pushPrNode.getNodeDefinitionId())
                .orElseThrow()
                .getPromptTemplate();

        assertThat(prompt)
                .as("the agent cannot filter by visibility it never resolved")
                .contains("gh repo view <owner/repo> --json visibility")
                .as("visibility must be resolved before any PR text is written")
                .contains("Resolve each repo's visibility FIRST")
                // Asserted as two fragments: the prompt is a Java text block, so the
                // sentence wraps and no single-line literal spans it.
                .as("unknown visibility must fail safe to public, never to private")
                .contains("Treat a repo as PUBLIC")
                .contains("if the command fails or the answer is unclear (fail-safe)");

        assertThat(prompt)
                .as("the pre-v26 order that forced §8 into every PR must be gone")
                .doesNotContain("do not filter by repo")
                // The pre-v26 sentence wrapped across text-block lines, so assert on a
                // fragment that lived on a single line — otherwise this passes vacuously.
                .as("the pre-v26 order that cross-linked every PR to every other must be gone")
                .doesNotContain("in the set must end up linking every other PR");

        // §7 Caveats and the Open Decisions section derived from it are cross-repo
        // content of exactly the same shape as §8 — a caveat can name a non-public
        // repo just as easily as a rollout row can. Filtering §8 while leaving §7
        // beside it unfiltered would reopen the hole at a different address.
        assertThat(prompt)
                .as("§7 Caveats must be visibility-filtered for a public repo, like §8")
                .contains("§7 is cross-repo content, exactly like §8, so the same")
                .as("a caveat that only exists because a non-public repo is involved must be dropped, not reworded")
                .contains("hint that one exists")
                .as("Open Decisions is derived from §7 and must inherit its filter")
                .contains("it inherits §7's visibility");

        assertThat(prompt)
                .as("a public PR may only link other public PRs — a URL alone discloses a private repo")
                .contains("list ONLY the other PRs whose repos are also")
                .as("an empty companion section must be omitted, not narrated — a 'none'"
                        + " note discloses that companions were withheld")
                .contains("Do NOT write \"none\"")
                .as("§8 must be scoped and generalized for a public repo, not copied verbatim")
                .contains("include ONLY the operations that apply to");
    }

    @Test
    void pushPrNodeIsNotTieredToACheaperModel() {
        // Through v25 this node ran on Haiku, tiered down on the premise that it was
        // mechanical. From v26 it must classify each repo's visibility and decide what
        // to drop or generalize before writing a PR body — a judgment whose failure
        // mode is publishing non-public detail irreversibly. If a future cost-tuning
        // pass re-pins a cheaper model here, that tradeoff must be made deliberately,
        // not by reflex — this test is what forces the conversation.
        var template = templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var pushPrNode = nodes.stream()
                .filter(n -> "push_create_pr".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var pushPrDef = nodeDefRepo.findById(pushPrNode.getNodeDefinitionId()).orElseThrow();

        assertThat(pushPrDef.getModel())
                .as("Push & Create PR must run on the default model now that it makes"
                        + " an irreversible visibility judgment")
                .isNull();
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
        // The "no `gh pr create`" guardrail (load-bearing per the v16/v23 incident
        // documented in the prior version of this test) is preserved — the dedicated
        // Push & Create PR node still owns PR creation.
        assertThat(prompt)
                .as("v24 Implement prompt must still forbid PR creation in this node")
                .contains("Do NOT run `gh pr create`");
    }

    @Test
    void reviewNodeDefinitionCapPromptsReferenceEpochRelativeIteration() {
        // Regression guard: the agent must reason about the iteration cap using
        // iteration_in_epoch (epoch-relative, reset by a human "route back"), not
        // the raw, ever-incrementing `iteration` field. Comparing against the raw
        // field is exactly the bug this template version fixes — see
        // BaseFeatureDevSeeder's Iteration awareness sections.
        //
        // The resolved prompt strings are Java text blocks; SPEC_REVIEW_PROMPT's
        // cap=3 escalation sentence wraps so the comparison operator falls on its
        // own line ("...AND iteration_in_epoch" / ">= 3." on the next line). A
        // literal substring match against the raw resolved string would miss (or
        // vacuously pass) that comparison, so whitespace is normalized to single
        // spaces before any assertion below.
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
        String normalizedSpecReviewPrompt = specReviewDef.getPromptTemplate().replaceAll("\\s+", " ");

        var codeReviewNode = nodes.stream()
                .filter(n -> "code_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var codeReviewDef =
                nodeDefRepo.findById(codeReviewNode.getNodeDefinitionId()).orElseThrow();
        String normalizedCodeReviewPrompt = codeReviewDef.getPromptTemplate().replaceAll("\\s+", " ");

        // Spec Review: cap = 3, references iteration_in_epoch alongside the cap
        // threshold and the escalation decision.
        assertThat(normalizedSpecReviewPrompt)
                .contains("iteration_in_epoch")
                .contains("iteration_in_epoch < 3")
                .contains("iteration_in_epoch >= 3")
                .contains("need_human_decision:iteration_cap");
        // Raw-iteration comparison forms must not appear anywhere in the
        // cap-related text — this is what would have masked the original bug.
        assertThat(normalizedSpecReviewPrompt).doesNotContain("iteration < 3").doesNotContain("iteration >= 3");

        // Code Review: cap = 5, same shape.
        assertThat(normalizedCodeReviewPrompt)
                .contains("iteration_in_epoch")
                .contains("iteration_in_epoch < 5")
                .contains("iteration_in_epoch >= 5")
                .contains("need_human_decision:iteration_cap");
        assertThat(normalizedCodeReviewPrompt).doesNotContain("iteration < 5").doesNotContain("iteration >= 5");
    }
}
