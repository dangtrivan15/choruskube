package com.choruskube.core.config;

import static com.choruskube.core.config.DeliveryPrompts.SpecSource.FEATURE_DEVELOPMENT;
import static com.choruskube.core.config.DeliveryPrompts.SpecSource.TASK_DELIVERY;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class DeliveryPromptsTest {

    // Taken from BaseFeatureDevSeeder's IMPLEMENT_PROMPT and CODE_REVIEW_PROMPT at version 53, before
    // the text moved here. A change to Feature Development's text must bump CURRENT_VERSION, and
    // these hashes with it; a mismatch without a bump means a refactor changed what agents read.
    static final String IMPLEMENT_SHA256 = "580e1b0918c84a3dcfb0b7e2c9f46f6886bb78f7987822aa1f95a24451f11ecf";
    static final String CODE_REVIEW_SHA256 = "c6bb741fc5ee421513af587560b3f4b7cd3fd9acd591cc1d13f0d006c490afe9";

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    @Test
    void featureDevelopmentPromptsAreByteIdentical() throws Exception {
        assertThat(BaseFeatureDevSeeder.CURRENT_VERSION).isEqualTo(53);
        assertThat(sha256(DeliveryPrompts.implement(FEATURE_DEVELOPMENT))).isEqualTo(IMPLEMENT_SHA256);
        assertThat(sha256(DeliveryPrompts.codeReview(FEATURE_DEVELOPMENT))).isEqualTo(CODE_REVIEW_SHA256);
    }

    @Test
    void taskDeliveryVariants_readPlanAndStorySpec() {
        String implement = DeliveryPrompts.implement(TASK_DELIVERY);
        assertThat(implement)
                .contains("/workspace/in/plan/plan.md")
                .contains("/workspace/in/plan/task_spec.md")
                .contains("/workspace/in/run_input/story_spec.md")
                .contains("Realises:")
                .doesNotContain("{input.draft_spec_and_plan.result}")
                .doesNotContain("spec_review/spec_and_plan.md");

        String codeReview = DeliveryPrompts.codeReview(TASK_DELIVERY);
        assertThat(codeReview).contains("/workspace/in/plan/plan.md").contains("Revisions");

        for (String prompt : new String[] {implement, codeReview}) {
            assertThat(prompt)
                    .as("an {input.<label>} placeholder for a node on the optional branch fails the node")
                    .doesNotContain("{input.");
        }
    }

    @Test
    void taskDeliveryVariantsNameNoFeatureDevelopmentNodeOrSpecPart() {
        for (String prompt :
                new String[] {DeliveryPrompts.implement(TASK_DELIVERY), DeliveryPrompts.codeReview(TASK_DELIVERY)}) {
            assertThat(prompt)
                    .as("Task Delivery has none of these nodes; an agent told to read them finds nothing")
                    .doesNotContain("draft_spec_and_plan")
                    .doesNotContain("spec_review")
                    .doesNotContain("approve_spec_and_plan")
                    .as("the spec-and-plan document's two parts do not exist in Task Delivery")
                    .doesNotContain("Part 1")
                    .doesNotContain("Part 2")
                    .doesNotContainPattern("§\\s*[0-9]");
        }
    }

    @Test
    void taskDeliveryImplementTakesThePrBodyAndDecisionsFromTheSpecThatStatesThem() {
        String collapsed = DeliveryPrompts.implement(TASK_DELIVERY).replaceAll("\\s+", " ");
        assertThat(collapsed)
                .as("plan.md is executed; the specs settle what it leaves ambiguous, Task spec first")
                .contains("EXECUTE it step by step")
                .contains("When `plan.md` is ambiguous about intent, read `task_spec.md` first")
                .as("PR bodies draw Context and Approach from the Task spec, else the realised story sections")
                .contains("`task_spec.md` when it is present; otherwise it is the story spec")
                .as("a story spec section another Task realises is that Task's to graduate")
                .contains("Take each decision from whichever spec states it")
                .as("the Story's own deferrals were proposed at the Story gate")
                .contains("never propose them again");
    }

    @Test
    void taskDeliveryCodeReviewChecksThePlanAndEscalatesWhenItNoLongerHolds() {
        String collapsed = DeliveryPrompts.codeReview(TASK_DELIVERY).replaceAll("\\s+", " ");
        assertThat(collapsed)
                .contains("Check the change against this Task's plan, `/workspace/in/plan/plan.md`")
                .contains("is context only")
                .as("a story revision mid-Task makes its Revisions entries the round's check target")
                .contains("those entries are the check target")
                .as("only Analyze raises a Story conflict")
                .contains("If the Task's plan no longer holds")
                .contains("Do not rewrite the plan yourself");
    }

    @Test
    void sharedTextReachesBothSources() {
        for (DeliveryPrompts.SpecSource source : DeliveryPrompts.SpecSource.values()) {
            assertThat(DeliveryPrompts.implement(source))
                    .as("%s implement", source)
                    .contains("## Opening and updating pull requests")
                    .contains("## Proposing deferred work to the roadmap")
                    .contains("git push --force-with-lease origin HEAD")
                    .endsWith("{review_history}");
            assertThat(DeliveryPrompts.codeReview(source))
                    .as("%s code review", source)
                    .contains("## Roadmap proposal")
                    .contains("## When to escalate")
                    .contains("{review_history}");
        }
    }
}
