package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
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

/**
 * Regression test for {@code DefaultEpicService#toResponses} batch-populating {@code milestone} —
 * not just the single-Epic {@code toResponse} path — and for the {@code milestoneId} list filter
 * added to {@code GET /api/v1/epics} (§3.3 of the Milestone spec).
 */
@AutoConfigureMockMvc
@Transactional
public class EpicMilestoneFilterTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

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
    void listEpics_filteredByMilestoneId_returnsOnlyTaggedEpics() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-filter.git");
        UUID milestoneId = createMilestone(repo.getId(), "Q3 Launch");
        UUID taggedEpicId = createEpic(repo.getId(), "Tagged Epic");
        UUID untaggedEpicId = createEpic(repo.getId(), "Untagged Epic");
        assignMilestone(taggedEpicId, milestoneId);

        mockMvc.perform(get("/api/v1/epics").param("milestoneId", milestoneId.toString()))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[?(@.id == '" + taggedEpicId + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + untaggedEpicId + "')]")
                        .doesNotExist());
    }

    @Test
    void listEpics_unfiltered_everyEpicCarriesPopulatedMilestoneReference() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-list-populate.git");
        UUID milestoneId = createMilestone(repo.getId(), "Q4 Launch");
        UUID taggedEpicId = createEpic(repo.getId(), "Tagged For List");
        assignMilestone(taggedEpicId, milestoneId);

        mockMvc.perform(get("/api/v1/epics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + taggedEpicId + "')].milestone.id")
                        .value(milestoneId.toString()))
                .andExpect(jsonPath("$.content[?(@.id == '" + taggedEpicId + "')].milestone.name")
                        .value("Q4 Launch"));
    }

    @Test
    void getEpic_singleRead_carriesPopulatedMilestoneReference() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/milestone-get-populate.git");
        UUID milestoneId = createMilestone(repo.getId(), "Q1 Launch");
        UUID epicId = createEpic(repo.getId(), "Single Read Epic");
        assignMilestone(epicId, milestoneId);

        mockMvc.perform(get("/api/v1/epics/" + epicId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.milestone.id").value(milestoneId.toString()));
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

    private UUID createEpic(UUID softwareProjectId, String title) throws Exception {
        var body = Map.of("title", title, "description", "D", "softwareProjectId", softwareProjectId);
        String response = mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).get("id").asText());
    }

    private void assignMilestone(UUID epicId, UUID milestoneId) throws Exception {
        mockMvc.perform(patch("/api/v1/epics/" + epicId + "/milestone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("milestoneId", milestoneId))))
                .andExpect(status().isOk());
    }
}
