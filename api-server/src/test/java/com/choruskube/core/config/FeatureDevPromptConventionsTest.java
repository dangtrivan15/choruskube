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
    void specPromptRequiresPerRepoSplit() throws Exception {
        assertThat(promptField("SPEC_AND_PLAN_PROMPT")).contains("per-repo").contains("privacy");
    }

    @Test
    void noPromptCitesAPastRunsSpec() throws Exception {
        for (String name : new String[] {"SPEC_AND_PLAN_PROMPT", "IMPLEMENT_PROMPT", "CODE_REVIEW_PROMPT"}) {
            assertThat(promptField(name))
                    .as("%s must not cite a past run's spec", name)
                    .doesNotContain("in the spec)");
        }
    }
}
