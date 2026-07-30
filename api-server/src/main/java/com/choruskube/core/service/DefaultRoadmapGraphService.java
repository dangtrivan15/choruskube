package com.choruskube.core.service;

import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.ExternalBlockerRef;
import com.choruskube.core.dto.RoadmapGraphSnapshot;
import com.choruskube.core.dto.RunSummary;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link RoadmapGraphService} (Decision 8). */
@Service
public class DefaultRoadmapGraphService implements RoadmapGraphService {

    /** Cap on embedded per-Task run history (Decision 3) — the rest is available via the
     * existing paginated {@code GET .../tasks/{id}/runs} (and its internal mirror). */
    private static final int RECENT_RUNS_LIMIT = 5;

    private final EpicService epicService;
    private final StoryService storyService;
    private final TaskService taskService;
    private final WorkItemDependencyRepository dependencyRepo;
    // Used only to resolve title/owning-Epic/status for items OUTSIDE the requested Epic (external
    // blockers) — everything belonging to the requested Epic itself goes through
    // epicService/storyService/taskService above, the same org-scoped path Epic-detail code uses
    // (or, on the internal path, the equivalent assertSameOrg/project-scoped *Internal methods).
    // Reads through these repositories are gated by authService.checkOrgAccess/assertSameOrg below
    // (the same per-item check DefaultWorkItemDependencyService#create/delete use) rather than the
    // org-scoped service layer, because there is no single-item org-scoped read on
    // Story/TaskService that doesn't also require the item's parent Epic/Story context this method
    // doesn't have.
    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;
    private final EpicRepository epicRepo;
    private final AuthorizationService authService;

    public DefaultRoadmapGraphService(
            EpicService epicService,
            StoryService storyService,
            TaskService taskService,
            WorkItemDependencyRepository dependencyRepo,
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            EpicRepository epicRepo,
            AuthorizationService authService) {
        this.epicService = epicService;
        this.storyService = storyService;
        this.taskService = taskService;
        this.dependencyRepo = dependencyRepo;
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.epicRepo = epicRepo;
        this.authService = authService;
    }

    @Override
    @Transactional(readOnly = true)
    public RoadmapGraphSnapshot getGraph(UUID epicId) {
        // epicService.get/storyService.list/taskService.list are the same org-scoped, Not-Found
        // throwing calls DefaultEpicService's own Epic-detail assembly uses — this deliberately
        // does not bypass that scoping by reading EpicRepository/StoryRepository/TaskRepository
        // directly for the requested Epic's own tree.
        EpicResponse epic = epicService.get(epicId);
        List<StoryResponse> stories = storyService.list(epicId);
        List<TaskResponse> tasks =
                stories.stream().flatMap(s -> taskService.list(s.id()).stream()).toList();
        return assemble(epic, stories, tasks, false, null);
    }

    @Override
    @Transactional(readOnly = true)
    public RoadmapGraphSnapshot getGraph(UUID epicId, UUID runId, UUID runSoftwareProjectId) {
        // Mirrors getGraph(UUID) above but through the *Internal variants, which validate via
        // assertSameOrg/project-match instead of checkOrgAccess (no tenant context on this path —
        // see Decision 1, Decision 5, and the javadoc on EpicService#getInternal).
        EpicResponse epic = epicService.getInternal(epicId, runId, runSoftwareProjectId);
        List<StoryResponse> stories = storyService.listInternal(epicId, runId, runSoftwareProjectId);
        List<TaskResponse> tasks = stories.stream()
                .flatMap(s -> taskService.listInternal(s.id(), runId, runSoftwareProjectId).stream())
                .toList();
        return assemble(epic, stories, tasks, true, runId);
    }

    /**
     * Shared assembly for both the public and internal read paths: computes dependency edges,
     * external blockers, per-node readiness (Decision 2), and per-Task capped run history
     * (Decision 3) from an already-fetched (and already-authorized) Epic/Story/Task set.
     *
     * @param internal whether to authorize/resolve cross-epic references via the internal
     *     ({@code assertSameOrg}) path or the public ({@code checkOrgAccess}) path
     * @param runId the calling run's id, used only when {@code internal} is true
     */
    private RoadmapGraphSnapshot assemble(
            EpicResponse epic, List<StoryResponse> stories, List<TaskResponse> tasks, boolean internal, UUID runId) {
        Set<UUID> candidateIds = new HashSet<>();
        stories.forEach(s -> candidateIds.add(s.id()));
        tasks.forEach(t -> candidateIds.add(t.id()));

        List<WorkItemDependency> rows = candidateIds.isEmpty()
                ? List.of()
                : dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(candidateIds, candidateIds);

        Map<UUID, String> statusById = new HashMap<>();
        stories.forEach(s -> statusById.put(s.id(), s.status()));
        tasks.forEach(t -> statusById.put(t.id(), t.status()));

        List<DependencyEdgeResponse> dependencies = new ArrayList<>();
        List<ExternalBlockerRef> externalBlockers = new ArrayList<>();

        for (WorkItemDependency row : rows) {
            boolean blockingInside = candidateIds.contains(row.getBlockingItemId());
            boolean blockedInside = candidateIds.contains(row.getBlockedItemId());

            if (blockingInside && blockedInside) {
                dependencies.add(toEdgeResponse(row));
            } else if (!blockingInside) {
                ExternalBlockerResolution resolution =
                        resolveExternalBlocker(row.getBlockingItemType(), row.getBlockingItemId(), internal, runId);
                externalBlockers.add(resolution.ref());
                // Feeds the transitive walk below: a direct blocker's own status still gates
                // readiness even when it lives outside this Epic (Decision 2), so its status must
                // be resolvable by id like every in-Epic item's.
                statusById.put(row.getBlockingItemId(), resolution.status());
            } else {
                // blockingInside && !blockedInside: the BLOCKED side lives outside this Epic —
                // shown as an external blocker reference for display, but it doesn't affect
                // readiness of anything IN this Epic (blockedInside is false), so its status is
                // never looked up.
                ExternalBlockerResolution resolution =
                        resolveExternalBlocker(row.getBlockedItemType(), row.getBlockedItemId(), internal, runId);
                externalBlockers.add(resolution.ref());
            }
        }

        // Readiness: BLOCKED iff any item reachable by walking the blocking chain backward from
        // this node is not yet done — not just its direct blocker (multi-step blocking chain
        // feature, Decisions 1/2). The walk is bounded to `rows` (this Epic's own candidate set,
        // loaded above), so an external blocker's own upstream chain is not followed further
        // (Decision 2) — only its already-resolved status (added to statusById above) gates
        // readiness at that hop.
        Map<UUID, Readiness> readinessById =
                TransitiveReadinessResolver.computeReadiness(candidateIds, rows, statusById::get);

        List<StoryResponse> storiesWithReadiness = stories.stream()
                .map(s -> withReadiness(s, readinessById.get(s.id())))
                .toList();
        List<TaskResponse> tasksWithExtras = tasks.stream()
                .map(t -> withReadinessAndRuns(t, readinessById.get(t.id()), internal))
                .toList();

        return new RoadmapGraphSnapshot(epic, storiesWithReadiness, tasksWithExtras, dependencies, externalBlockers);
    }

