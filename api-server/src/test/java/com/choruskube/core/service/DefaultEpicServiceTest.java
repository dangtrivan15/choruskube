package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
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
import com.choruskube.core.repository.WorkItemDependencyRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    @Autowired
    private WorkItemDependencyService dependencyService;

    @Autowired
    private WorkItemDependencyRepository dependencyRepo;

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
    void delete_withDependencyEdgeOnDescendantStory_alsoRemovesDependency() {
        // Story/Task rows under this Epic are removed by the DB-level ON DELETE CASCADE (see
        // delete_withBacklogDescendants_cascadesToStoryAndTask above), which bypasses
        // DefaultStoryService#delete's/DefaultTaskService#delete's own work_item_dependency
        // cleanup entirely. Assert that Epic delete cleans up dependency edges referencing its
        // descendant Stories itself, otherwise the edge would dangle and break the Roadmap Graph
        // endpoint for whichever other, unrelated Epic is on the other end of it.
        GitRepo r = makeRepo("https://github.com/test/epic-delete-story-dep.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        GitRepo otherRepo = makeRepo("https://github.com/test/epic-delete-story-dep-other.git");
        EpicResponse otherEpic = service.create(new EpicRequest("Other", "D", null, otherRepo.getId()), null);
        var otherStory = storyService.create(otherEpic.id(), new StoryRequest("Other S", "D"));
        DependencyEdgeResponse edge =
                dependencyService.create(new CreateDependencyRequest("story", otherStory.id(), "story", story.id()));

        service.delete(epic.id());

        assertThat(dependencyRepo.findById(edge.id())).isEmpty();
    }

    @Test
    void delete_withDependencyEdgeOnDescendantTask_alsoRemovesDependency() {
        GitRepo r = makeRepo("https://github.com/test/epic-delete-task-dep.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        GitRepo otherRepo = makeRepo("https://github.com/test/epic-delete-task-dep-other.git");
        EpicResponse otherEpic = service.create(new EpicRequest("Other", "D", null, otherRepo.getId()), null);
        var otherStory = storyService.create(otherEpic.id(), new StoryRequest("Other S", "D"));
        DependencyEdgeResponse edge =
                dependencyService.create(new CreateDependencyRequest("story", otherStory.id(), "task", task.id()));

        service.delete(epic.id());

        assertThat(dependencyRepo.findById(edge.id())).isEmpty();
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

    // --- readyToStart rollup (Decision 2/3) ---

    @Test
    void readyToStart_epicWithNoStoriesOrTasks_isFalse() {
        GitRepo r = makeRepo("https://github.com/test/ready-empty.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);

        EpicResponse fetched = service.get(created.id());

        assertThat(fetched.readyToStart()).isFalse();
    }

    @Test
    void readyToStart_backlogTaskWithNoBlockers_isTrue() {
        GitRepo r = makeRepo("https://github.com/test/ready-simple.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(created.id(), new StoryRequest("S", "D"));
        taskService.create(story.id(), new TaskRequest("T", "D"));

        EpicResponse fetched = service.get(created.id());

        assertThat(fetched.readyToStart()).isTrue();
    }

    @Test
    void readyToStart_multiHopChainWithUpstreamBlockerNotDone_isFalse() {
        // A blocks B blocks C; B is done but A (B's own blocker) is not — mirrors the multi-step
        // blocking chain feature's core regression rule: C must stay BLOCKED. A itself is left
        // in_progress (not backlog) so it can't make the Epic ready to start on its own merits.
        GitRepo r = makeRepo("https://github.com/test/ready-multihop-blocked.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(created.id(), new StoryRequest("S", "D"));
        var a = taskService.create(story.id(), new TaskRequest("A", "D"));
        var b = taskService.create(story.id(), new TaskRequest("B", "D"));
        var c = taskService.create(story.id(), new TaskRequest("C", "D"));
        dependencyService.create(new CreateDependencyRequest("task", a.id(), "task", b.id()));
        dependencyService.create(new CreateDependencyRequest("task", b.id(), "task", c.id()));
        markTaskInProgress(a.id());
        markTaskDone(b.id());

        EpicResponse fetched = service.get(created.id());

        assertThat(fetched.readyToStart()).isFalse();
    }

    @Test
    void readyToStart_multiHopChainFullyDone_isTrue() {
        // Same shape as above, but the entire upstream chain (A and B) is done — C's blockers are
        // all resolved, so C (still backlog) makes the Epic ready to start.
        GitRepo r = makeRepo("https://github.com/test/ready-multihop-done.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(created.id(), new StoryRequest("S", "D"));
        var a = taskService.create(story.id(), new TaskRequest("A", "D"));
        var b = taskService.create(story.id(), new TaskRequest("B", "D"));
        var c = taskService.create(story.id(), new TaskRequest("C", "D"));
        dependencyService.create(new CreateDependencyRequest("task", a.id(), "task", b.id()));
        dependencyService.create(new CreateDependencyRequest("task", b.id(), "task", c.id()));
        markTaskDone(a.id());
        markTaskDone(b.id());

        EpicResponse fetched = service.get(created.id());

        assertThat(fetched.readyToStart()).isTrue();
    }

    @Test
    void readyToStart_blockedOnlyByExternalItemInDifferentEpic_usesThatItemsLiveStatus() {
        // The in-Epic Story is deliberately left with no Tasks: a Story with a backlog Task is
        // ALSO its own independent readiness candidate (Decision 2 treats every Story/Task
        // independently), and since that Task has no blocking edge of its own, it would make the
        // Epic ready to start on its own merits regardless of what blocks the Story — masking the
        // very interaction this test wants to isolate. A bare Story (no Tasks) is the Epic's only
        // candidate, so its own readiness is the only thing that can make the Epic ready.
        GitRepo r = makeRepo("https://github.com/test/ready-external-blocked.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));

        GitRepo otherRepo = makeRepo("https://github.com/test/ready-external-blocked-other.git");
        EpicResponse otherEpic = service.create(new EpicRequest("Other", "D", null, otherRepo.getId()), null);
        var otherStory = storyService.create(otherEpic.id(), new StoryRequest("Other S", "D"));
        var externalBlocker = taskService.create(otherStory.id(), new TaskRequest("Other T", "D"));
        dependencyService.create(new CreateDependencyRequest("task", externalBlocker.id(), "story", story.id()));

        // The external blocker is still backlog (not done): the in-Epic Story stays blocked, so
        // this Epic (whose only item is that Story) is not ready to start.
        assertThat(service.get(epic.id()).readyToStart()).isFalse();

        markTaskDone(externalBlocker.id());

        // Once the external blocker's own (live) status flips to done, the in-Epic Story becomes
        // ready and the Epic is ready to start — proving the status is read live, not cached.
        assertThat(service.get(epic.id()).readyToStart()).isTrue();
    }

    @Test
    void readyToStart_doneEpic_isFalse() {
        GitRepo r = makeRepo("https://github.com/test/ready-done-epic.git");
        EpicResponse epic = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskDone(task.id());

        assertThat(service.get(epic.id()).readyToStart()).isFalse();
    }

    @Test
    void readyToStart_singleItemPathMatchesBatchedListPath() {
        // create()/update()/get() (single-item toResponse) must not diverge from list()'s batched
        // rollup for the same Epic (Decision 3: one definition, not two).
        GitRepo r = makeRepo("https://github.com/test/ready-single-vs-batch.git");
        EpicResponse created = service.create(new EpicRequest("T", "D", null, r.getId()), null);
        var story = storyService.create(created.id(), new StoryRequest("S", "D"));
        taskService.create(story.id(), new TaskRequest("T", "D"));

        EpicResponse fromGet = service.get(created.id());
        assertThat(fromGet.readyToStart()).isTrue();

        Page<EpicResponse> page = service.list(null, null, Pageable.unpaged());
        EpicResponse fromList = page.getContent().stream()
                .filter(e -> e.id().equals(created.id()))
                .findFirst()
                .orElseThrow();
        assertThat(fromList.readyToStart()).isEqualTo(fromGet.readyToStart());

        EpicResponse updated = service.update(created.id(), new EpicRequest("T2", "D2", null, r.getId()));
        assertThat(updated.readyToStart()).isEqualTo(fromGet.readyToStart());
    }

    @Test
    void list_readyToStartFilter_excludesUnreadyIncludesReady() {
        GitRepo r = makeRepo("https://github.com/test/ready-filter.git");
        EpicResponse readyEpic = service.create(new EpicRequest("Ready Epic", "D", null, r.getId()), null);
        var readyStory = storyService.create(readyEpic.id(), new StoryRequest("S", "D"));
        taskService.create(readyStory.id(), new TaskRequest("T", "D"));

        // The Story's own rollup status must leave "backlog" too (not just the blocked Task),
        // otherwise the Story itself — having no blocking edge of its own — would independently
        // count as ready-to-start work regardless of the Task-level block (Decision 2 treats
        // Story/Task readiness independently). Task A started (in_progress) trips the Story's
        // rollup to "in_progress" while itself not counting toward readyToStart (not backlog);
        // Task A being not-done is what keeps Task C (blocked transitively through done Task B)
        // BLOCKED — mirrors the multi-hop chain regression this feature must not reintroduce.
        EpicResponse blockedEpic = service.create(new EpicRequest("Blocked Epic", "D", null, r.getId()), null);
        var blockedStory = storyService.create(blockedEpic.id(), new StoryRequest("S", "D"));
        var taskA = taskService.create(blockedStory.id(), new TaskRequest("A", "D"));
        var taskB = taskService.create(blockedStory.id(), new TaskRequest("B", "D"));
        var taskC = taskService.create(blockedStory.id(), new TaskRequest("C", "D"));
        dependencyService.create(new CreateDependencyRequest("task", taskA.id(), "task", taskB.id()));
        dependencyService.create(new CreateDependencyRequest("task", taskB.id(), "task", taskC.id()));
        markTaskInProgress(taskA.id());
        markTaskDone(taskB.id());

        EpicResponse doneEpic = service.create(new EpicRequest("Done Epic", "D", null, r.getId()), null);
        var doneStory = storyService.create(doneEpic.id(), new StoryRequest("S", "D"));
        var doneTask = taskService.create(doneStory.id(), new TaskRequest("T", "D"));
        markTaskDone(doneTask.id());

        Page<EpicResponse> filtered = service.list(null, true, PageRequest.of(0, 20));

        List<UUID> ids = filtered.getContent().stream().map(EpicResponse::id).toList();
        assertThat(ids).contains(readyEpic.id());
        assertThat(ids).doesNotContain(blockedEpic.id(), doneEpic.id());
        assertThat(filtered.getContent()).allMatch(EpicResponse::readyToStart);
    }

    @Test
    void list_readyToStartFilter_paginationReflectsFilteredCountNotUnfilteredCount() {
        GitRepo r = makeRepo("https://github.com/test/ready-filter-pagination.git");
        EpicResponse readyEpic = service.create(new EpicRequest("Ready Only Epic", "D", null, r.getId()), null);
        var readyStory = storyService.create(readyEpic.id(), new StoryRequest("S", "D"));
        taskService.create(readyStory.id(), new TaskRequest("T", "D"));

        // Several Epics with no ready work at all — they'd inflate totalElements if the filter's
        // pagination metadata were computed against the unfiltered set.
        for (int i = 0; i < 3; i++) {
            service.create(new EpicRequest("Empty Epic " + i, "D", null, r.getId()), null);
        }

        Page<EpicResponse> filtered = service.list(null, true, PageRequest.of(0, 20));

        assertThat(filtered.getTotalElements()).isEqualTo(1);
        assertThat(filtered.getContent()).hasSize(1);
        assertThat(filtered.getContent().get(0).id()).isEqualTo(readyEpic.id());
    }

    @Test
    void list_readyToStartFilter_noMatches_returnsEmptyPageWithZeroPagination() {
        GitRepo r = makeRepo("https://github.com/test/ready-filter-no-matches.git");
        service.create(new EpicRequest("Never Ready Epic", "D", null, r.getId()), null);

        Page<EpicResponse> filtered = service.list(null, true, PageRequest.of(0, 20));

        assertThat(filtered.getContent()).isEmpty();
        assertThat(filtered.getTotalElements()).isEqualTo(0);
        assertThat(filtered.getTotalPages()).isEqualTo(0);
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
