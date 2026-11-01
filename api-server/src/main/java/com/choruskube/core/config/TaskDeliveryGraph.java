package com.choruskube.core.config;

import com.choruskube.core.config.DeliveryPrompts.SpecSource;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Task Delivery graph, shared by {@link BaseTaskDeliverySeeder} and the e2e mock copy so the graph
 * the e2e suite exercises cannot drift from the one users run.
 */
public final class TaskDeliveryGraph {

    public static final String ANALYZE = "analyze";
    public static final String TASK_SPEC_REVIEW = "task_spec_review";
    public static final String APPROVE_TASK_SPEC = "approve_task_spec";
    public static final String AWAIT_STORY_REVISION = "await_story_revision";
    public static final String IMPLEMENT = "implement";
    public static final String CODE_REVIEW = "code_review";
    public static final String TEST = "test";
    public static final String FINAL_APPROVAL = "final_approval";
    public static final String SUPERVISOR = "supervisor";

    public static final String PLANNED = "planned";
    public static final String NEEDS_SPEC = "needs_spec";
    public static final String STORY_CONFLICT = "story_conflict";
    public static final String RESUME = "resume";

    private static final Set<String> LABELS = Set.of(
            ANALYZE,
            TASK_SPEC_REVIEW,
            APPROVE_TASK_SPEC,
            AWAIT_STORY_REVISION,
            IMPLEMENT,
            CODE_REVIEW,
            TEST,
            FINAL_APPROVAL,
            SUPERVISOR);

    private static final String ANALYZE_OUTPUTS = "{\"files\":["
            + "{\"name\":\"plan.md\",\"required\":true,\"description\":\"The Task plan, written against current main\"},"
            + "{\"name\":\"task_spec.md\",\"required\":false,\"description\":\"The Task spec (only on needs_spec)\"},"
            + "{\"name\":\"story_conflict.md\",\"required\":false,\"description\":\"Where the story spec is wrong for this Task (only on story_conflict)\"}]}";

    private static final String TASK_SPEC_REVIEW_OUTPUTS = "{\"files\":["
            + "{\"name\":\"plan.md\",\"required\":true,\"description\":\"The reviewed (and possibly revised) Task plan\"},"
            + "{\"name\":\"task_spec.md\",\"required\":true,\"description\":\"The reviewed (and possibly revised) Task spec\"},"
            + "{\"name\":\"task_spec_review.md\",\"required\":true,\"description\":\"Review notes, including Reasoning for fixes\"}]}";

    private static final String IMPLEMENT_OUTPUTS = "{\"files\":["
            + "{\"name\":\"summary.md\",\"required\":true,\"description\":\"Implementation summary describing changes made\"},"
            + "{\"name\":\"roadmap_candidates.json\",\"required\":false,\"description\":\"Proposed roadmap extension for Final Approval (only when deferred work is proposed)\"}]}";

    private static final String CODE_REVIEW_OUTPUTS = "{\"files\":["
            + "{\"name\":\"review.md\",\"required\":true,\"description\":\"Code review findings and approve/reject recommendation\"},"
            + "{\"name\":\"roadmap_candidates.json\",\"required\":false,\"description\":\"Reviewed roadmap proposal for Final Approval (only when one exists)\"}]}";

    private static final String ANALYZE_CONFIG = "{\"effort\": \"high\"}";

    private static final String TASK_SPEC_REVIEW_CONFIG = "{\"loop_group\": \"task-spec\", "
            + "\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\", "
            + "\"effort_first_iteration\": \"xhigh\", "
            + "\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\", "
            + "\"effort_subsequent_iteration\": \"high\"}";

    private static final String APPROVE_TASK_SPEC_CONFIG = "{\"loop_group\": \"task-spec\", "
            + "\"drift_recheck\": {\"route\": \"rereview\", \"stamp\": \"always\"}, "
            + "\"story_version_recheck\": {\"route\": \"rereview\"}}";

    private static final String AWAIT_STORY_REVISION_CONFIG = "{}";

    private static final String IMPLEMENT_CONFIG =
            "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\", \"effort\": \"high\", \"needs_pr\": \"true\"}";

    private static final String TEST_CONFIG =
            "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\", \"command\": \"run-all-tests\"}";

    private static final String CODE_REVIEW_CONFIG =
            "{\"loop_group\": \"impl-review\", \"needs_branch\": \"true\", \"needs_pr\": \"true\", "
                    + "\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\", "
                    + "\"effort_first_iteration\": \"xhigh\", "
                    + "\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\", "
                    + "\"effort_subsequent_iteration\": \"high\"}";

    private static final String FINAL_APPROVAL_CONFIG = "{\"loop_group\": \"impl-review\", "
            + "\"terminal_decisions\": [\"approved\"], \"materialize\": \"roadmap_extension\", "
            + "\"merge_pull_requests\": \"squash\", "
            + "\"drift_recheck\": {\"route\": \"rereview\", \"stamp\": \"always\"}, "
            + "\"story_version_recheck\": {\"route\": \"rereview\"}}";

