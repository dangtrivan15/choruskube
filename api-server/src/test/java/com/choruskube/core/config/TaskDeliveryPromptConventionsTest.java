package com.choruskube.core.config;

import static com.choruskube.core.config.TaskDeliveryPrompts.ANALYZE_PROMPT;
import static com.choruskube.core.config.TaskDeliveryPrompts.TASK_SPEC_REVIEW_PROMPT;
import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.config.DeliveryPrompts.SpecSource;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.enums.ExecutorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The prompts are the only place the Task plan's contract and the Analyze decision reach an agent,
 * so a missing clause is silent: the agent plans, or decides, by its own lights.
 */
class TaskDeliveryPromptConventionsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The worker's placeholder syntax: any match it cannot resolve fails the node. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_.]*)\\}");

    /** The decision test, as the design states it; the prompt carries it word for word. */
    private static final String[] DECISION_TABLE = {
        "| Decision | Test | Also writes |",
        "|---|---|---|",
        "| `planned` | The story spec plus the Task make every choice a reviewer could reasonably reject | — |",
        "| `needs_spec` | **Gap:** the Task needs such a choice and the story spec does not make it. This is the"
                + " same bar as a `docs/decisions/` entry: a choice a later reader could not recover from the code"
                + " | `task_spec.md`: Part 1-shaped, scoped to the gap, opening with \"What this adds to the Story"
                + " spec\". It must not contradict the story spec; a contradiction is a conflict |",
        "| `story_conflict` | **Conflict:** following the story spec as written would be wrong given current code"
                + " or a merged sibling, or the spec contradicts itself or the Task | `story_conflict.md`: what the"
                + " spec says (quoted, with section), what the code or merged change shows (`file:line`), why both"
                + " can't hold, and a proposed amendment |",
        "| `escalate` | The Task is already done, should be split, or is not this Story's work | — (the Supervisor"
                + " decides) |"
    };

    private InMemoryGraph graph;

    @BeforeEach
    void seed() {
        graph = new InMemoryGraph();
        TaskDeliveryGraph.seed(graph.writer, graph.template, Map.of());
    }

    private static String collapsed(String prompt) {
        return prompt.replaceAll("\\s+", " ");
    }

    private static String prompt(String name) {
        return switch (name) {
            case "analyze" -> ANALYZE_PROMPT;
            case "task_spec_review" -> TASK_SPEC_REVIEW_PROMPT;
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void analyzePromptNamesTheDecisionsAndTheFilesEachWrites() {
        assertThat(ANALYZE_PROMPT)
                .contains("planned")
                .contains("needs_spec")
                .contains("story_conflict")
                .contains("docs/decisions")
                .contains("Realises:")
                .contains("/workspace/in/run_input/story_spec.md")
                .contains("plan.md")
                .contains("task_spec.md")
                .contains("story_conflict.md")
                .contains("What this adds to the Story spec")
                .contains("Revisions");
    }

    @Test
    void analyzePromptCarriesTheDecisionTableVerbatim() {
        String table = String.join("\n", DECISION_TABLE);
        assertThat(ANALYZE_PROMPT)
                .as("the rubric is a written test; a paraphrase drifts from the design's bar")
                .contains(table);
    }

    @Test
    void analyzePromptDefinesThePlanContract() {
        String p = collapsed(ANALYZE_PROMPT);
        assertThat(p)
                .as("Implement and Code Review cut the story spec to the headings on this line")
                .contains("The first line of plan.md is exactly: Realises:")
                .contains("exactly as written");
        assertThat(ANALYZE_PROMPT)
                .containsSubsequence(
                        "## Task ordering",
                        "## Cross-repo sync points",
                        "## Expected Changed Files",
                        "## File-level test cases",
                        "## Migrations",
                        "## Verification commands");
    }

    @Test
    void analyzePromptPlansAgainstCurrentMainOnEveryIteration() {
        String p = collapsed(ANALYZE_PROMPT);
        assertThat(p)
                .contains("/workspace/in/await_story_revision/human_guidance.md")
                .contains("/workspace/in/approve_task_spec/human_guidance.md")
                .as("a resumed Task re-plans from the Revisions entries newer than the version it raised against")
                .contains("read every Revisions entry newer than")
                .as("an answer of holds settles the conflict unless something the entry did not weigh changed")
                .contains("**holds** — an entry answers that the spec stands as written")
                .contains("evidence the entry did not weigh")
                .contains("**amended** — an entry amends the spec for it")
                .as("a conflict raised while a revision was open is released after it with no entry of its own")
                .contains("**no entry names your conflict**")
                .contains("Apply the decision test again against the revised spec, and raise `story_conflict`"
                        + " again if the conflict still stands")
                .doesNotContain("One of them answers your conflict")
                .as("transcripts and observations do not carry over; decisions do")
                .contains("plans from scratch against the default branch as it is now");
    }

    @Test
    void analyzePromptEscalatesWithAStubPlanWhenTheStorySpecIsMissing() {
        String p = collapsed(ANALYZE_PROMPT);
        assertThat(p)
                .as("plan.md is a required output even on escalate, and no Realises heading can exist")
                .contains("write a one-line plan.md naming the missing input, and escalate")
                .contains("Unless the story spec was missing, plan.md's first line is the Realises line");
    }

    @Test
    void analyzePromptKeepsTheTaskSpecFromForkingTheStorySpec() {
        String p = collapsed(ANALYZE_PROMPT);
        assertThat(p)
                .contains("Never settle a conflict in task_spec.md")
                .as("Implement draws the pull request's Context and Approach from the Task spec")
                .contains("## Context")
                .contains("## Approach")
                .contains("## Technical Decisions")
                .contains("## Caveats")
                .as("Implement proposes the Task spec's Future work; Analyze proposes nothing")
                .contains("Do not run propose-roadmap");
    }

    @Test
    void taskSpecReviewPromptReviewsAgainstTheStorySpec() {
        String p = collapsed(TASK_SPEC_REVIEW_PROMPT);
        assertThat(TASK_SPEC_REVIEW_PROMPT).contains("{review_history}");
        assertThat(p)
                .contains("must not contradict")
                .as("the server picks the newest of Analyze's and the reviewer's copies; the agent never does")
                .contains("/workspace/in/plan/plan.md")
                .contains("/workspace/in/plan/task_spec.md")
                .contains("The server picks the newest copy from Analyze and your previous iteration")
                .doesNotContain("/workspace/in/analyze/")
                .contains("/workspace/in/task_spec_review/task_spec_review.md")
                .contains("/workspace/in/run_input/story_spec.md")
                .contains("/workspace/out/plan.md")
                .contains("/workspace/out/task_spec.md")
                .contains("/workspace/out/task_spec_review.md")
                .as("only Analyze raises a Story conflict")
                .contains("only Analyze raises");
    }

    @Test
    void taskSpecReviewNotesAStoryWideChoiceForTheHumanWithoutEscalating() {
        String p = collapsed(TASK_SPEC_REVIEW_PROMPT);
        assertThat(p)
                .as("Analyze sends such a gap here as needs_spec; escalating it would loop on the same story spec")
                .contains("must make the same way is neither a flaw nor a reason to escalate")
                .contains("under `## For the human: Story-wide choice`, so the human at Approve Task Spec");

        String escalation = p.substring(p.indexOf("## When to escalate"), p.indexOf("## Re-checks"));
        assertThat(escalation).doesNotContain("must make the same way").doesNotContain("Story-wide");
    }

    @Test
    void taskSpecReviewPromptHandlesBothRechecks() {
        String p = collapsed(TASK_SPEC_REVIEW_PROMPT);
        assertThat(p)
                .as("drift: the held approval re-applies without a human")
                .contains("# Re-check against current main")
                .as("a story revision mid-Task makes its Revisions entries the round's check target")
                .contains("names entries from the story spec's Revisions")
                .contains("those entries are the check target");
    }

    @Test
    void deliveryNodesRunTheSharedTaskDeliveryPrompts() {
        assertThat(graph.definition(TaskDeliveryGraph.IMPLEMENT).getPromptTemplate())
                .isEqualTo(DeliveryPrompts.implement(SpecSource.TASK_DELIVERY));
        assertThat(graph.definition(TaskDeliveryGraph.CODE_REVIEW).getPromptTemplate())
                .isEqualTo(DeliveryPrompts.codeReview(SpecSource.TASK_DELIVERY));
    }

    @Test
    void noPromptReachableFromBothBranchesUsesAnInputPlaceholder() {
        // Implement runs after `planned` and after the Task spec gate; an {input.<label>} for a node
        // one branch skips is unresolved there and fails the node.
        for (String label : new String[] {TaskDeliveryGraph.IMPLEMENT, TaskDeliveryGraph.CODE_REVIEW}) {
            assertThat(graph.definition(label).getPromptTemplate()).as(label).doesNotContain("{input.");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                TaskDeliveryGraph.ANALYZE,
                TaskDeliveryGraph.TASK_SPEC_REVIEW,
                TaskDeliveryGraph.IMPLEMENT,
                TaskDeliveryGraph.CODE_REVIEW
            })
    void everyPlaceholderIsOneTheServerSupplies(String label) throws Exception {
        NodeDefinition def = graph.definition(label);
        assertThat(def.getExecutorType()).isEqualTo(ExecutorType.ai);

        Set<String> supplied = new HashSet<>(Set.of("run.feature_request"));
        // The orchestrator loads review history only for a node in a loop group.
        if (JSON.readTree(graph.node(label).getConfigOverrides()).hasNonNull("loop_group")) {
            supplied.add("review_history");
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(def.getPromptTemplate());
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).isSubsetOf(supplied);
    }

    @Test
    void everyAiNodeIsCoveredByThePlaceholderCheck() {
        assertThat(graph.nodes().stream()
                        .filter(n -> graph.definition(n.getLabel()).getExecutorType() == ExecutorType.ai)
                        .map(n -> n.getLabel()))
                .containsExactlyInAnyOrder(
                        TaskDeliveryGraph.ANALYZE,
                        TaskDeliveryGraph.TASK_SPEC_REVIEW,
                        TaskDeliveryGraph.IMPLEMENT,
                        TaskDeliveryGraph.CODE_REVIEW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"analyze", "task_spec_review"})
    void noRunScopedReferences(String name) {
        assertThat(prompt(name))
                .as("an ordinal resolves against whichever spec the next reader holds")
                .doesNotContainPattern("(Decision|Caveat|Task) +(N|#?[0-9]+)\\b")
                .doesNotContain("§");
    }

    @ParameterizedTest
    @ValueSource(strings = {"analyze", "task_spec_review"})
    void neverNamesFeatureDevelopmentNodes(String name) {
        assertThat(prompt(name))
                .as("Task Delivery has none of these; an agent told to read them finds nothing")
                .doesNotContain("spec_and_plan")
                .doesNotContain("draft_spec")
                .doesNotContain("Part 2");
    }
}
