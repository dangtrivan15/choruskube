package com.choruskube.core.config;

import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The Story Design graph, shared by {@link BaseStoryDesignSeeder} and the e2e mock copy so the graph
 * the e2e suite exercises cannot drift from the one users run.
 */
public final class StoryDesignGraph {

    public static final String DRAFT = "draft_story_spec";
    public static final String REVIEW = "story_spec_review";
    public static final String APPROVE = "approve_story_spec";
    public static final String SUPERVISOR = "supervisor";

    private static final Set<String> LABELS = Set.of(DRAFT, REVIEW, APPROVE, SUPERVISOR);

    private static final String DRAFT_OUTPUTS = "{\"files\":["
            + "{\"name\":\"story_spec.md\",\"required\":true,\"description\":\"The Story's design and Task breakdown\"},"
            + "{\"name\":\"roadmap_candidates.json\",\"required\":false,\"description\":\"Proposed Tasks and deferred work for the approval gate (only when something is proposed)\"}]}";

    private static final String REVIEW_OUTPUTS = "{\"files\":["
            + "{\"name\":\"story_spec.md\",\"required\":true,\"description\":\"The reviewed (and possibly revised) Story spec\"},"
            + "{\"name\":\"story_spec_review.md\",\"required\":true,\"description\":\"Review notes, including Reasoning for fixes\"},"
            + "{\"name\":\"roadmap_candidates.json\",\"required\":false,\"description\":\"The reviewed roadmap proposal (only when one exists)\"}]}";

    private static final String DRAFT_CONFIG = "{\"loop_group\": \"story-spec\", \"effort\": \"xhigh\"}";

    private static final String REVIEW_CONFIG = "{\"loop_group\": \"story-spec\", "
            + "\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\", "
            + "\"effort_first_iteration\": \"xhigh\", "
            + "\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\", "
            + "\"effort_subsequent_iteration\": \"high\"}";

    private static final String APPROVE_CONFIG = "{\"loop_group\": \"story-spec\", "
            + "\"terminal_decisions\": [\"approved\"], \"materialize\": \"roadmap_extension\"}";

    private static final String SUPERVISOR_CONFIG = "{\"routing_hub\": true}";

    // The self-reference resolves to the previous iteration's outputs, which is how a fresh-session
    // review builds on its earlier reasoning instead of reverting it.
    private static final String REVIEW_INPUTS = "["
            + "{\"template_node_label\":\"" + DRAFT + "\",\"artifacts\":["
            + "{\"name\":\"story_spec.md\",\"description\":\"The drafter's Story spec\",\"required\":true},"
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"The drafter's roadmap proposal (only present if one was proposed)\",\"required\":false}]},"
            + "{\"template_node_label\":\"" + REVIEW + "\",\"artifacts\":["
            + "{\"name\":\"story_spec.md\",\"description\":\"Prior iteration's revised Story spec (only present if iteration > 1)\",\"required\":false},"
            + "{\"name\":\"story_spec_review.md\",\"description\":\"Prior iteration's review notes including Reasoning for fixes (only present if iteration > 1)\",\"required\":false},"
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Prior iteration's roadmap proposal (only present if one was installed)\",\"required\":false}]}]";

    // Approval records Story Spec Review's copy of the spec as the Story's version, so the gate must
    // show that copy, never the unreviewed draft.
    private static final String APPROVE_INPUTS = "["
            + "{\"template_node_label\":\"" + REVIEW + "\",\"artifacts\":["
            + "{\"name\":\"story_spec.md\",\"description\":\"The reviewed Story spec to approve\",\"required\":true},"
            + "{\"name\":\"story_spec_review.md\",\"description\":\"Reviewer notes\",\"required\":true},"
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"Reviewed roadmap proposal (optional; the newer of this and the drafter's copy is used)\",\"required\":false}]},"
            + "{\"template_node_label\":\"" + DRAFT + "\",\"artifacts\":["
            + "{\"name\":\"roadmap_candidates.json\",\"description\":\"The drafter's roadmap proposal (optional)\",\"required\":false}]}]";

    private StoryDesignGraph() {}

    /** Each extra config replaces that label's top-level keys of the same name; nested objects are replaced whole. */
    public static void seed(TemplateWriter w, GraphTemplate t, Map<String, String> extraConfigByLabel) {
        Set<String> unknown = new TreeSet<>(extraConfigByLabel.keySet());
        unknown.removeAll(LABELS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Story Design has no node labelled " + unknown);
        }

        NodeDefinition draftDef = w.nodeDef(
                "Draft Story Spec",
                ExecutorType.ai,
                StoryDesignPrompts.DRAFT_STORY_SPEC_PROMPT,
                3600,
                ModelIds.MODEL_OPUS,
                DRAFT_OUTPUTS);
        NodeDefinition reviewDef = w.nodeDef(
                "Story Spec Review",
                ExecutorType.ai,
                StoryDesignPrompts.STORY_SPEC_REVIEW_PROMPT,
                1800,
                null,
                REVIEW_OUTPUTS);
        NodeDefinition approveDef = w.nodeDef("Approve Story Spec", ExecutorType.human, null, 86400, null, null);
        NodeDefinition supervisorDef = w.nodeDef("Supervisor", ExecutorType.human, null, 86400, null, null);

        TemplateNode draft = w.node(t, draftDef, DRAFT, true, config(DRAFT, DRAFT_CONFIG, extraConfigByLabel), null);
        TemplateNode review =
                w.node(t, reviewDef, REVIEW, false, config(REVIEW, REVIEW_CONFIG, extraConfigByLabel), REVIEW_INPUTS);
        TemplateNode approve = w.node(
                t, approveDef, APPROVE, false, config(APPROVE, APPROVE_CONFIG, extraConfigByLabel), APPROVE_INPUTS);
        w.node(t, supervisorDef, SUPERVISOR, false, config(SUPERVISOR, SUPERVISOR_CONFIG, extraConfigByLabel), null);

        w.edge(t, draft, review, null);
        w.edge(t, review, approve, "approved");
        w.edge(t, review, review, "revised");
        w.edge(t, approve, review, "rereview");
        w.edge(t, approve, draft, "redraft");
    }

    private static String config(String label, String base, Map<String, String> extraConfigByLabel) {
        return TemplateWriter.mergeConfig(base, extraConfigByLabel.get(label));
    }
}
