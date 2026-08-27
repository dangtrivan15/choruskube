package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.AuthorizationService;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.OrgIdentitySync;
import com.choruskube.core.service.RepoGroupService;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
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
class RepoGroupControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private OrgIdentitySync orgIdentity;

    @MockitoBean
    private AuthorizationService authService;

    @BeforeEach
    void setUp() {
        // Default: same-org access is permitted; tests that need to simulate cross-org rejection
        // override with a doThrow stub. checkOrgAccess returns void, so no Mockito.when() form.
        doNothing().when(authService).checkOrgAccess(any(String.class), any(UUID.class));
    }

    @Test
    void create_201_with_members() throws Exception {
        GitRepo r1 = saveRepo("r1");
        GitRepo r2 = saveRepo("r2");

        String groupName = "my-group-" + UUID.randomUUID().toString().substring(0, 8);
        RepoGroupRequest body = new RepoGroupRequest(
                groupName, "registry/agent:v1", "two-repo project", List.of(r1.getId(), r2.getId()));

        mockMvc.perform(post("/api/v1/repo-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(groupName))
                .andExpect(jsonPath("$.members.length()").value(2));
    }

    @Test
    void delete_repo_that_is_a_group_member_is_409() throws Exception {
        GitRepo r1 = saveRepo("r1");
        String groupName = "g-" + UUID.randomUUID().toString().substring(0, 8);
        repoGroupService.create(groupName, null, null, List.of(r1.getId()));
        // Force the membership row to be flushed before MockMvc opens its own transaction.
        entityManager.flush();

        String body = mockMvc.perform(delete("/api/v1/git-repos/{id}", r1.getId()))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("member of RepoGroup");
    }

    @Test
    void get_other_orgs_group_is_403() throws Exception {
        GitRepo foreignRepo = new GitRepo();
        foreignRepo.setName("foreign-" + UUID.randomUUID().toString().substring(0, 8));
        foreignRepo.setUrl("https://github.com/other-org/" + foreignRepo.getName() + ".git");
        foreignRepo.setDefaultBranch("main");
        foreignRepo = gitRepoRepo.saveAndFlush(foreignRepo);

        RepoGroup foreignGroup = repoGroupService.create(
                "foreign-grp-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(foreignRepo.getId()));
        entityManager.flush();
        UUID foreignGroupId = foreignGroup.getId();

        // The controller must reject a lookup of a group the caller's org does not own; the
        // mocked AuthorizationService throws ForbiddenException for this group id.
        doThrow(new ForbiddenException(
                        "Access denied: repo-group " + foreignGroupId + " belongs to another organization"))
                .when(authService)
                .checkOrgAccess(eq("repo_group"), eq(foreignGroupId));

        mockMvc.perform(get("/api/v1/repo-groups/{id}", foreignGroupId)).andExpect(status().isForbidden());
    }

    @Test
    void delete_repo_group_with_active_task_is_409() throws Exception {
        GitRepo r1 = saveRepo("active-task-r1");
        GitRepo r2 = saveRepo("active-task-r2");
        RepoGroup group = repoGroupService.create(
                "g-active-task-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId(), r2.getId()));
        entityManager.flush();

        // Seed an Epic -> Story -> Task chain targeting this group, with the Task left non-done
        // (Task carries software_project_id directly).
        var epic = epicService.create(
                new EpicRequest("Active epic", "Holds the group from delete", null, group.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("Story", "Desc"));
        taskService.create(story.id(), new TaskRequest("Active task", "Holds the group from delete"));
        entityManager.flush();

        String body = mockMvc.perform(delete("/api/v1/repo-groups/{id}", group.getId()))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("active task");
    }

    @Test
    void delete_repo_group_with_epic_and_no_story_or_task_is_409() throws Exception {
        // Epic.software_project_id carries a FK to software_project (like Task's) with no ON
        // DELETE clause, so an Epic that hasn't grown a Story/Task yet must still block the hard
        // delete below the controller. Regression test for the gap where the delete guard only
        // looked at TaskRepository#countNonDoneBySoftwareProjectId and missed this case entirely,
        // turning it into an unhandled 500 instead of this 409.
        GitRepo r1 = saveRepo("epic-only-r1");
        RepoGroup group = repoGroupService.create(
                "g-epic-only-" + UUID.randomUUID().toString().substring(0, 8), null, null, List.of(r1.getId()));
        entityManager.flush();

        epicService.create(new EpicRequest("Lonely epic", "No Story/Task yet", null, group.getId()), null);
        entityManager.flush();

        String body = mockMvc.perform(delete("/api/v1/repo-groups/{id}", group.getId()))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("epic");
    }

    @Test
    void delete_repo_group_with_story_and_no_task_is_409() throws Exception {
        // Same gap as above, one level deeper: a Story with no Task yet is only reachable through
        // its Epic (epic_id is NOT NULL), so the Epic count above is what catches this case too.
        GitRepo r1 = saveRepo("story-only-r1");
        RepoGroup group = repoGroupService.create(
                "g-story-only-" + UUID.randomUUID().toString().substring(0, 8), null, null, List.of(r1.getId()));
        entityManager.flush();

        var epic = epicService.create(new EpicRequest("Epic with story", "No Task yet", null, group.getId()), null);
        storyService.create(epic.id(), new StoryRequest("Story", "No Task yet"));
        entityManager.flush();

        mockMvc.perform(delete("/api/v1/repo-groups/{id}", group.getId())).andExpect(status().isConflict());
    }

    @Test
    void delete_unknown_repo_group_is_404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(delete("/api/v1/repo-groups/{id}", unknown)).andExpect(status().isNotFound());
    }

    @Test
    void update_200_changes_name_image_description_and_members() throws Exception {
        GitRepo r1 = saveRepo("upd-r1");
        GitRepo r2 = saveRepo("upd-r2");
        GitRepo r3 = saveRepo("upd-r3");
        RepoGroup group = repoGroupService.create(
                "g-upd-" + UUID.randomUUID().toString().substring(0, 8),
                "img:1",
                "old desc",
                List.of(r1.getId(), r2.getId()));
        entityManager.flush();

        String newName = "g-upd-renamed-" + UUID.randomUUID().toString().substring(0, 8);
        RepoGroupRequest body = new RepoGroupRequest(newName, "img:2", "new desc", List.of(r2.getId(), r3.getId()));

        mockMvc.perform(put("/api/v1/repo-groups/{id}", group.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.agentImage").value("img:2"))
                .andExpect(jsonPath("$.description").value("new desc"))
                .andExpect(jsonPath("$.members.length()").value(2));
    }

    @Test
    void update_with_duplicate_name_is_400() throws Exception {
        GitRepo r1 = saveRepo("dup-r1");
        String takenName = "g-taken-" + UUID.randomUUID().toString().substring(0, 8);
        repoGroupService.create(takenName, null, null, List.of(r1.getId()));
        RepoGroup group = repoGroupService.create(
                "g-other-" + UUID.randomUUID().toString().substring(0, 8), null, null, List.of(r1.getId()));
        entityManager.flush();

        RepoGroupRequest body = new RepoGroupRequest(takenName, null, null, List.of(r1.getId()));
        mockMvc.perform(put("/api/v1/repo-groups/{id}", group.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_other_orgs_group_is_403() throws Exception {
        GitRepo foreignRepo = new GitRepo();
        foreignRepo.setName("foreign-upd-" + UUID.randomUUID().toString().substring(0, 8));
        foreignRepo.setUrl("https://github.com/other-org/" + foreignRepo.getName() + ".git");
        foreignRepo.setDefaultBranch("main");
        foreignRepo = gitRepoRepo.saveAndFlush(foreignRepo);

        RepoGroup foreignGroup = repoGroupService.create(
                "foreign-upd-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(foreignRepo.getId()));
        entityManager.flush();
        UUID foreignGroupId = foreignGroup.getId();

        doThrow(new ForbiddenException("Access denied: repo-group " + foreignGroupId))
                .when(authService)
                .checkOrgAccess(eq("repo_group"), eq(foreignGroupId));

        RepoGroupRequest body = new RepoGroupRequest("anything", null, null, List.of(foreignRepo.getId()));
        mockMvc.perform(put("/api/v1/repo-groups/{id}", foreignGroupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_with_empty_members_is_400() throws Exception {
        GitRepo r1 = saveRepo("empty-r1");
        RepoGroup group = repoGroupService.create(
                "g-empty-" + UUID.randomUUID().toString().substring(0, 8), null, null, List.of(r1.getId()));
        entityManager.flush();

        String body = "{\"name\":\"" + group.getName() + "\",\"memberRepoIds\":[]}";
        mockMvc.perform(put("/api/v1/repo-groups/{id}", group.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    private GitRepo saveRepo(String shortName) {
        String unique = shortName + "-" + UUID.randomUUID().toString().substring(0, 8);
        GitRepo r = new GitRepo();
        r.setName(unique);
        r.setUrl("https://github.com/owner/" + unique + ".git");
        r.setDefaultBranch("main");
        return gitRepoRepo.saveAndFlush(r);
    }
}