    private static StoryResponse withReadiness(StoryResponse s, Readiness readiness) {
        return new StoryResponse(
                s.id(),
                s.epicId(),
                s.title(),
                s.description(),
                s.status(),
                readiness,
                s.progress(),
                s.createdAt(),
                s.updatedAt());
    }

    private TaskResponse withReadinessAndRuns(TaskResponse t, Readiness readiness, boolean internal) {
        Page<RunSummary> page = internal
                ? taskService.listRunsInternal(t.id(), PageRequest.of(0, RECENT_RUNS_LIMIT))
                : taskService.listRuns(t.id(), PageRequest.of(0, RECENT_RUNS_LIMIT));
        return new TaskResponse(
                t.id(),
                t.storyId(),
                t.title(),
                t.description(),
                t.status(),
                t.softwareProject(),
                t.repos(),
                t.latestRunId(),
                t.latestRunStatus(),
                readiness,
                page.getContent(),
                page.getTotalElements(),
                t.createdAt(),
                t.updatedAt());
    }

    private DependencyEdgeResponse toEdgeResponse(WorkItemDependency row) {
        return new DependencyEdgeResponse(
                row.getId(),
                row.getBlockingItemType().name(),
                row.getBlockingItemId(),
                row.getBlockedItemType().name(),
                row.getBlockedItemId(),
                row.getCreatedAt());
    }

    private record ExternalBlockerResolution(ExternalBlockerRef ref, String status) {}

    /**
     * Resolves title/owning-Epic/status for an item OUTSIDE the requested Epic, guarding against
     * leaking it to a caller outside its org first. Uses {@code checkOrgAccess} (request-scoped)
     * on the public path or {@code assertSameOrg} against the calling run (no tenant context) on
     * the internal path — mirrors the split between {@link EpicService#get} and
     * {@link EpicService#getInternal}.
     *
     * <p>Today every dependency edge is created with both endpoints in the same org as the caller
     * (DefaultWorkItemDependencyService#create), so this never rejects in practice — but it closes
     * the gap defensively for any future path (bulk import, admin ownership-transfer, direct
     * repository writes) that could insert an edge without going through create()'s own
     * checkOrgAccess/assertSameOrg pair.
     */
    private ExternalBlockerResolution resolveExternalBlocker(
            BlockableItemType type, UUID id, boolean internal, UUID runId) {
        if (internal) {
            authService.assertSameOrg(type.name(), id, "workflow_run", runId);
        } else {
            authService.checkOrgAccess(type.name(), id);
        }
        if (type == BlockableItemType.story) {
            Story story = storyRepo.findById(id).orElseThrow(() -> new NotFoundException("Story not found: " + id));
            Epic epic = findEpic(story.getEpicId());
            List<Task> tasks = taskRepo.findByStoryIdOrderByCreatedAtDesc(id);
            String status = RollupCalculator.compute(tasks).status();
            return new ExternalBlockerResolution(
                    new ExternalBlockerRef("story", id, story.getTitle(), epic.getId(), epic.getTitle()), status);
        }
        Task task = taskRepo.findById(id).orElseThrow(() -> new NotFoundException("Task not found: " + id));
        Story story = storyRepo
                .findById(task.getStoryId())
                .orElseThrow(() -> new NotFoundException("Story not found: " + task.getStoryId()));
        Epic epic = findEpic(story.getEpicId());
        return new ExternalBlockerResolution(
                new ExternalBlockerRef("task", id, task.getTitle(), epic.getId(), epic.getTitle()),
                task.getStatus().name());
    }

    private Epic findEpic(UUID epicId) {
        return epicRepo.findById(epicId).orElseThrow(() -> new NotFoundException("Epic not found: " + epicId));
    }
}