    private static final String SUPERVISOR_CONFIG = "{\"routing_hub\": true}";

    // Analyze and Task Spec Review both write plan.md and task_spec.md, and an agent cannot tell
    // which copy is newer, so the server delivers the newest producer's; an optional task_spec.md
    // that producer did not write is left out, so a re-plan never feeds a stale one.
    private static final String PLAN_GROUP = planGroup(false);

    private static final String ANALYZE_INPUTS = "["
            + "{\"template_node_label\":\"" + ANALYZE + "\",\"artifacts\":["
            + "{\"name\":\"story_conflict.md\",\"description\":\"The conflict this Task raised on an earlier iteration (only present if it raised one)\",\"required\":false}]}]";

    // The self-reference resolves to the previous iteration's notes, which is how a fresh-session
    // review builds on its earlier reasoning instead of reverting it.
    private static final String TASK_SPEC_REVIEW_INPUTS = "[" + planGroup(true) + ","
            + "{\"template_node_label\":\"" + TASK_SPEC_REVIEW + "\",\"artifacts\":["
            + "{\"name\":\"task_spec_review.md\",\"description\":\"Prior iteration's review notes including Reasoning for fixes (only present if iteration > 1)\",\"required\":false}]}]";

    private static final String APPROVE_TASK_SPEC_INPUTS = "[" + PLAN_GROUP + ","
            + "{\"template_node_label\":\"" + TASK_SPEC_REVIEW + "\",\"artifacts\":["
            + "{\"name\":\"task_spec_review.md\",\"description\":\"Reviewer notes\",\"required\":true}]}]";

    private static final String AWAIT_STORY_REVISION_INPUTS = "["
            + "{\"template_node_label\":\"" + ANALYZE + "\",\"artifacts\":["
            + "{\"name\":\"story_conflict.md\",\"description\":\"Where the story spec is wrong for this Task\",\"required\":true}]}]";

    private static final String IMPLEMENT_INPUTS = "[" + PLAN_GROUP + ","
            + "{\"template_node_label\":\"" + IMPLEMENT + "\",\"artifacts\":["
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Prior iteration's roadmap proposal (only present if iteration > 1 and one was proposed)\",\"required\":false}]}]";

    // Implement's proposal is read so Code Review can rebuild and re-install it; Final Approval
    // takes whichever of the two copies was written last.
    private static final String CODE_REVIEW_INPUTS = "[" + PLAN_GROUP + ","
            + "{\"template_node_label\":\"" + CODE_REVIEW + "\",\"artifacts\":["
            + "{\"name\":\"review.md\",\"description\":\"Prior iteration's code review notes including Reasoning for fixes (only present if iteration > 1)\",\"required\":false}]},"
            + "{\"template_node_label\":\"" + IMPLEMENT + "\",\"artifacts\":["
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Implement's latest roadmap proposal (only present if one was proposed)\",\"required\":false}]}]";

