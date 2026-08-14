package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class MilestoneControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicService epicService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void allowAllByDefault() {
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
        when(orgSecurity.isPlatformAdmin()).thenReturn(true);
    }

    @Test
    void createMilestone_returns201_withShape() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-create.git");

        var body = Map.of(
                "name", "Q3 Launch",
                "description", "Third-quarter release",
                "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Q3 Launch"))
                .andExpect(jsonPath("$.description").value("Third-quarter release"))
                .andExpect(jsonPath("$.softwareProjectId").value(repo.getId().toString()))
                .andExpect(jsonPath("$.epicCount").value(0));
    }

    @Test
    void createMilestone_withMissingSoftwareProjectId_returns400() throws Exception {
        var body = Map.of("name", "No project");

        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createMilestone_withUnknownSoftwareProjectId_returns404() throws Exception {
        var body = Map.of("name", "Ghost project", "softwareProjectId", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void createMilestone_duplicateNameInSameProject_returns409() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-dup.git");
        var body = Map.of("name", "Q3 Launch", "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        var duplicateBody = Map.of("name", "q3 launch", "softwareProjectId", repo.getId());
        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicateBody)))
                .andExpect(status().isConflict());
    }

    @Test
    void createMilestone_sameNameDifferentProject_succeeds() throws Exception {
        GitRepo repoA = createGitRepo("https://github.com/test/milestone-proj-a.git");
        GitRepo repoB = createGitRepo("https://github.com/test/milestone-proj-b.git");
        var bodyA = Map.of("name", "Q3 Launch", "softwareProjectId", repoA.getId());
        var bodyB = Map.of("name", "Q3 Launch", "softwareProjectId", repoB.getId());

        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bodyA)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bodyB)))
                .andExpect(status().isCreated());
    }

    @Test
    void listMilestones_filteredBySoftwareProjectId_returnsOnlyMatching() throws Exception {
        GitRepo repoA = createGitRepo("https://github.com/test/milestone-list-a.git");
        GitRepo repoB = createGitRepo("https://github.com/test/milestone-list-b.git");
        UUID milestoneA = createMilestone(repoA.getId(), "A Milestone");
        UUID milestoneB = createMilestone(repoB.getId(), "B Milestone");

        mockMvc.perform(get("/api/v1/milestones")
                        .param("softwareProjectId", repoA.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[?(@.id == '" + milestoneA + "')]").exists())
                .andExpect(
                        jsonPath("$.content[?(@.id == '" + milestoneB + "')]").doesNotExist());
    }

    @Test
    void getMilestone_returnsMilestone() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-get.git");
        UUID id = createMilestone(repo.getId(), "Gettable Milestone");

        mockMvc.perform(get("/api/v1/milestones/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gettable Milestone"));
    }

    @Test
    void getMilestone_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/milestones/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateMilestone_renames_returns200() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-rename.git");
        UUID id = createMilestone(repo.getId(), "Old Name");

        var body = Map.of("name", "New Name", "description", "Updated");
        mockMvc.perform(put("/api/v1/milestones/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));
    }

    @Test
    void updateMilestone_notFound_returns404() throws Exception {
        var body = Map.of("name", "New Name");
        mockMvc.perform(put("/api/v1/milestones/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteMilestone_returns204_andUntagsEpics() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-delete.git");
        UUID milestoneId = createMilestone(repo.getId(), "Deletable Milestone");
        var epic = epicService.create(new EpicRequest("Tagged Epic", "D", null, repo.getId()), null);
        epicService.assignMilestone(epic.id(), milestoneId);

        mockMvc.perform(delete("/api/v1/milestones/" + milestoneId)).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/milestones/" + milestoneId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/epics/" + epic.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.milestone").doesNotExist());
    }

    // --- Authorization ladder ---

    @Test
    void createMilestone_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);
        GitRepo repo = createGitRepo("https://github.com/test/milestone-forbidden-create.git");
        var body = Map.of("name", "Forbidden", "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listMilestones_belowCanReadPermission_returns403() throws Exception {
        when(orgSecurity.canRead()).thenReturn(false);

        mockMvc.perform(get("/api/v1/milestones")).andExpect(status().isForbidden());
    }

    @Test
    void updateMilestone_belowCanAdminPermission_returns403() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-forbidden-update.git");
        UUID id = createMilestone(repo.getId(), "Admin Gated");
        when(orgSecurity.canAdmin()).thenReturn(false);

        var body = Map.of("name", "New Name");
        mockMvc.perform(put("/api/v1/milestones/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteMilestone_belowCanAdminPermission_returns403() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-forbidden-delete.git");
        UUID id = createMilestone(repo.getId(), "Admin Gated Delete");
        when(orgSecurity.canAdmin()).thenReturn(false);

        mockMvc.perform(delete("/api/v1/milestones/" + id)).andExpect(status().isForbidden());
    }

    // --- Test helpers ---

    private GitRepo createGitRepo(String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(repo);
    }

    private UUID createMilestone(UUID softwareProjectId, String name) throws Exception {
        var body = Map.of("name", name, "softwareProjectId", softwareProjectId);
        String response = mockMvc.perform(post("/api/v1/milestones")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }
}
