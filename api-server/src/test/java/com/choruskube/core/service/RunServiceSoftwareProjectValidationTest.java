package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Integration test for RunService.validateInputs() with software_project_id type.
 *
 * <p>Intentionally does NOT extend a class-level {@code @Transactional} BaseTest, so the
 * production code path (including the lack of an outer transaction at validateInputs's
 * call site) is faithfully exercised. If validateInputs ever regresses to walking the
 * lazy {@code RepoGroup.members} collection without a transaction, the
 * {@code repo_group} test below will fail with LazyInitializationException — that's the
 * regression guard.
 */
class RunServiceSoftwareProjectValidationTest extends BaseTest {

    @MockitoBean
    WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    WorkflowClient workflowClient;

    @Autowired
    RunService runs;

    @Autowired
    RepoGroupService groupService;

    @Autowired
    GraphTemplateRepository templateRepo;

    @Autowired
    GitRepoRepository gitRepoRepo;

    @Autowired
    RepoGroupRepository repoGroupRepo;

    @Test
    void validate_inputs_with_git_repo_software_project_passes() {
        GitRepo r = createGitRepo("https://github.com/test/r-" + suffix());
        GraphTemplate t = spTemplate();
        assertThatCode(() -> runs.validateInputs(
                        t.getInputSchema(),
                        Map.of("software_project_id", r.getId().toString(), "feature_request", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_inputs_with_repo_group_software_project_passes() {
        // CRITICAL: this test exercises the production lazy-load path. If validateInputs
        // is changed to walk RepoGroup.members directly without a transaction (or a count
        // query), this test will fail with LazyInitializationException — that's the protection.
        GitRepo m1 = createGitRepo("https://github.com/test/m1-" + suffix());
        GitRepo m2 = createGitRepo("https://github.com/test/m2-" + suffix());
        RepoGroup g = groupService.create("g-" + suffix(), "registry/agent:v1", null, List.of(m1.getId(), m2.getId()));
        GraphTemplate t = spTemplate();
        assertThatCode(() -> runs.validateInputs(
                        t.getInputSchema(),
                        Map.of("software_project_id", g.getId().toString(), "feature_request", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_inputs_software_project_from_other_org_is_allowed_under_always_allow() {
        GitRepo foreign = new GitRepo();
        String foreignUrl = "https://github.com/test/foreign-" + suffix();
        foreign.setUrl(foreignUrl);
        foreign.setName(RepoNameUtil.deriveOwnerRepoName(foreignUrl));
        foreign.setSecrets("[]");
        foreign = gitRepoRepo.save(foreign);
        GraphTemplate t = spTemplate();
        UUID id = foreign.getId();
        assertThatCode(() -> runs.validateInputs(
                        t.getInputSchema(), Map.of("software_project_id", id.toString(), "feature_request", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_inputs_software_project_not_found_is_rejected() {
        GraphTemplate t = spTemplate();
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> runs.validateInputs(
                        t.getInputSchema(), Map.of("software_project_id", missing.toString(), "feature_request", "x")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validate_inputs_invalid_uuid_is_rejected() {
        GraphTemplate t = spTemplate();
        assertThatThrownBy(() -> runs.validateInputs(
                        t.getInputSchema(), Map.of("software_project_id", "not-a-uuid", "feature_request", "x")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validate_inputs_missing_required_software_project_is_rejected() {
        GraphTemplate t = spTemplate();
        assertThatThrownBy(() -> runs.validateInputs(t.getInputSchema(), Map.of("feature_request", "x")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void validate_inputs_with_empty_repo_group_is_rejected() {
        // REGRESSION GUARD: this test specifically exercises the production lazy-load path.
        // RepoGroupService.create rejects empty member lists, so we save directly via the
        // repository to construct an empty group. With the count-query fix, validation
        // correctly rejects with "no repositories". With the buggy lazy-walk version,
        // RepoGroup.members.stream() throws LazyInitializationException — which the
        // outer catch in validateInputs silently swallows, leaving validation to pass.
        // That difference is what this test asserts: a rejection-on-empty MUST happen.
        RepoGroup empty = new RepoGroup();
        empty.setName("empty-" + suffix());
        empty.setAgentImage("registry/agent:v1");
        empty = repoGroupRepo.save(empty);
        GraphTemplate t = spTemplate();
        UUID id = empty.getId();
        assertThatThrownBy(() -> runs.validateInputs(
                        t.getInputSchema(), Map.of("software_project_id", id.toString(), "feature_request", "x")))
                .isInstanceOf(ValidationException.class);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private GitRepo createGitRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r.setSecrets("[]");
        return gitRepoRepo.save(r);
    }

    private GraphTemplate spTemplate() {
        String s = suffix();
        GraphTemplate t = new GraphTemplate();
        t.setName("SP Validation Test " + s);
        t.setGraphId("sp-validation-test-" + s);
        t.setVersion(1);
        t.setInputSchema(
                "[{\"name\":\"software_project_id\",\"label\":\"Software Project\",\"type\":\"software_project_id\",\"required\":true},"
                        + "{\"name\":\"feature_request\",\"label\":\"Feature\",\"type\":\"textarea\",\"required\":true}]");
        return templateRepo.save(t);
    }
}
