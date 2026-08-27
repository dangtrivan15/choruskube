package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Pre-flight gating test asserting that {@code git_repo_list} is no longer in active use before
 * Phase 3 cleanup is shipped. All three tests must pass for the cleanup to be safe.
 *
 * <p>Test 2 is expected to fail until the v16 Feature Dev seeder block is dropped — that
 * failure is the gate. If test 2 fails for any other template, STOP and investigate.
 */
public class Phase3GatingTest extends BaseTest {

    /** Non-terminal statuses: the complement of {completed, failed, cancelled}. */
    private static final Set<WorkflowRunStatus> NON_TERMINAL_STATUSES = EnumSet.of(
            WorkflowRunStatus.pending,
            WorkflowRunStatus.running,
            WorkflowRunStatus.paused,
            WorkflowRunStatus.awaiting_human,
            WorkflowRunStatus.awaiting_retry,
            WorkflowRunStatus.live_chat);

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Asserts that no non-terminal workflow_run rows reference the v16 Feature Dev template.
     * If v16 is not seeded the assertion trivially passes (treat "no template" as "zero active
     * runs"). Failure here means Phase 3 cleanup must be deferred until in-flight runs finish.
     */
    @Test
    void no_active_runs_reference_v16_feature_dev() {
        var v16 = templateRepo.findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, 16);
        if (v16.isEmpty()) {
            // v16 not seeded — trivially no active runs reference it
            return;
        }

        boolean hasActive = runRepo.existsByGraphTemplateIdAndStatusIn(v16.get().getId(), NON_TERMINAL_STATUSES);
        assertThat(hasActive)
                .as("Expected zero non-terminal workflow_run rows referencing the v16 Feature Dev template, "
                        + "but found at least one. Defer Phase 3 cleanup until those runs reach a terminal state.")
                .isFalse();
    }

    /**
     * Asserts that no graph_template row (any version, any graph) declares an input field with
     * {@code type = "git_repo_list"}. This is the load-bearing gate.
     *
     * <p><b>Expected failure</b>: this test fails until the v16 Feature Dev seeder block is
     * dropped. Confirm the failure message names only the v16 Feature Dev template; any other
     * template tripping this assertion is a real signal requiring investigation.
     */
    @Test
    void no_active_templates_declare_git_repo_list_input() throws Exception {
        List<GraphTemplate> allTemplates = templateRepo.findAll();
        List<String> offenders = new ArrayList<>();

        for (GraphTemplate template : allTemplates) {
            String schema = template.getInputSchema();
            if (schema == null || schema.isBlank()) {
                continue;
            }

            JsonNode schemaArray = objectMapper.readTree(schema);
            if (!schemaArray.isArray()) {
                continue;
            }

            for (JsonNode field : schemaArray) {
                JsonNode typeNode = field.get("type");
                if (typeNode != null && "git_repo_list".equals(typeNode.asText())) {
                    offenders.add(String.format(
                            "graphId=%s version=%d fieldName=%s",
                            template.getGraphId(),
                            template.getVersion(),
                            field.get("name").asText()));
                    break; // one offence per template is enough to report
                }
            }
        }

        assertThat(offenders)
                .as("Found graph_template rows that still declare a git_repo_list input field. "
                        + "Expected only the v16 Feature Dev template here. "
                        + "Any other entry is unexpected and must be investigated.")
                .isEmpty();
    }

    /**
     * Static-analysis-style assertion: reads the {@code DefaultEpicService} source file from the
     * filesystem and asserts it does not contain the legacy strings {@code git_repo_list} or
     * {@code repos":[}. Belt-and-braces — the DB-level tests above are the load-bearing ones.
     *
     * <p>Retargeted from {@code FeatureProposalService.java} (deleted by the work-hierarchy
     * migration) to {@code DefaultEpicService.java} — the concrete implementation that now owns
     * this response-building code path (Epic is the entity carrying the
     * initiative-level software-project/repo relationship), not {@code DefaultStoryService.java}
     * or {@code DefaultTaskService.java}, and not the {@code EpicService.java} interface file,
     * which has no method bodies for this static analysis to inspect.
     */
    @Test
    void default_epic_service_does_not_emit_git_repo_list() throws IOException {
        // When gradle runs api-server tests, the working directory is api-server/
        Path serviceFile = Paths.get("src/main/java/com/choruskube/core/service/DefaultEpicService.java");

        if (!Files.exists(serviceFile)) {
            fail("DefaultEpicService.java not found at expected path: "
                    + serviceFile.toAbsolutePath()
                    + " — check that tests are run from the api-server/ directory.");
        }

        String content = Files.readString(serviceFile);
        assertThat(content).doesNotContain("git_repo_list").doesNotContain("repos\":[");
    }
}