    private static final String FINAL_APPROVAL_INPUTS = "["
            + "{\"template_node_label\":\"" + IMPLEMENT + "\",\"artifacts\":["
            + "{\"name\":\"summary.md\",\"description\":\"Implementation summary describing changes made\",\"required\":true},"
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Proposed roadmap extension (optional)\",\"required\":false}]},"
            + "{\"template_node_label\":\"" + CODE_REVIEW + "\",\"artifacts\":["
            + "{\"name\":\"review.md\",\"description\":\"Code review findings and approve/reject recommendation\",\"required\":true},"
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Reviewed roadmap proposal (optional; the newer of this and Implement's copy is used)\",\"required\":false}]}]";

    private TaskDeliveryGraph() {}

    private static String planGroup(boolean taskSpecRequired) {
        return "{\"newest_of\": [\"" + ANALYZE + "\", \"" + TASK_SPEC_REVIEW + "\"], "
                + "\"alias\": \"plan\", \"artifacts\": ["
                + "{\"name\":\"plan.md\",\"description\":\"The Task plan, from whichever of Analyze and Task Spec Review ran last\",\"required\":true},"
                + "{\"name\":\"task_spec.md\",\"description\":\"The Task spec from that same node"
                + (taskSpecRequired ? "" : " (only present if it wrote one)")
                + "\",\"required\":" + taskSpecRequired + "}]}";
    }

    /** Each extra config replaces that label's top-level keys of the same name; nested objects are replaced whole. */
    public static void seed(TemplateWriter w, GraphTemplate t, Map<String, String> extraConfigByLabel) {
        Set<String> unknown = new TreeSet<>(extraConfigByLabel.keySet());
        unknown.removeAll(LABELS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Task Delivery has no node labelled " + unknown);
        }

        NodeDefinition analyzeDef = w.nodeDef(
                "Analyze Task",
                ExecutorType.ai,
                TaskDeliveryPrompts.ANALYZE_PROMPT,
                3600,
                ModelIds.MODEL_OPUS,
                ANALYZE_OUTPUTS);
        NodeDefinition taskSpecReviewDef = w.nodeDef(
                "Task Spec Review",
                ExecutorType.ai,
                TaskDeliveryPrompts.TASK_SPEC_REVIEW_PROMPT,
                1800,
                null,
                TASK_SPEC_REVIEW_OUTPUTS);
        NodeDefinition approveTaskSpecDef = w.nodeDef("Approve Task Spec", ExecutorType.human, null, 86400, null, null);
        // 0 selects the orchestrator's 7-day gate timer: a Story revision is drafted, reviewed and
        // approved by a human before this gate resumes, which outlasts the 1-day human-node maximum.
        NodeDefinition awaitStoryRevisionDef =
                w.nodeDef("Await Story Revision", ExecutorType.human, null, 0, null, null);
        NodeDefinition implementDef = w.nodeDef(
                "Implement",
                ExecutorType.ai,
                DeliveryPrompts.implement(SpecSource.TASK_DELIVERY),
                10800,
                ModelIds.MODEL_SONNET,
                IMPLEMENT_OUTPUTS);
        NodeDefinition testDef = w.nodeDef("Test", ExecutorType.script, null, 7200, null, null);
        NodeDefinition codeReviewDef = w.nodeDef(
                "Code Review",
                ExecutorType.ai,
                DeliveryPrompts.codeReview(SpecSource.TASK_DELIVERY),
                10800,
                null,
                CODE_REVIEW_OUTPUTS);
        NodeDefinition finalApprovalDef = w.nodeDef("Final Approval", ExecutorType.human, null, 86400, null, null);
        NodeDefinition supervisorDef = w.nodeDef("Supervisor", ExecutorType.human, null, 86400, null, null);

        TemplateNode analyze = w.node(
                t, analyzeDef, ANALYZE, true, config(ANALYZE, ANALYZE_CONFIG, extraConfigByLabel), ANALYZE_INPUTS);
        TemplateNode taskSpecReview = w.node(
                t,
                taskSpecReviewDef,
                TASK_SPEC_REVIEW,
                false,
                config(TASK_SPEC_REVIEW, TASK_SPEC_REVIEW_CONFIG, extraConfigByLabel),
                TASK_SPEC_REVIEW_INPUTS);
        TemplateNode approveTaskSpec = w.node(
                t,
                approveTaskSpecDef,
                APPROVE_TASK_SPEC,
                false,
                config(APPROVE_TASK_SPEC, APPROVE_TASK_SPEC_CONFIG, extraConfigByLabel),
                APPROVE_TASK_SPEC_INPUTS);
        TemplateNode awaitStoryRevision = w.node(
                t,
                awaitStoryRevisionDef,
                AWAIT_STORY_REVISION,
                false,
                config(AWAIT_STORY_REVISION, AWAIT_STORY_REVISION_CONFIG, extraConfigByLabel),
                AWAIT_STORY_REVISION_INPUTS);
        TemplateNode implement = w.node(
                t,
                implementDef,
                IMPLEMENT,
                false,
                config(IMPLEMENT, IMPLEMENT_CONFIG, extraConfigByLabel),
                IMPLEMENT_INPUTS);
        TemplateNode test = w.node(t, testDef, TEST, false, config(TEST, TEST_CONFIG, extraConfigByLabel), null);
        TemplateNode codeReview = w.node(
                t,
                codeReviewDef,
                CODE_REVIEW,
                false,
                config(CODE_REVIEW, CODE_REVIEW_CONFIG, extraConfigByLabel),
                CODE_REVIEW_INPUTS);
        TemplateNode finalApproval = w.node(
                t,
                finalApprovalDef,
                FINAL_APPROVAL,
                false,
                config(FINAL_APPROVAL, FINAL_APPROVAL_CONFIG, extraConfigByLabel),
                FINAL_APPROVAL_INPUTS);
        w.node(t, supervisorDef, SUPERVISOR, false, config(SUPERVISOR, SUPERVISOR_CONFIG, extraConfigByLabel), null);

        w.edge(t, analyze, implement, PLANNED);
        w.edge(t, analyze, taskSpecReview, NEEDS_SPEC);
        w.edge(t, analyze, awaitStoryRevision, STORY_CONFLICT);
        w.edge(t, awaitStoryRevision, analyze, RESUME);
        w.edge(t, taskSpecReview, approveTaskSpec, "approved");
        w.edge(t, taskSpecReview, taskSpecReview, "revised");
        w.edge(t, approveTaskSpec, implement, "approved");
        w.edge(t, approveTaskSpec, taskSpecReview, "rereview");
        w.edge(t, approveTaskSpec, analyze, "redraft");
        w.edge(t, implement, codeReview, null);
        w.edge(t, codeReview, codeReview, "revised");
        w.edge(t, codeReview, test, "approved");
        w.edge(t, test, finalApproval, "passed");
        w.edge(t, test, implement, "failed");
        w.edge(t, finalApproval, codeReview, "rereview");
    }

    private static String config(String label, String base, Map<String, String> extraConfigByLabel) {
        return TemplateWriter.mergeConfig(base, extraConfigByLabel.get(label));
    }
}
