package com.choruskube.core.config;

import static com.choruskube.core.config.StoryDesignPrompts.DRAFT_STORY_SPEC_PROMPT;
import static com.choruskube.core.config.StoryDesignPrompts.STORY_SPEC_REVIEW_PROMPT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The prompts are the only place the Story spec's shape reaches an agent, so a missing clause is
 * silent — the agent writes Feature Development's spec instead.
 */
class StoryDesignPromptConventionsTest {

    /** The worker's placeholder syntax: any match it cannot resolve fails the node. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_.]*)\\}");

    private static final Set<String> SUPPLIED_PLACEHOLDERS =
            Set.of("run.story_request", "run.revision_context", "review_history");

    private static String collapsed(String prompt) {
        return prompt.replaceAll("\\s+", " ");
    }

    private static String prompt(String name) {
        return switch (name) {
            case "draft" -> DRAFT_STORY_SPEC_PROMPT;
            case "review" -> STORY_SPEC_REVIEW_PROMPT;
            default -> throw new IllegalArgumentException(name);
        };
    }

    @Test
    void draftPromptReadsTheRequestAndRevisionInputs() {
        assertThat(DRAFT_STORY_SPEC_PROMPT)
                .contains("{run.story_request}")
                .contains("{run.revision_context}")
                .contains("/workspace/in/run_input/story_spec.md")
                .contains("prior_review_v")
                .contains("propose-roadmap");
    }

    @Test
    void draftPromptDefinesEveryStorySpecSection() {
        assertThat(DRAFT_STORY_SPEC_PROMPT)
                .contains("## Context")
                .contains("## Approach")
                .contains("## Architecture")
                .contains("## Technical Decisions")
                .contains("## Caveats")
                .contains("## Testing Strategy")
                .contains("## Manual Operations")
                .contains("## Tasks")
                .contains("## Revisions");
    }

    @Test
    void draftPromptLeavesFileLevelPlanningToEachTask() {
        assertThat(DRAFT_STORY_SPEC_PROMPT)
                .doesNotContain("## Expected Changed Files")
                .doesNotContain("Implementation Plan");
    }

    @Test
    void draftPromptMakesEveryTaskStandAloneAndOwnsEverySection() {
        String p = collapsed(DRAFT_STORY_SPEC_PROMPT);
        assertThat(p)
                .as("a Task that cannot merge alone blocks its siblings' PRs")
                .contains("its own PR set")
                .as("Task Delivery's plan names the headings it realises")
                .contains("Realises:")
                .as("a section no Task realises is designed and never built")
                .contains("realised by at least one Task")
                .as("the Realises line is comma-separated")
                .contains("no heading outside Tasks and Revisions may contain a comma");
    }

    @Test
    void draftPromptKeepsRevisionsAppendOnlyAndAnswersEveryConflict() {
        String p = collapsed(DRAFT_STORY_SPEC_PROMPT);
        assertThat(p)
                .contains("append-only")
                .contains("amended")
                .contains("holds")
                .as("a parked Task re-plans from the Revisions entries newer than its version")
                .contains("a change the entry does not name is invisible");
    }

    @Test
    void draftPromptEditsTheCurrentSpecInRevisionMode() {
        String p = collapsed(DRAFT_STORY_SPEC_PROMPT);
        assertThat(p)
                .as("revision mode is keyed off the revision context, not a separate template")
                .contains("When the revision context above is empty")
                .contains("Edit it; do not rewrite it")
                .as("Tasks cite sections by heading, so a renamed heading breaks every citation")
                .contains("Keep every heading's text unchanged");
    }

    @Test
    void draftPromptPlacesProposalsUnderTheStoryOrItsEpic() {
        String p = collapsed(DRAFT_STORY_SPEC_PROMPT);
        assertThat(p)
                .as("this Story's Tasks are anchored under it")
                .contains("{\"existingId\": \"<Story id>\", \"tasks\": [...]}")
                .as("today's roadmap_extension placement for Future work")
                .contains("a new Epic only for a genuinely separate initiative")
                .as("existing Tasks are never edited or removed by the run")
                .contains("Never edit or remove an existing Task")
                .as("a revision must not re-create what the last approval already created")
                .contains("already on the roadmap");
    }

    @Test
    void reviewPromptChecksTheStorySpecShapeAndDecides() {
        assertThat(STORY_SPEC_REVIEW_PROMPT)
                .contains("{review_history}")
                .contains("## Tasks")
                .contains("## Revisions")
                .contains("approved")
                .contains("revised")
                .contains("escalate");
    }

    @Test
    void reviewPromptChecksEveryConflictIsAnswered() {
        String p = collapsed(STORY_SPEC_REVIEW_PROMPT);
        assertThat(p)
                .contains("{run.revision_context}")
                .contains("/workspace/in/run_input/story_spec.md")
                .contains("prior_review_v")
                .contains("amended")
                .contains("holds");
    }

    @Test
    void reviewPromptOwnsTheProposalTheGateReads() {
        String p = collapsed(STORY_SPEC_REVIEW_PROMPT);
        assertThat(p)
                .contains("propose-roadmap")
                .as("a review that skips the install hands the gate a stale copy")
                .contains("{\"epics\": []}")
                .contains("/workspace/out/story_spec.md")
                .contains("/workspace/out/story_spec_review.md");
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "review"})
    void everyPlaceholderIsOneTheServerSupplies(String name) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(prompt(name));
        while (m.find()) {
            found.add(m.group(1));
        }
        assertThat(found).isSubsetOf(SUPPLIED_PLACEHOLDERS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "review"})
    void noRunScopedReferences(String name) {
        String p = prompt(name);
        assertThat(p)
                .as("an ordinal resolves against whichever spec the next reader holds")
                .doesNotContainPattern("(Decision|Caveat|Task) +(N|#?[0-9]+)\\b")
                .doesNotContain("§")
                .doesNotContainPattern("(?i)\\bthe plan\\b");
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "review"})
    void neverNamesFeatureDevelopmentNodes(String name) {
        assertThat(prompt(name))
                .doesNotContain("spec_and_plan")
                .doesNotContain("Part 1")
                .doesNotContain("Part 2");
    }
}
