package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.FeatureProposal;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.FeatureProposalStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.FeatureProposalRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.RepoGroupService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class FeatureProposalControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private FeatureProposalRepository proposalRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Test
    void createProposal_returns201_withSoftwareProjectIdShape() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/repo.git");

        var body = Map.of(
                "title", "Add login page",
                "description", "Build a login page with OAuth",
                "motivation", "Users need to authenticate",
                "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Add login page"))
                .andExpect(jsonPath("$.status").value("backlog"))
                .andExpect(jsonPath("$.softwareProject.id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andExpect(jsonPath("$.softwareProject.name").value(repo.getName()))
                .andExpect(jsonPath("$.repos.length()").value(1))
                .andExpect(jsonPath("$.repos[0].id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.repos[0].name").value("repo"))
                .andExpect(jsonPath("$.repos[0].url").value("https://github.com/test/repo.git"))
                .andExpect(jsonPath("$.workflowRunId").isEmpty());
    }

    @Test
    void createProposal_withRepoGroupTarget_returnsRepoGroupTypeAndResolvedRepos() throws Exception {
        GitRepo r1 = createGitRepo("https://github.com/test/group-r1.git");
        GitRepo r2 = createGitRepo("https://github.com/test/group-r2.git");
        RepoGroup group = createRepoGroup("grp-" + UUID.randomUUID().toString().substring(0, 8), r1, r2);

        var body = Map.of(
                "title", "Two-repo feature",
                "description", "Backend + UI",
                "softwareProjectId", group.getId());

        mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softwareProject.id").value(group.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("repo_group"))
                .andExpect(jsonPath("$.softwareProject.name").value(group.getName()))
                .andExpect(jsonPath("$.repos.length()").value(2))
                .andExpect(jsonPath("$.repos[0].id").value(r1.getId().toString()))
                .andExpect(jsonPath("$.repos[0].name").value("group-r1"))
                .andExpect(jsonPath("$.repos[1].id").value(r2.getId().toString()))
                .andExpect(jsonPath("$.repos[1].name").value("group-r2"));
    }

    @Test
    void createProposal_withMissingSoftwareProjectId_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "No project");
        body.put("description", "softwareProjectId required");

        mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createProposal_withInvalidSoftwareProjectId_returns404() throws Exception {
        var body = Map.of(
                "title", "Add login page",
                "description", "Build a login page",
                "softwareProjectId", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createProposal_withCrossOrgSoftwareProjectId_allowedUnderAlwaysAllow() throws Exception {
        GitRepo foreign = new GitRepo();
        String foreignUrl = "https://github.com/other/foreign-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        foreign.setUrl(foreignUrl);
        foreign.setName(RepoNameUtil.deriveOwnerRepoName(foreignUrl));
        foreign = gitRepoRepo.saveAndFlush(foreign);

        var body = Map.of(
                "title", "Cross-org",
                "description", "Allowed in single-tenant",
                "softwareProjectId", foreign.getId());

        mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void listProposals_returnsAll() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/list-all.git");
        createProposal(repo, "Proposal A");
        createProposal(repo, "Proposal B");

        mockMvc.perform(get("/api/v1/feature-proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listProposals_filtersByStatus() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/filter.git");
        createProposal(repo, "Backlog one");
        FeatureProposal inProgress = createProposal(repo, "In progress one");
        inProgress.setStatus(FeatureProposalStatus.in_progress);
        proposalRepo.save(inProgress);

        mockMvc.perform(get("/api/v1/feature-proposals?status=backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Backlog one"));
    }

    @Test
    void getProposal_returnsProposal() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/get.git");
        FeatureProposal p = createProposal(repo, "My Feature");

        mockMvc.perform(get("/api/v1/feature-proposals/" + p.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My Feature"))
                .andExpect(jsonPath("$.status").value("backlog"))
                .andExpect(jsonPath("$.softwareProject.id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"));
    }

    @Test
    void getProposal_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/feature-proposals/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateProposal_inBacklog_returns200() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/update.git");
        FeatureProposal p = createProposal(repo, "Old Title");

        var body = Map.of(
                "title", "New Title",
                "description", "Updated description",
                "softwareProjectId", repo.getId());

        mockMvc.perform(put("/api/v1/feature-proposals/" + p.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"));
    }

    @Test
    void updateProposal_changesSoftwareProjectId() throws Exception {
        GitRepo r1 = createGitRepo("https://github.com/test/upd-1.git");
        GitRepo r2 = createGitRepo("https://github.com/test/upd-2.git");
        FeatureProposal p = createProposal(r1, "Swap project");

        var body = Map.of(
                "title", "Swap project",
                "description", "Switch target",
                "softwareProjectId", r2.getId());

        mockMvc.perform(put("/api/v1/feature-proposals/" + p.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.softwareProject.id").value(r2.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"));
    }

    @Test
    void updateProposal_notInBacklog_returns409() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/update-conflict.git");
        FeatureProposal p = createProposal(repo, "Title");
        p.setStatus(FeatureProposalStatus.in_progress);
        proposalRepo.save(p);

        var body = Map.of(
                "title", "New Title",
                "description", "Updated",
                "softwareProjectId", repo.getId());

        mockMvc.perform(put("/api/v1/feature-proposals/" + p.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteProposal_inBacklog_returns204() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/delete.git");
        FeatureProposal p = createProposal(repo, "To Delete");

        mockMvc.perform(delete("/api/v1/feature-proposals/" + p.getId())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/feature-proposals/" + p.getId())).andExpect(status().isNotFound());
    }

    @Test
    void deleteProposal_notInBacklog_returns409() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/delete-conflict.git");
        FeatureProposal p = createProposal(repo, "In Progress");
        p.setStatus(FeatureProposalStatus.in_progress);
        proposalRepo.save(p);

        mockMvc.perform(delete("/api/v1/feature-proposals/" + p.getId())).andExpect(status().isConflict());
    }

    @Test
    void rollOut_withTerminalRun_returns200() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/rollout-ok.git");
        GraphTemplate gt = createTemplate("Test");
        WorkflowRun run = createRun(gt, WorkflowRunStatus.completed);
        FeatureProposal p = createProposal(repo, "Done Feature");
        p.setStatus(FeatureProposalStatus.in_progress);
        p.setWorkflowRunId(run.getId());
        proposalRepo.save(p);

        mockMvc.perform(patch("/api/v1/feature-proposals/" + p.getId() + "/roll-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rolled_out"));
    }

    @Test
    void rollOut_withActiveRun_returns409() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/rollout-active.git");
        GraphTemplate gt = createTemplate("Test");
        WorkflowRun run = createRun(gt, WorkflowRunStatus.running);
        FeatureProposal p = createProposal(repo, "Still Running");
        p.setStatus(FeatureProposalStatus.in_progress);
        p.setWorkflowRunId(run.getId());
        proposalRepo.save(p);

        mockMvc.perform(patch("/api/v1/feature-proposals/" + p.getId() + "/roll-out"))
                .andExpect(status().isConflict());
    }

    @Test
    void rollOut_fromBacklog_returns409() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/rollout-backlog.git");
        FeatureProposal p = createProposal(repo, "Not Started");

        mockMvc.perform(patch("/api/v1/feature-proposals/" + p.getId() + "/roll-out"))
                .andExpect(status().isConflict());
    }

    @Test
    void createProposal_publishesEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-create.git");

        var body = Map.of(
                "title", "Add login page",
                "description", "Build a login page with OAuth",
                "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        verify(runEventPublisher).publishFeatureProposalChanged(any(UUID.class), eq("backlog"));
    }

    @Test
    void updateProposal_publishesEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-update.git");
        FeatureProposal p = createProposal(repo, "Old Title");

        var body = Map.of(
                "title", "New Title",
                "description", "Updated description",
                "softwareProjectId", repo.getId());

        mockMvc.perform(put("/api/v1/feature-proposals/" + p.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());

        verify(runEventPublisher).publishFeatureProposalChanged(eq(p.getId()), eq("backlog"));
    }

    @Test
    void deleteProposal_publishesEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-delete.git");
        FeatureProposal p = createProposal(repo, "To Delete");

        mockMvc.perform(delete("/api/v1/feature-proposals/" + p.getId())).andExpect(status().isNoContent());

        verify(runEventPublisher).publishFeatureProposalChanged(eq(p.getId()), eq("deleted"));
    }

    @Test
    void rollOut_publishesEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-rollout.git");
        GraphTemplate gt = createTemplate("Test");
        WorkflowRun run = createRun(gt, WorkflowRunStatus.completed);
        FeatureProposal p = createProposal(repo, "Done Feature");
        p.setStatus(FeatureProposalStatus.in_progress);
        p.setWorkflowRunId(run.getId());
        proposalRepo.save(p);

        mockMvc.perform(patch("/api/v1/feature-proposals/" + p.getId() + "/roll-out"))
                .andExpect(status().isOk());

        verify(runEventPublisher).publishFeatureProposalChanged(eq(p.getId()), eq("rolled_out"));
    }

    // --- Test helpers ---

    private GitRepo createGitRepo(String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(repo);
    }

    private RepoGroup createRepoGroup(String name, GitRepo... members) {
        List<UUID> ids = java.util.Arrays.stream(members).map(GitRepo::getId).toList();
        return repoGroupService.create(name, null, null, ids);
    }

    private GraphTemplate createTemplate(String name) {
        GraphTemplate gt = new GraphTemplate();
        gt.setName(name);
        gt.setGraphId(name.toLowerCase().replace(" ", "-"));
        gt.setVersion(1);
        return graphTemplateRepo.save(gt);
    }

    /**
     * Creates a backlog proposal targeting {@code repo}'s software_project id (which equals
     * the GitRepo's id post-V45).
     */
    private FeatureProposal createProposal(GitRepo repo, String title) {
        FeatureProposal p = new FeatureProposal();
        p.setTitle(title);
        p.setDescription("Description for " + title);
        p.setSoftwareProjectId(repo.getId());
        return proposalRepo.save(p);
    }

    private WorkflowRun createRun(GraphTemplate gt, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(gt.getId());
        run.setStatus(status);
        return runRepo.save(run);
    }
}
