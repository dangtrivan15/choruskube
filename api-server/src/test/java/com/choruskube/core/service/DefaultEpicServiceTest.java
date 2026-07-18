package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalUpdateEpicRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.RepoGroupMember;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DefaultEpicServiceTest extends BaseTest {

    @Autowired
    private EpicService service;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RepoGroupRepository repoGroupRepo;

    @Autowired
    private SoftwareProjectRepository softwareProjectRepo;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private StoryRepository storyRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private AuditSink auditSink;

    @Test
    void create_withGitRepoTarget_returnsSoftwareProjectRef_withType_git_repo() {
        GitRepo r = makeRepo("https://github.com/test/one.git");

        EpicResponse created = service.create(new EpicRequest("Title", "Desc", null, r.getId()), null);

        assertThat(created.softwareProject()).isNotNull();
        assertThat(created.softwareProject().id()).isEqualTo(r.getId());
        assertThat(created.softwareProject().type()).isEqualTo("git_repo");
        assertThat(created.repos()).hasSize(1);
        assertThat(created.repos().get(0).id()).isEqualTo(r.getId());
        assertThat(created.status()).isEqualTo("backlog");
        assertThat(created.progress().totalTasks()).isZero();
        assertThat(created.progress().doneTasks()).isZero();
    }

    @Test
    void create_withRepoGroupTarget_returnsSoftwareProjectRef_withType_repo_group_andResolvedRepos() {
        GitRepo r1 = makeRepo("https://github.com/test/group-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/group-b.git");
        RepoGroup group = makeGroup("group-1", List.of(r1, r2));

        EpicResponse created = service.create(new EpicRequest("Title", "Desc", null, group.getId()), null);

        assertThat(created.softwareProject().id()).isEqualTo(group.getId());
        assertThat(created.softwareProject().type()).isEqualTo("repo_group");
        assertThat(created.repos()).extracting(rr -> rr.id()).containsExactly(r1.getId(), r2.getId());
    }

    @Test
    void create_withNullSoftwareProjectId_throwsBadRequest() {
        assertThatThrownBy(() -> service.create(new EpicRequest("Title", "Desc", null, null), null))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void create_withUnknownSoftwareProjectId_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(new EpicRequest("Title", "Desc", null, unknown), null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void toResponse_repos_nameDerivedFromUrl() {
        GitRepo r = makeRepo("https://github.com/acme/derived-name.git");

        EpicResponse created = service.create(new EpicRequest("Title", "Desc", null, r.getId()), null);

        assertThat(created.repos()).hasSize(1);
        assertThat(created.repos().get(0).name()).isEqualTo("derived-name");
        assertThat(created.repos().get(0).url()).isEqualTo("https://github.com/acme/derived-name.git");
    }

    @Test
    void listBySoftwareProjectId_returnsEpicsForGivenProject() {
        GitRepo r = makeRepo("https://github.com/test/list-by-id.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        List<EpicResponse> result = service.listBySoftwareProjectId(r.getId());
        assertThat(result).extracting(EpicResponse::id).contains(created.id());
    }

    @Test
    void update_replacesSoftwareProjectId() {
        GitRepo r1 = makeRepo("https://github.com/test/upd-one.git");
        GitRepo r2 = makeRepo("https://github.com/test/upd-two.git");

        EpicResponse created = service.create(new EpicRequest("Orig", "Desc", null, r1.getId()), null);

        EpicResponse updated = service.update(created.id(), new EpicRequest("Orig", "Desc", null, r2.getId()));

        assertThat(updated.softwareProject().id()).isEqualTo(r2.getId());
        assertThat(updated.repos()).hasSize(1);
        assertThat(updated.repos().get(0).id()).isEqualTo(r2.getId());
    }

    @Test
    void update_withStartedDescendantTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/upd-blocked.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        assertThatThrownBy(() -> service.update(epic.id(), new EpicRequest("New", "D", null, r.getId())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_withStartedDescendantTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/del-blocked.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        assertThatThrownBy(() -> service.delete(epic.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_withNoDescendantTasksOrAllBacklog_succeeds() {
        GitRepo r = makeRepo("https://github.com/test/del-ok.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        service.delete(epic.id());

        assertThatThrownBy(() -> service.get(epic.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_withBacklogDescendants_cascadesToStoryAndTask() {
        // Epic/Story/Task have plain UUID FK columns, not JPA @OneToMany associations, so
        // service.delete() issues a single-table SQL DELETE on the epic row and relies entirely on
        // the DB-level `ON DELETE CASCADE` declared in V2__work_hierarchy.sql (story.epic_id,
        // task.story_id) to remove descendants. Assert that cascade actually happens end to end
        // against the real database, rather than trusting the migration SQL by inspection alone.
        GitRepo r = makeRepo("https://github.com/test/del-cascade.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));

        service.delete(epic.id());
        // repo.delete(epic) only removes the epic row from the JPA persistence context; Epic/Story
        // have no mapped association, so Hibernate's auto-flush heuristics don't know a Task/Story
        // query depends on the pending Epic delete. Flush explicitly so the DB-level ON DELETE
        // CASCADE has actually fired, then clear the persistence context so the findById checks
        // below hit the database instead of returning the still-managed, pre-cascade instances from
        // the first-level cache.
        entityManager.flush();
        entityManager.clear();

        assertThat(storyRepo.findById(story.id())).isEmpty();
        assertThat(taskRepo.findById(task.id())).isEmpty();
    }

    @Test
    void rollup_allDescendantTasksDone_statusIsDone() {
        GitRepo r = makeRepo("https://github.com/test/rollup-done.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskDone(task.id());

        EpicResponse fetched = service.get(epic.id());
        assertThat(fetched.status()).isEqualTo("done");
        assertThat(fetched.progress().totalTasks()).isEqualTo(1);
        assertThat(fetched.progress().doneTasks()).isEqualTo(1);
    }

    @Test
    void rollup_anyDescendantTaskStarted_statusIsInProgress() {
        GitRepo r = makeRepo("https://github.com/test/rollup-in-progress.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        EpicResponse fetched = service.get(epic.id());
        assertThat(fetched.status()).isEqualTo("in_progress");
    }

    @Test
    void rollup_emptyEpic_statusIsBacklog_neverDone() {
        GitRepo r = makeRepo("https://github.com/test/rollup-empty.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        EpicResponse fetched = service.get(epic.id());
        assertThat(fetched.status()).isEqualTo("backlog");
        assertThat(fetched.progress().totalTasks()).isZero();
    }

    // ── updateInternal: PATCH preserve semantics ──────────────────────────────────

    @Test
    void updateInternal_withNullTitle_preservesExistingTitle() {
        GitRepo r = makeRepo("https://github.com/test/upd-preserve-title.git");
        EpicResponse created = service.create(new EpicRequest("Original Title", "Desc", "Motivation", r.getId()), null);

        EpicResponse updated = service.updateInternal(
                created.id(), r.getId(), UUID.randomUUID(), new InternalUpdateEpicRequest(null, "New Desc", null));

        assertThat(updated.title()).isEqualTo("Original Title");
        assertThat(updated.description()).isEqualTo("New Desc");
        assertThat(updated.motivation()).isEqualTo("Motivation");
    }

    @Test
    void updateInternal_withEmptyStringMotivation_clearsMotivation() {
        GitRepo r = makeRepo("https://github.com/test/upd-clear-motivation.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", "Clear me", r.getId()), null);

        EpicResponse updated = service.updateInternal(
                created.id(), r.getId(), UUID.randomUUID(), new InternalUpdateEpicRequest(null, null, ""));

        assertThat(updated.motivation()).isNull();
    }

    @Test
    void updateInternal_withBlankTitle_throwsBadRequest() {
        GitRepo r = makeRepo("https://github.com/test/upd-blank-title.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        assertThatThrownBy(() -> service.updateInternal(
                        created.id(), r.getId(), UUID.randomUUID(), new InternalUpdateEpicRequest("   ", null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("title");
    }

    @Test
    void updateInternal_withProjectIdMismatch_throwsForbidden() {
        GitRepo r1 = makeRepo("https://github.com/test/upd-proj-mismatch-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/upd-proj-mismatch-b.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r1.getId()), null);

        assertThatThrownBy(() -> service.updateInternal(
                        created.id(),
                        r2.getId(), // wrong project
                        UUID.randomUUID(),
                        new InternalUpdateEpicRequest("New T", null, null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void updateInternal_withUnknownEpicId_throwsNotFound() {
        UUID unknownId = UUID.randomUUID();
        assertThatThrownBy(() -> service.updateInternal(
                        unknownId, UUID.randomUUID(), SingleTenant.ID, new InternalUpdateEpicRequest("T", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(unknownId.toString());
    }

    // ── updateStage: roadmap board stage moves ────────────────────────────────────

    @Test
    void create_defaultsStageToBacklog() {
        GitRepo r = makeRepo("https://github.com/test/stage-default.git");

        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        assertThat(created.stage()).isEqualTo("backlog");
    }

    @Test
    void updateStage_persistsNewStage_leavesStatusAndProgressUnchanged() {
        GitRepo r = makeRepo("https://github.com/test/stage-persist.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(created.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskDone(task.id());
        EpicResponse beforeStageMove = service.get(created.id());

        EpicResponse updated = service.updateStage(created.id(), WorkItemStatus.rolled_out);

        assertThat(updated.stage()).isEqualTo("rolled_out");
        // Decision: stage is fully decoupled from the read-time status rollup.
        assertThat(updated.status()).isEqualTo(beforeStageMove.status());
        assertThat(updated.progress().totalTasks())
                .isEqualTo(beforeStageMove.progress().totalTasks());
        assertThat(updated.progress().doneTasks())
                .isEqualTo(beforeStageMove.progress().doneTasks());

        EpicResponse refetched = service.get(created.id());
        assertThat(refetched.stage()).isEqualTo("rolled_out");
    }

    @Test
    void updateStage_publishesRoadmapItemChangedEvent() {
        GitRepo r = makeRepo("https://github.com/test/stage-event.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        service.updateStage(created.id(), WorkItemStatus.in_progress);

        verify(runEventPublisher).publishRoadmapItemChanged(eq("epic"), eq(created.id()), eq("in_progress"));
    }

    @Test
    void updateStage_writesAuditEntryWithBeforeAfterStage() {
        GitRepo r = makeRepo("https://github.com/test/stage-audit.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        service.updateStage(created.id(), WorkItemStatus.rolled_out);

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditSink)
                .record(eq(AuditSink.EPIC_STAGE_UPDATED), eq("epic"), eq(created.id()), detailCaptor.capture());
        // Parse and check the before/after fields structurally rather than just asserting both
        // raw strings appear somewhere in the payload — a `contains` check alone wouldn't catch a
        // bug that swapped before/after in detailJson(...).
        JsonNode detail = readTree(detailCaptor.getValue());
        assertThat(detail.path("before").path("stage").asText()).isEqualTo("backlog");
        assertThat(detail.path("after").path("stage").asText()).isEqualTo("rolled_out");
    }

    private static JsonNode readTree(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void updateStage_withDoneValue_throwsAndHasNoSideEffects() {
        GitRepo r = makeRepo("https://github.com/test/stage-done-rejected.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        assertThatThrownBy(() -> service.updateStage(created.id(), WorkItemStatus.done))
                .isInstanceOf(BadRequestException.class);

        EpicResponse after = service.get(created.id());
        assertThat(after.stage()).isEqualTo("backlog");
        verify(auditSink, never()).record(eq(AuditSink.EPIC_STAGE_UPDATED), any(), any(), any());
        verify(runEventPublisher, never()).publishRoadmapItemChanged(eq("epic"), eq(created.id()), eq("done"));
    }

    private void markTaskInProgress(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.in_progress);
        taskRepo.saveAndFlush(t);
    }

    private void markTaskDone(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(t);
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }

    private RepoGroup makeGroup(String name, List<GitRepo> repos) {
        RepoGroup group = new RepoGroup();
        // Prefix with random UUID to avoid (org, name) collisions across parallel tests.
        group.setName(name + "-" + UUID.randomUUID().toString().substring(0, 8));
        List<RepoGroupMember> members = new ArrayList<>();
        for (int i = 0; i < repos.size(); i++) {
            RepoGroupMember m = new RepoGroupMember();
            m.setRepoGroup(group);
            m.setGitRepo(repos.get(i));
            m.setPosition(i);
            members.add(m);
        }
        group.setMembers(members);
        return repoGroupRepo.save(group);
    }
}
