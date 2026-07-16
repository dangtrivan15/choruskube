package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.EpicService;
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
public class EpicControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private EpicService epicService;

    @Autowired
    private RepoGroupService repoGroupService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Test
    void createEpic_returns201_withSoftwareProjectIdShape() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/repo.git");

        var body = Map.of(
                "title", "Add login page",
                "description", "Build a login page with OAuth",
                "motivation", "Users need to authenticate",
                "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Add login page"))
                .andExpect(jsonPath("$.status").value("backlog"))
                .andExpect(jsonPath("$.progress.totalTasks").value(0))
                .andExpect(jsonPath("$.softwareProject.id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andExpect(jsonPath("$.repos.length()").value(1));
    }

    @Test
    void createEpic_withRepoGroupTarget_returnsRepoGroupTypeAndResolvedRepos() throws Exception {
        GitRepo r1 = createGitRepo("https://github.com/test/group-r1.git");
        GitRepo r2 = createGitRepo("https://github.com/test/group-r2.git");
        RepoGroup group = createRepoGroup("grp-" + UUID.randomUUID().toString().substring(0, 8), r1, r2);

        var body =
                Map.of("title", "Two-repo feature", "description", "Backend + UI", "softwareProjectId", group.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softwareProject.type").value("repo_group"))
                .andExpect(jsonPath("$.repos.length()").value(2));
    }

    @Test
    void createEpic_withMissingSoftwareProjectId_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "No project");
        body.put("description", "softwareProjectId required");

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createEpic_withInvalidSoftwareProjectId_returns404() throws Exception {
        var body = Map.of(
                "title", "Add login page", "description", "Build a login page", "softwareProjectId", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listEpics_returnsAll() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/list-all.git");
        createEpic(repo, "Epic A");
        createEpic(repo, "Epic B");

        mockMvc.perform(get("/api/v1/epics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getEpic_returnsEpic() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/get.git");
        Epic e = createEpic(repo, "My Feature");

        mockMvc.perform(get("/api/v1/epics/" + e.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My Feature"))
                .andExpect(jsonPath("$.status").value("backlog"));
    }

    @Test
    void getEpic_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/epics/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateEpic_returns200() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/update.git");
        Epic e = createEpic(repo, "Old Title");

        var body =
                Map.of("title", "New Title", "description", "Updated description", "softwareProjectId", repo.getId());

        mockMvc.perform(put("/api/v1/epics/" + e.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"));
    }

    @Test
    void deleteEpic_returns204() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/delete.git");
        Epic e = createEpic(repo, "To Delete");

        mockMvc.perform(delete("/api/v1/epics/" + e.getId())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/epics/" + e.getId())).andExpect(status().isNotFound());
    }

    @Test
    void createEpic_publishesRoadmapItemChangedEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-create.git");

        var body = Map.of(
                "title",
                "Add login page",
                "description",
                "Build a login page with OAuth",
                "softwareProjectId",
                repo.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        verify(runEventPublisher).publishRoadmapItemChanged(eq("epic"), any(UUID.class), eq("backlog"));
    }

    @Test
    void deleteEpic_publishesRoadmapItemChangedEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-delete.git");
        Epic e = createEpic(repo, "To Delete");

        mockMvc.perform(delete("/api/v1/epics/" + e.getId())).andExpect(status().isNoContent());

        verify(runEventPublisher).publishRoadmapItemChanged(eq("epic"), eq(e.getId()), eq("deleted"));
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

    /** Creates a backlog Epic targeting {@code repo}'s software_project id. */
    private Epic createEpic(GitRepo repo, String title) {
        var response = epicService.create(
                new com.choruskube.core.dto.EpicRequest(title, "Description for " + title, null, repo.getId()), null);
        return epicRepo.findById(response.id()).orElseThrow();
    }
}
