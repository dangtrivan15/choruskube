package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.StoryUpdateRequest;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DefaultStoryServiceTest extends BaseTest {

    @Autowired
    private StoryService service;

    @Autowired
    private EpicService epicService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private TaskRepository taskRepo;

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
    void create_underEpic_returnsStoryWithEpicId() {
        EpicResponse epic = makeEpic("https://github.com/test/story-one.git");

        StoryResponse story = service.create(epic.id(), new StoryRequest("Story title", "Story desc"));

        assertThat(story.epicId()).isEqualTo(epic.id());
        assertThat(story.title()).isEqualTo("Story title");
        assertThat(story.status()).isEqualTo("backlog");
        assertThat(story.progress().totalTasks()).isZero();
    }

    @Test
    void create_underUnknownEpic_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(unknown, new StoryRequest("T", "D")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void create_withRunId_underEpicInRunsSoftwareProject_succeeds() {
        GitRepo r = makeRepo("https://github.com/test/story-runid-proj-ok.git");
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);

        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"), UUID.randomUUID(), r.getId());

        assertThat(story.epicId()).isEqualTo(epic.id());
        assertThat(story.title()).isEqualTo("S");
    }

    @Test
    void create_withRunId_underEpicOutsideRunsSoftwareProject_throwsForbidden() {
        GitRepo r1 = makeRepo("https://github.com/test/story-runid-proj-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/story-runid-proj-b.git");
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r1.getId()), null);

        // r2 is a real, different SoftwareProject in the same (only, OSS single-tenant) org — the
        // run's resolved software_project_id must still match the Epic's own, or the Story would
        // silently attach to an Epic outside the run's actual target project.
        assertThatThrownBy(() -> service.create(epic.id(), new StoryRequest("S", "D"), UUID.randomUUID(), r2.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_returnsStoriesForEpicNewestFirst() throws InterruptedException {
        EpicResponse epic = makeEpic("https://github.com/test/story-list.git");
        StoryResponse older = service.create(epic.id(), new StoryRequest("Older", "D"));
        Thread.sleep(5);
        StoryResponse newer = service.create(epic.id(), new StoryRequest("Newer", "D"));

        List<StoryResponse> result = service.list(epic.id());
        assertThat(result).extracting(StoryResponse::id).containsExactly(newer.id(), older.id());
    }

    // ── readiness (Decision 1/2) — the flat list endpoint now populates the field the Roadmap
    // Graph View has always computed, via the same shared EpicReadinessAssembler ──────────────

    @Test
    void list_storyWithNoDependencyEdges_isReady() {
        EpicResponse epic = makeEpic("https://github.com/test/story-readiness-none.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));

        List<StoryResponse> result = service.list(epic.id());

        assertThat(result).extracting(StoryResponse::readiness).containsExactly(Readiness.READY);
        assertThat(story.readiness()).isNull(); // create() itself still returns null (Decision 1 scopes list only)
    }

    @Test
    void list_storyBlockedByUnfinishedDependency_isBlocked() {
        EpicResponse epic = makeEpic("https://github.com/test/story-readiness-blocked.git");
        StoryResponse blocking = service.create(epic.id(), new StoryRequest("Blocking", "D"));
        StoryResponse blocked = service.create(epic.id(), new StoryRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("story", blocking.id(), "story", blocked.id()));

        List<StoryResponse> result = service.list(epic.id());

        assertThat(readinessOf(result, blocked.id())).isEqualTo(Readiness.BLOCKED);
        assertThat(readinessOf(result, blocking.id())).isEqualTo(Readiness.READY);
    }

    @Test
    void list_storyBlockedBySiblingStorysUnfinishedTask_isBlocked() {
        // Decision 3: the readiness walk is bounded to the whole Epic, not the requested Story
        // alone — a Story in the same Epic can be blocked by a Task under a completely different
        // sibling Story.
        EpicResponse epic = makeEpic("https://github.com/test/story-readiness-cross-story.git");
        StoryResponse blockerStory = service.create(epic.id(), new StoryRequest("Blocker Story", "D"));
        var blockerTask = taskService.create(blockerStory.id(), new TaskRequest("Blocker Task", "D"));
        StoryResponse blocked = service.create(epic.id(), new StoryRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blockerTask.id(), "story", blocked.id()));

        List<StoryResponse> result = service.list(epic.id());

        assertThat(readinessOf(result, blocked.id())).isEqualTo(Readiness.BLOCKED);
    }

    @Test
    void get_doesNotPopulateReadiness() {
        // Decision 1: only the flat list endpoints (and the Roadmap Graph View) compute
        // readiness — single-item reads are unaffected and keep returning null.
        EpicResponse epic = makeEpic("https://github.com/test/story-readiness-get-null.git");
        StoryResponse blocking = service.create(epic.id(), new StoryRequest("Blocking", "D"));
        StoryResponse blocked = service.create(epic.id(), new StoryRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("story", blocking.id(), "story", blocked.id()));

        StoryResponse fetched = service.get(blocked.id());

        assertThat(fetched.readiness()).isNull();
    }

    private static Readiness readinessOf(List<StoryResponse> stories, UUID storyId) {
        return stories.stream()
                .filter(s -> s.id().equals(storyId))
                .findFirst()
                .orElseThrow()
                .readiness();
    }

    @Test
    void update_replacesFields() {
        EpicResponse epic = makeEpic("https://github.com/test/story-update.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("Orig", "Desc"));

        StoryResponse updated = service.update(story.id(), new StoryUpdateRequest("New", "New Desc"));

        assertThat(updated.title()).isEqualTo("New");
        assertThat(updated.description()).isEqualTo("New Desc");
    }

    @Test
    void update_withStartedTask_throwsConflict() {
        EpicResponse epic = makeEpic("https://github.com/test/story-update-blocked.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        assertThatThrownBy(() -> service.update(story.id(), new StoryUpdateRequest("New", "D")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_withStartedTask_throwsConflict() {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-blocked.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        assertThatThrownBy(() -> service.delete(story.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_withNoStartedTasks_succeeds() {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-ok.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));

        service.delete(story.id());

        assertThatThrownBy(() -> service.get(story.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_withBacklogTask_cascadesToTask() {
        // Story/Task have a plain UUID FK column (task.story_id), not a JPA @OneToMany
        // association, so service.delete() issues a single-table SQL DELETE on the story row and
        // relies entirely on the DB-level `ON DELETE CASCADE` declared in V2__work_hierarchy.sql to
        // remove the Task. Assert that cascade actually happens against the real database.
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-cascade.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));

        service.delete(story.id());
        // repo.delete(story) only removes the story row from the JPA persistence context; Story/Task
        // have no mapped association, so Hibernate's auto-flush heuristics don't know this Task
        // query depends on the pending Story delete. Flush explicitly so the DB-level ON DELETE
        // CASCADE has actually fired, then clear the persistence context so the findById check
        // below hits the database instead of returning the still-managed, pre-cascade instance from
        // the first-level cache.
        entityManager.flush();
        entityManager.clear();

        assertThat(taskRepo.findById(task.id())).isEmpty();
    }

    @Test
    void delete_withDependencyEdge_alsoRemovesDependency() {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-dep.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        StoryResponse other = service.create(epic.id(), new StoryRequest("Other", "D"));
        DependencyEdgeResponse edge =
                dependencyService.create(new CreateDependencyRequest("story", other.id(), "story", story.id()));

        service.delete(story.id());

        assertThat(dependencyRepo.findById(edge.id())).isEmpty();
    }

    @Test
    void delete_withDescendantTaskDependencyEdge_alsoRemovesDependency() {
        // The Task row itself is removed by the DB-level ON DELETE CASCADE (see
        // delete_cascadesToTasks above), which bypasses DefaultTaskService#delete's own
        // work_item_dependency cleanup entirely. Assert that Story delete cleans up dependency
        // edges referencing its descendant Tasks too, not just edges on the Story's own id.
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-task-dep.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        StoryResponse other = service.create(epic.id(), new StoryRequest("Other", "D"));
        DependencyEdgeResponse edge =
                dependencyService.create(new CreateDependencyRequest("story", other.id(), "task", task.id()));

        service.delete(story.id());

        assertThat(dependencyRepo.findById(edge.id())).isEmpty();
    }

    // ── global list(stage, pageable) board listing ────────────────────────────────

    @Test
    void create_defaultsStageToBacklog() {
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-default.git");

        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));

        assertThat(created.stage()).isEqualTo("backlog");
    }

    @Test
    void listBoard_unfiltered_returnsAllStories() {
        EpicResponse epic = makeEpic("https://github.com/test/story-board-list-all.git");
        StoryResponse s1 = service.create(epic.id(), new StoryRequest("S1", "D"));
        StoryResponse s2 = service.create(epic.id(), new StoryRequest("S2", "D"));

        Page<StoryResponse> page = service.list(
                null, null, PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(page.getContent()).extracting(StoryResponse::id).contains(s1.id(), s2.id());
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void listBoard_filteredByStage_returnsOnlyMatchingRows() {
        EpicResponse epic = makeEpic("https://github.com/test/story-board-list-filtered.git");
        StoryResponse backlogStory = service.create(epic.id(), new StoryRequest("Still Backlog", "D"));
        StoryResponse movedStory = service.create(epic.id(), new StoryRequest("Moved", "D"));
        service.updateStage(movedStory.id(), WorkItemStatus.in_progress);

        Page<StoryResponse> backlogPage = service.list(
                WorkItemStatus.backlog,
                null,
                PageRequest.of(0, 20, Sort.by("createdAt").descending()));
        Page<StoryResponse> inProgressPage = service.list(
                WorkItemStatus.in_progress,
                null,
                PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(backlogPage.getContent()).extracting(StoryResponse::id).contains(backlogStory.id());
        assertThat(backlogPage.getContent()).extracting(StoryResponse::id).doesNotContain(movedStory.id());
        assertThat(inProgressPage.getContent()).extracting(StoryResponse::id).contains(movedStory.id());
        assertThat(inProgressPage.getContent()).extracting(StoryResponse::id).doesNotContain(backlogStory.id());
    }

    @Test
    void listBoard_readinessStaysNull_usesSharedSingleItemMapper() {
        // Mirrors DefaultTaskServiceTest#list_readinessStaysNull_usesSharedSingleItemMapper: the
        // global board listing deliberately reuses the shared single-item mapper (the same one
        // get()/create() use), not the Roadmap Graph View's EpicReadinessAssembler-backed path.
        EpicResponse epic = makeEpic("https://github.com/test/story-board-list-readiness.git");
        service.create(epic.id(), new StoryRequest("S", "D"));

        Page<StoryResponse> page = service.list(
                null, null, PageRequest.of(0, 20, Sort.by("createdAt").descending()));

        assertThat(page.getContent()).extracting(StoryResponse::readiness).containsOnlyNulls();
    }

    // ── updateStage: roadmap board stage moves ────────────────────────────────────

    @Test
    void updateStage_persistsNewStage_leavesStatusAndProgressUnchanged() {
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-persist.git");
        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(created.id(), new TaskRequest("T", "D"));
        markTaskDone(task.id());
        StoryResponse beforeStageMove = service.get(created.id());

        StoryResponse updated = service.updateStage(created.id(), WorkItemStatus.rolled_out);

        assertThat(updated.stage()).isEqualTo("rolled_out");
        // Decision: stage is fully decoupled from the read-time status rollup.
        assertThat(updated.status()).isEqualTo(beforeStageMove.status());
        assertThat(updated.progress().totalTasks())
                .isEqualTo(beforeStageMove.progress().totalTasks());
        assertThat(updated.progress().doneTasks())
                .isEqualTo(beforeStageMove.progress().doneTasks());

        StoryResponse refetched = service.get(created.id());
        assertThat(refetched.stage()).isEqualTo("rolled_out");
    }

    @Test
    void updateStage_publishesRoadmapItemChangedEvent() {
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-event.git");
        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));

        service.updateStage(created.id(), WorkItemStatus.in_progress);

        verify(runEventPublisher).publishRoadmapItemChanged(eq("story"), eq(created.id()), eq("in_progress"));
    }

    @Test
    void updateStage_writesAuditEntryWithBeforeAfterStage() {
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-audit.git");
        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));

        service.updateStage(created.id(), WorkItemStatus.rolled_out);

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditSink)
                .record(eq(AuditSink.STORY_STAGE_UPDATED), eq("story"), eq(created.id()), detailCaptor.capture());
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
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-done-rejected.git");
        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));

        assertThatThrownBy(() -> service.updateStage(created.id(), WorkItemStatus.done))
                .isInstanceOf(BadRequestException.class);

        StoryResponse after = service.get(created.id());
        assertThat(after.stage()).isEqualTo("backlog");
        verify(auditSink, never()).record(eq(AuditSink.STORY_STAGE_UPDATED), any(), any(), any());
        verify(runEventPublisher, never()).publishRoadmapItemChanged(eq("story"), eq(created.id()), eq("done"));
    }

    @Test
    void updateStage_withStartedDescendantTask_succeeds() {
        // Proves the "no edit once started" guard used by the full update() edit path does NOT
        // apply to stage moves (Decision 2 of the plan: mirrors DefaultEpicService#updateStage).
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-started-task.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        StoryResponse updated = service.updateStage(story.id(), WorkItemStatus.rolled_out);

        assertThat(updated.stage()).isEqualTo("rolled_out");
    }

    @Test
    void rollup_allTasksDone_statusIsDone() {
        EpicResponse epic = makeEpic("https://github.com/test/story-rollup-done.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskDone(task.id());

        StoryResponse fetched = service.get(story.id());
        assertThat(fetched.status()).isEqualTo("done");
        assertThat(fetched.progress().doneTasks()).isEqualTo(1);
    }

    @Test
    void rollup_emptyStory_statusIsBacklog() {
        EpicResponse epic = makeEpic("https://github.com/test/story-rollup-empty.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));

        StoryResponse fetched = service.get(story.id());
        assertThat(fetched.status()).isEqualTo("backlog");
    }

    // ── updatePriority: Story priority field (mirrors updateStage) ────────────────

    @Test
    void create_defaultsPriorityToMedium() {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-default.git");

        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));

        assertThat(created.priority()).isEqualTo("medium");
    }

    @Test
    void create_withExplicitPriority_persistsIt() {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-explicit.git");

        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D", Priority.high));

        assertThat(created.priority()).isEqualTo("high");
    }

    @Test
    void updatePriority_persistsNewPriority() {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-persist.git");
        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));

        StoryResponse updated = service.updatePriority(created.id(), Priority.high);

        assertThat(updated.priority()).isEqualTo("high");
        StoryResponse refetched = service.get(created.id());
        assertThat(refetched.priority()).isEqualTo("high");
    }

    @Test
    void updatePriority_withStartedDescendantTask_succeeds() {
        // Proves the "no edit once started" guard used by the full update() edit path does NOT
        // apply to priority moves — mirrors updateStage_withStartedDescendantTask_succeeds.
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-started-task.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        StoryResponse updated = service.updatePriority(story.id(), Priority.low);

        assertThat(updated.priority()).isEqualTo("low");
    }

    @Test
    void updatePriority_writesAuditEntryWithBeforeAfterPriority() {
        // Mirrors updateStage_writesAuditEntryWithBeforeAfterStage: a priority move is audited like
        // every other roadmap mutation, with structurally correct before/after.
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-audit.git");
        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D"));

        service.updatePriority(created.id(), Priority.high);

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditSink)
                .record(eq(AuditSink.STORY_PRIORITY_UPDATED), eq("story"), eq(created.id()), detailCaptor.capture());
        JsonNode detail = readTree(detailCaptor.getValue());
        assertThat(detail.path("before").path("priority").asText()).isEqualTo("medium");
        assertThat(detail.path("after").path("priority").asText()).isEqualTo("high");
    }

    @Test
    void update_leavesPriorityUnchanged() {
        // The full PUT edit (StoryUpdateRequest) has no priority field, so it must never move it.
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-put-noop.git");
        StoryResponse created = service.create(epic.id(), new StoryRequest("S", "D", Priority.high));

        StoryResponse updated = service.update(created.id(), new StoryUpdateRequest("New", "New Desc"));

        assertThat(updated.priority()).isEqualTo("high");
    }

    @Test
    void listBoard_filteredByPriority_returnsOnlyMatchingRows() {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-filter.git");
        StoryResponse highStory = service.create(epic.id(), new StoryRequest("High", "D", Priority.high));
        StoryResponse lowStory = service.create(epic.id(), new StoryRequest("Low", "D", Priority.low));

        Page<StoryResponse> highPage = service.list(null, Priority.high, PageRequest.of(0, 20));

        assertThat(highPage.getContent()).extracting(StoryResponse::id).contains(highStory.id());
        assertThat(highPage.getContent()).extracting(StoryResponse::id).doesNotContain(lowStory.id());
    }

    @Test
    void listBoard_sortedByPriorityDescending_ordersHighToLow() {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-sort.git");
        StoryResponse lowStory = service.create(epic.id(), new StoryRequest("Low", "D", Priority.low));
        StoryResponse highStory = service.create(epic.id(), new StoryRequest("High", "D", Priority.high));
        StoryResponse mediumStory = service.create(epic.id(), new StoryRequest("Medium", "D", Priority.medium));

        Page<StoryResponse> page = service.list(
                null, null, PageRequest.of(0, 20, Sort.by("priority").descending()));

        List<UUID> ids = page.getContent().stream().map(StoryResponse::id).toList();
        assertThat(ids.indexOf(highStory.id())).isLessThan(ids.indexOf(mediumStory.id()));
        assertThat(ids.indexOf(mediumStory.id())).isLessThan(ids.indexOf(lowStory.id()));
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

    private EpicResponse makeEpic(String url) {
        GitRepo r = makeRepo(url);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
