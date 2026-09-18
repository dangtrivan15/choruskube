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
                groupName, "registry/agent:v1", "two-repo project", List.of(r1.getId(), r2.getId()), null, null);

        mockMvc.perform(post("/api/v1/repo-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(groupName))
                .andExpect(jsonPath("$.members.length()").value(2));
    }

    @Test
    void create_persists_dind_image_and_returns_it_on_get() throws Exception {
        GitRepo r1 = saveRepo("dind-r1");
        String groupName = "g-dind-" + UUID.randomUUID().toString().substring(0, 8);
        RepoGroupRequest body = new RepoGroupRequest(
                groupName,
                "registry/agent:v1",
                "with dind",
                List.of(r1.getId()),
                null,
                "registry.example/grp-dind:latest");

        String created = mockMvc.perform(post("/api/v1/repo-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dindImage").value("registry.example/grp-dind:latest"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Re-read through GET so the assertion exercises the persisted row, not just the create response.
        String id = objectMapper.readTree(created).get("id").asText();
        mockMvc.perform(get("/api/v1/repo-groups/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dindImage").value("registry.example/grp-dind:latest"));
    }

    @Test
    void create_persists_enable_docker_and_returns_it_on_get() throws Exception {
        // The member has Docker off; only the group's own enable_docker=true is sent. A round-trip that
        // returns true proves the flag is self-composed and stored, not inferred from members.
        GitRepo r1 = saveRepo("docker-r1");
        String groupName = "g-docker-" + UUID.randomUUID().toString().substring(0, 8);
        RepoGroupRequest body =
                new RepoGroupRequest(groupName, "registry/agent:v1", "with docker", List.of(r1.getId()), true, null);

        String created = mockMvc.perform(post("/api/v1/repo-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.enableDocker").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Re-read through GET so the assertion exercises the persisted row, not just the create response.
        String id = objectMapper.readTree(created).get("id").asText();
        mockMvc.perform(get("/api/v1/repo-groups/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enableDocker").value(true));
    }

    @Test
    void delete_repo_that_is_a_group_member_is_409() throws Exception {
        GitRepo r1 = saveRepo("r1");
        String groupName = "g-" + UUID.randomUUID().toString().substring(0, 8);
        repoGroupService.create(new RepoGroupRequest(groupName, null, null, List.of(r1.getId()), null, null));
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

        RepoGroup foreignGroup = repoGroupService.create(new RepoGroupRequest(
                "foreign-grp-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(foreignRepo.getId()),
                null,
                null));
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
    void delete_repo_group_with_epic_and_tasks_soft_deletes() throws Exception {
        GitRepo r1 = saveRepo("archive-r1");
        GitRepo r2 = saveRepo("archive-r2");
        RepoGroup group = repoGroupService.create(new RepoGroupRequest(
                "g-archive-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId(), r2.getId()),
                null,
                null));
        entityManager.flush();

        // An Epic -> Story -> Task chain targeting this group. Roadmap work no longer blocks the
        // delete — it is archived alongside the group instead of returning 409.
        var epic = epicService.create(
                new EpicRequest("Shipped epic", "Completed work under this group", null, group.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("Story", "Done"));
        taskService.create(story.id(), new TaskRequest("Done task", "Completed"));
        entityManager.flush();

        mockMvc.perform(delete("/api/v1/repo-groups/{id}", group.getId())).andExpect(status().isNoContent());

        // Archive, not purge: the row survives with deleted_at set (so the Epic/Task FKs stay valid),
        // and @SQLRestriction hides it from every read path.
        entityManager.flush();
        entityManager.clear();
        List<?> rows = entityManager
                .createNativeQuery("SELECT deleted_at FROM software_project WHERE id = :id")
                .setParameter("id", group.getId())
                .getResultList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).isNotNull();

        mockMvc.perform(get("/api/v1/repo-groups/{id}", group.getId())).andExpect(status().isNotFound());
    }

    @Test
    void member_git_repo_can_be_deleted_after_its_group_is_archived() throws Exception {
        GitRepo r1 = saveRepo("freed-r1");
        RepoGroup group = repoGroupService.create(new RepoGroupRequest(
                "g-freed-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId()),
                null,
                null));
        entityManager.flush();

        // Archiving the group clears its membership rows, so the member repo is no longer held by an
        // invisible group and its own (soft) delete succeeds instead of 409ing.
        mockMvc.perform(delete("/api/v1/repo-groups/{id}", group.getId())).andExpect(status().isNoContent());
        entityManager.flush();

        mockMvc.perform(delete("/api/v1/git-repos/{id}", r1.getId())).andExpect(status().isNoContent());
    }

    @Test
    void delete_repo_group_with_lonely_epic_soft_deletes() throws Exception {
        // An Epic with no Story/Task under it used to block the hard delete — its
        // software_project_id FK has no ON DELETE clause. Archiving keeps the row, so the FK stays
        // valid and the delete succeeds instead of 409ing.
        GitRepo r1 = saveRepo("epic-only-r1");
        RepoGroup group = repoGroupService.create(new RepoGroupRequest(
                "g-epic-only-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId()),
                null,
                null));
        entityManager.flush();

        epicService.create(new EpicRequest("Lonely epic", "No Story/Task yet", null, group.getId()), null);
        entityManager.flush();

        mockMvc.perform(delete("/api/v1/repo-groups/{id}", group.getId())).andExpect(status().isNoContent());

        // Clear the session so the GET re-reads through @SQLRestriction (a cache hit by id would
        // ignore it); in production the GET is a separate request, so this only bridges the shared
        // test transaction.
        entityManager.flush();
        entityManager.clear();
        mockMvc.perform(get("/api/v1/repo-groups/{id}", group.getId())).andExpect(status().isNotFound());
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
        RepoGroup group = repoGroupService.create(new RepoGroupRequest(
                "g-upd-" + UUID.randomUUID().toString().substring(0, 8),
                "img:1",
                "old desc",
                List.of(r1.getId(), r2.getId()),
                null,
                null));
        entityManager.flush();

        String newName = "g-upd-renamed-" + UUID.randomUUID().toString().substring(0, 8);
        RepoGroupRequest body =
                new RepoGroupRequest(newName, "img:2", "new desc", List.of(r2.getId(), r3.getId()), null, null);

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
        repoGroupService.create(new RepoGroupRequest(takenName, null, null, List.of(r1.getId()), null, null));
        RepoGroup group = repoGroupService.create(new RepoGroupRequest(
                "g-other-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId()),
                null,
                null));
        entityManager.flush();

        RepoGroupRequest body = new RepoGroupRequest(takenName, null, null, List.of(r1.getId()), null, null);
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

        RepoGroup foreignGroup = repoGroupService.create(new RepoGroupRequest(
                "foreign-upd-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(foreignRepo.getId()),
                null,
                null));
        entityManager.flush();
        UUID foreignGroupId = foreignGroup.getId();

        doThrow(new ForbiddenException("Access denied: repo-group " + foreignGroupId))
                .when(authService)
                .checkOrgAccess(eq("repo_group"), eq(foreignGroupId));

        RepoGroupRequest body = new RepoGroupRequest("anything", null, null, List.of(foreignRepo.getId()), null, null);
        mockMvc.perform(put("/api/v1/repo-groups/{id}", foreignGroupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_with_empty_members_is_400() throws Exception {
        GitRepo r1 = saveRepo("empty-r1");
        RepoGroup group = repoGroupService.create(new RepoGroupRequest(
                "g-empty-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId()),
                null,
                null));
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
