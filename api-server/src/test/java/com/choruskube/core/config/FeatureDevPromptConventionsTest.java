package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/**
 * The prompts are the only place these conventions reach an agent that has not read
 * CLAUDE.md yet, so a missing clause is silent — the agent simply behaves the old way.
 */
class FeatureDevPromptConventionsTest {

    private static String promptField(String name) throws Exception {
        Field f = BaseFeatureDevSeeder.class.getDeclaredField(name);
        f.setAccessible(true);
        return (String) f.get(null);
    }

    @Test
    void implementPromptDefersToRepoConventions() throws Exception {
        assertThat(promptField("IMPLEMENT_PROMPT"))
                .contains("read that repo's CLAUDE.md")
                .contains("Its conventions override these instructions");
    }

    @Test
    void codeReviewPromptDefersToRepoConventions() throws Exception {
        assertThat(promptField("CODE_REVIEW_PROMPT")).contains("read that repo's CLAUDE.md");
    }

    @Test
    void implementPromptBoundsDecisionFilesToOnePerRun() throws Exception {
        assertThat(promptField("IMPLEMENT_PROMPT")).contains("exactly one").contains("never create a second one");
    }

    @Test
    void implementPromptRoutesSpecSectionsByMutability() throws Exception {
        String p = promptField("IMPLEMENT_PROMPT");
        assertThat(p).contains("docs/decisions/").contains("ARCHITECTURE.md");
        assertThat(p)
                .as("architecture must merge in place, or docs/ grows once per run")
                .contains("rewritten in place");
        assertThat(p)
                .as("a decision graduates on demand, not in bulk")
                .contains("only when something in this repo cites it");
    }

    @Test
    void implementPromptRoutesFutureWorkOnlyThroughTheProposal() throws Exception {
        String collapsed = promptField("IMPLEMENT_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("a hand-filed issue has no Task, never closes, and duplicates the Task's own issue")
                .contains("Never file a GitHub issue for one yourself")
                .doesNotContain("A direct GitHub issue is still the right tool")
                .doesNotContain("prefer this over creating a GitHub issue yourself");
        assertThat(collapsed)
                .as("a proposed Task's text lands verbatim in an issue in a possibly PUBLIC repo")
                .contains("become a GitHub issue in the repo it is about")
                .contains("visibility");
    }

    @Test
    void implementPromptWritesUpTheSpecsProposalAndMarksAdditions() throws Exception {
        String collapsed = promptField("IMPLEMENT_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("an Implement-originated entry reaches the human unmarked and unreviewed")
                .contains("placed as its \"Proposed as\" line says, and nothing else")
                .contains("Origin: approved with the spec")
                .contains("Origin: added during implementation")
                .contains("(found during implementation)");
    }

    @Test
    void implementPromptRepairsReferencesLeftDanglingByGraduation() throws Exception {
        assertThat(promptField("IMPLEMENT_PROMPT"))
                .as("graduating some decisions and not others is what strands a reference")
                .contains("Resolve or delete every such reference");
    }

    @Test
    void specPromptRequiresPerRepoSplit() throws Exception {
        assertThat(promptField("SPEC_AND_PLAN_PROMPT")).contains("per-repo").contains("privacy");
    }

    @Test
    void specPromptDefaultsGapsIntoTheRun() throws Exception {
        String collapsed = promptField("SPEC_AND_PLAN_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("without an in-scope default, finishable work is deferred into a follow-up run")
                .contains("Every gap you find is in scope unless it is clearly not")
                .contains("Size alone is never a reason to defer")
                .contains("name exactly what to change rather than deferring it");
    }

    @Test
    void specPromptSeparatesOutOfScopeFromDeferral() throws Exception {
        String collapsed = promptField("SPEC_AND_PLAN_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("out-of-scope work tagged Future work becomes a Task that Autopilot runs")
                .contains("An Accepted caveat creates nothing")
                .contains("its own design decision with real alternatives")
                .contains("a contract, schema or component this change does not otherwise touch")
                .contains("an investigation or measurement, one that can run now")
                .contains("Most runs defer nothing");
    }

    @Test
    void specPromptTurnsWorkConditionsIntoDependenciesAndEventsIntoAccepted() throws Exception {
        String collapsed = promptField("SPEC_AND_PLAN_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("a Task standing for an event starts at once and invents work")
                .contains("ask whether a run could do X and finish it")
                .contains("Future work that depends on X")
                .contains("X is an event");
    }

    @Test
    void specPromptProposesFollowUpsWithoutWritingTheRoadmapDocument() throws Exception {
        String collapsed = promptField("SPEC_AND_PLAN_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("the human approves follow-ups with the spec; Implement writes the document")
                .contains("**Proposed as:**")
                .contains("Do not run propose-roadmap yourself");
    }

    @Test
    void specReviewEnforcesTheDeferralBar() throws Exception {
        String collapsed = promptField("SPEC_REVIEW_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("a reviewer that flags absorbed gaps as scope creep pushes the drafter back to deferring")
                .contains("closes a gap this change creates or exposes is justified, not scope creep")
                .contains("names no deferral criterion");
    }

    /**
     * BaseFeatureDevSeeder is exempt from scripts/check-comment-refs.sh as a whole file, because
     * its prompt strings define the spec format the ordinals belong to. This test is the only
     * thing standing in for the guard inside that exemption, so it checks the shapes a citation
     * actually takes rather than one of them.
     */
    @Test
    void implementPromptRequiresAnIndexRowPerGraduatedEntry() throws Exception {
        // Marking a superseded entry is not enough on its own: a run that supersedes
        // nothing must still register, or the index stays empty and the supersession
        // check below it has nothing to read.
        assertThat(promptField("IMPLEMENT_PROMPT"))
                .contains("docs/decisions/README.md")
                .contains("its own row")
                .contains("newest last");
    }

    @Test
    void implementPromptKeepsGraduatedEntriesImmutable() throws Exception {
        assertThat(promptField("IMPLEMENT_PROMPT"))
                .as("a reversal adds an entry and marks the old one; it never edits it")
                .contains("do not edit it")
                .contains("superseded by");
    }

    @Test
    void implementPromptDocumentsTheRoadmapProposalContract() throws Exception {
        String p = promptField("IMPLEMENT_PROMPT");
        assertThat(p)
                .contains("propose-roadmap")
                .contains("existingId")
                .contains("roadmap_candidates.json")
                .contains("Do not anchor a new Story or Task under any other existing Epic")
                .contains("/workspace/in/implement/roadmap_candidates.json");
    }

    @Test
    void implementPromptNoLongerOffersARoadmapItemAsAFutureWorkHome() throws Exception {
        String collapsed = promptField("IMPLEMENT_PROMPT").replaceAll("\\s+", " ");
        assertThat(collapsed).doesNotContain("A roadmap item is an acceptable home");
    }

    @Test
    void proposingDeferredWorkSectionHasNoUnknownPlaceholders() throws Exception {
        String p = promptField("IMPLEMENT_PROMPT");
        int start = p.indexOf("## Proposing deferred work to the roadmap");
        int end = p.indexOf("## Opening and updating pull requests");
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String section = p.substring(start, end);
        assertThat(section).doesNotContainPattern("\\{[a-zA-Z_][a-zA-Z0-9_.]*\\}");
    }

    @Test
    void noPromptCitesAPastRunsSpec() throws Exception {
        for (String name :
                new String[] {"SPEC_AND_PLAN_PROMPT", "SPEC_REVIEW_PROMPT", "IMPLEMENT_PROMPT", "CODE_REVIEW_PROMPT"}) {
            assertThat(promptField(name))
                    .as("%s must not cite a past run's spec", name)
                    .doesNotContain("in the spec)")
                    .doesNotContain("see the spec")
                    .doesNotContain("the spec's Caveat")
                    .doesNotContain("see Decision");
        }
    }
}
