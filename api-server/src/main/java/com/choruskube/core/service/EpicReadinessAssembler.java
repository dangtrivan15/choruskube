package com.choruskube.core.service;

import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.ExternalBlockerRef;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.BlockerDirection;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Epic-bounded dependency-readiness assembly (Decision 2), shared by {@link
 * DefaultRoadmapGraphService} (the graph endpoint) and {@link DefaultStoryService}/{@link
 * DefaultTaskService} (the flat list endpoints, Decision 1) so all three read paths compute
 * "is this item blocked" identically instead of independently drifting. Positioned below all
 * three call sites — it depends only on repositories and {@link AuthorizationService}, never on
 * {@link EpicService}/{@link StoryService}/{@link TaskService} — so none of those services calling
 * it creates a circular Spring bean dependency.
 *
 * <p>This is a straight extraction of logic that previously lived entirely inside {@code
 * DefaultRoadmapGraphService#assemble}: loading the dependency edges touching a candidate Story/
 * Task set, resolving the status of any cross-Epic external blocker (org-checked the same way
 * that class always has), and delegating the actual transitive walk to {@link
 * TransitiveReadinessResolver}. No behavior changed by extracting it (Decision 2's own framing).
 */
@Component
class EpicReadinessAssembler {

    private final StoryRepository storyRepo;
    private final TaskRepository taskRepo;
    private final EpicRepository epicRepo;
    private final WorkItemDependencyRepository dependencyRepo;
    private final AuthorizationService authService;

    EpicReadinessAssembler(
            StoryRepository storyRepo,
            TaskRepository taskRepo,
            EpicRepository epicRepo,
            WorkItemDependencyRepository dependencyRepo,
            AuthorizationService authService) {
        this.storyRepo = storyRepo;
        this.taskRepo = taskRepo;
        this.epicRepo = epicRepo;
        this.dependencyRepo = dependencyRepo;
        this.authService = authService;
    }

    /**
     * Per-item readiness plus the intra-Epic edges and cross-Epic blocker refs touching {@code
     * candidateIds}. {@code edges} is the raw row set the transitive walk ran over, handed back so
     * a caller needing {@link TransitiveReadinessResolver#rootCauseBlockersOf} does not have to
     * re-query it.
     */
    record Assembly(
            Map<UUID, Readiness> readinessById,
            List<DependencyEdgeResponse> dependencies,
            List<ExternalBlockerRef> externalBlockers,
            List<WorkItemDependency> edges) {}

    /**
     * An Epic's full Story/Task set, pre-loaded once (Decision 3) so callers of {@link #assemble}
     * don't each re-query it — shared between {@link DefaultStoryService#list} and {@link
     * DefaultTaskService#list} (sync point from the implementation plan) so both list endpoints
     * agree on exactly one "load this Epic's candidates" behavior.
     */
    record EpicCandidates(
            List<Story> stories,
            Map<UUID, List<Task>> tasksByStoryId,
            Set<UUID> candidateIds,
            Map<UUID, String> statusById,
            Map<UUID, UUID> parentOf) {}

    /**
     * Loads every Story under {@code epicId} plus every Task under each of those Stories, and
     * derives each item's status (Story status is the rollup of its own Tasks, matching {@link
     * DefaultStoryService#toResponse}). Does not authorize the read itself — callers already do
     * that (checkOrgAccess/assertSameOrg on the Epic/Story) before calling this.
     */
    EpicCandidates loadEpicCandidates(UUID epicId) {
        List<Story> stories = storyRepo.findByEpicIdOrderByCreatedAtDesc(epicId);
        Map<UUID, List<Task>> tasksByStoryId = new HashMap<>();
        Map<UUID, String> statusById = new HashMap<>();
        Map<UUID, UUID> parentOf = new HashMap<>();
        Set<UUID> candidateIds = new HashSet<>();
        List<Task> allTasks = new ArrayList<>();
        for (Story story : stories) {
            List<Task> tasks = taskRepo.findByStoryIdOrderByCreatedAtDesc(story.getId());
            tasksByStoryId.put(story.getId(), tasks);
            candidateIds.add(story.getId());
            parentOf.put(story.getId(), epicId);
            statusById.put(story.getId(), storyStatus(story, tasks));
            for (Task task : tasks) {
                candidateIds.add(task.getId());
                parentOf.put(task.getId(), story.getId());
                statusById.put(task.getId(), task.getStatus().name());
                allTasks.add(task);
            }
        }
        // The Epic is a candidate in its own right now that edges can target it. Its status is the
        // rollup of every descendant Task, matching how DefaultEpicService renders it.
        candidateIds.add(epicId);
        statusById.put(epicId, epicStatus(epicId, allTasks));
        return new EpicCandidates(stories, tasksByStoryId, candidateIds, statusById, parentOf);
    }

    /**
     * An Epic counts as satisfied when its Tasks all report done, or when a human has moved it to
     * the {@code rolled_out} board lane — the only signal in the model that says "shipped", which
     * a Task rollup cannot express. The stage check runs first, so an explicit human "shipped"
     * outranks emptiness: an Epic with no Tasks and no {@code rolled_out} stage is never satisfied.
     */
    private String epicStatus(UUID epicId, List<Task> allTasks) {
        Epic epic = findEpic(epicId);
        if (epic.getStage() == WorkItemStatus.rolled_out) {
            return WorkItemStatus.done.name();
        }
        return RollupCalculator.compute(allTasks).status();
    }

    /**
     * A Story counts as satisfied when its Tasks all report done, or when a human has moved it to
     * the {@code rolled_out} board lane — the only signal in the model that says "shipped", which
     * a Task rollup cannot express. The stage check runs first, so an explicit human "shipped"
     * outranks emptiness: a Story with no Tasks and no {@code rolled_out} stage is never satisfied.
     */
    private String storyStatus(Story story, List<Task> tasks) {
        if (story.getStage() == WorkItemStatus.rolled_out) {
            return WorkItemStatus.done.name();
        }
        return RollupCalculator.compute(tasks).status();
    }

    /**
     * Loads dependency edges touching {@code candidateIds}, resolves any cross-Epic external
     * blocker's status (one hop only — Decision 2), and computes per-item {@link Readiness} via
     * {@link TransitiveReadinessResolver}.
     *
     * @param statusById status for every id in {@code candidateIds}; mutated copies are made
     *     internally, the caller's map is left untouched
     * @param parentOf maps a Story to its Epic and a Task to its Story, so a blocked container's
     *     readiness can be cascaded onto the work inside it after the walk below
     * @param mode which authorization path cross-Epic references are resolved through
     * @param contextId the id {@code mode} authorizes against — the calling run for {@link
     *     ReadinessAuthMode#INTERNAL_RUN}, the Autopilot for {@link ReadinessAuthMode#AUTOPILOT},
     *     unused (and normally null) for {@link ReadinessAuthMode#PUBLIC}
     */
    Assembly assemble(
            Set<UUID> candidateIds,
            Map<UUID, String> statusById,
            Map<UUID, UUID> parentOf,
            ReadinessAuthMode mode,
            UUID contextId) {
        List<WorkItemDependency> rows = candidateIds.isEmpty()
                ? List.of()
                : dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(candidateIds, candidateIds);

        Map<UUID, String> effectiveStatusById = new HashMap<>(statusById);
        List<DependencyEdgeResponse> dependencies = new ArrayList<>();
        List<ExternalBlockerRef> externalBlockers = new ArrayList<>();

        for (WorkItemDependency row : rows) {
            boolean blockingInside = candidateIds.contains(row.getBlockingItemId());
            boolean blockedInside = candidateIds.contains(row.getBlockedItemId());

            if (blockingInside && blockedInside) {
                dependencies.add(toEdgeResponse(row));
            } else if (!blockingInside) {
                // The BLOCKING side lives outside this Epic (blockedInside is therefore
                // guaranteed true — see findByBlockingItemIdInOrBlockedItemIdIn above) — the
                // external item BLOCKS the in-Epic (blocked) item.
                ExternalBlockerResolution resolution = resolveExternalBlocker(
                        row.getBlockingItemType(),
                        row.getBlockingItemId(),
                        mode,
                        contextId,
                        BlockerDirection.BLOCKING,
                        row.getBlockedItemId());
                externalBlockers.add(resolution.ref());
                // Feeds the transitive walk below: a direct blocker's own status still gates
                // readiness even when it lives outside this Epic (Decision 2), so its status must
                // be resolvable by id like every in-Epic item's.
                effectiveStatusById.put(row.getBlockingItemId(), resolution.status());
            } else {
                // blockingInside && !blockedInside: the BLOCKED side lives outside this Epic —
                // shown as an external blocker reference for display, but it doesn't affect
                // readiness of anything IN this Epic (blockedInside is false), so its status is
                // never looked up. The in-Epic (blocking) item BLOCKS the external item, so from
                // the external item's perspective it is the BLOCKED side.
                ExternalBlockerResolution resolution = resolveExternalBlocker(
                        row.getBlockedItemType(),
                        row.getBlockedItemId(),
                        mode,
                        contextId,
                        BlockerDirection.BLOCKED,
                        row.getBlockingItemId());
                externalBlockers.add(resolution.ref());
            }
        }

        // Readiness: BLOCKED iff any item reachable by walking the blocking chain backward from
        // this node is not yet done — not just its direct blocker (multi-step blocking chain
        // feature). The walk is bounded to `rows` (this Epic's own candidate set, loaded above),
        // so an external blocker's own upstream chain is not followed further (Decision 2) — only
        // its already-resolved status (added to effectiveStatusById above) gates readiness at
        // that hop.
        Map<UUID, Readiness> readinessById =
                TransitiveReadinessResolver.computeReadiness(candidateIds, rows, effectiveStatusById::get);

        // Cascade: a blocked container blocks the work inside it. Applied after the walk rather
        // than by expanding the edge set. Each item walks its own ancestor chain (at most two
        // hops in this 3-tier model: task -> story -> epic) checking whether any ancestor is
        // already BLOCKED, so the result does not depend on map iteration order.
        applyContainmentCascade(readinessById, parentOf);

        return new Assembly(readinessById, dependencies, externalBlockers, rows);
    }

    /**
     * Marks an item BLOCKED when any ancestor is BLOCKED. {@code parentOf} maps task→story and
     * story→epic, so walking upward from each item covers both tiers. An item whose ancestor is
     * absent from the map (a cross-Epic reference resolved for display only) simply stops the walk.
     */
    private static void applyContainmentCascade(Map<UUID, Readiness> readinessById, Map<UUID, UUID> parentOf) {
        for (Map.Entry<UUID, Readiness> entry : readinessById.entrySet()) {
            if (entry.getValue() == Readiness.BLOCKED) {
                continue;
            }
            UUID ancestor = parentOf.get(entry.getKey());
            Set<UUID> seen = new LinkedHashSet<>();
            while (ancestor != null && seen.add(ancestor)) {
                if (readinessById.get(ancestor) == Readiness.BLOCKED) {
                    entry.setValue(Readiness.BLOCKED);
                    break;
                }
                ancestor = parentOf.get(ancestor);
            }
        }
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
     * the internal path — mirrors the split between {@link EpicService#get} and {@link
     * EpicService#getInternal}. The Autopilot path is a third case: no tenant context AND no run,
     * so it asserts against the Autopilot's own org.
     *
     * <p>Today every dependency edge is created with both endpoints in the same org as the caller
     * (DefaultWorkItemDependencyService#create), so this never rejects in practice — but it closes
     * the gap defensively for any future path (bulk import, admin ownership-transfer, direct
     * repository writes) that could insert an edge without going through create()'s own
     * checkOrgAccess/assertSameOrg pair.
     *
     * @param direction the external item's role relative to the in-Epic item it connects to, as
     *     already determined by the caller from which side of the dependency row was outside the
     *     Epic
     * @param internalItemId the in-Epic Story/Task id this external blocker connects to, as
     *     already determined by the caller
     */
    private ExternalBlockerResolution resolveExternalBlocker(
            BlockableItemType type,
            UUID id,
            ReadinessAuthMode mode,
            UUID contextId,
            BlockerDirection direction,
            UUID internalItemId) {
        switch (mode) {
            case PUBLIC -> authService.checkOrgAccess(type.name(), id);
            case INTERNAL_RUN -> authService.assertSameOrg(type.name(), id, "workflow_run", contextId);
            case AUTOPILOT -> authService.assertSameOrg(type.name(), id, "autopilot", contextId);
        }
        if (type == BlockableItemType.epic) {
            Epic epic = findEpic(id);
            List<Story> stories = storyRepo.findByEpicIdOrderByCreatedAtDesc(id);
            List<Task> allTasks = new ArrayList<>();
            for (Story story : stories) {
                allTasks.addAll(taskRepo.findByStoryIdOrderByCreatedAtDesc(story.getId()));
            }
            String status = epicStatus(id, allTasks);
            return new ExternalBlockerResolution(
                    new ExternalBlockerRef(
                            "epic", id, epic.getTitle(), epic.getId(), epic.getTitle(), direction, internalItemId),
                    status);
        }
        if (type == BlockableItemType.story) {
            Story story = storyRepo.findById(id).orElseThrow(() -> new NotFoundException("Story not found: " + id));
            Epic epic = findEpic(story.getEpicId());
            List<Task> tasks = taskRepo.findByStoryIdOrderByCreatedAtDesc(id);
            String status = storyStatus(story, tasks);
            return new ExternalBlockerResolution(
                    new ExternalBlockerRef(
                            "story", id, story.getTitle(), epic.getId(), epic.getTitle(), direction, internalItemId),
                    status);
        }
        Task task = taskRepo.findById(id).orElseThrow(() -> new NotFoundException("Task not found: " + id));
        Story story = storyRepo
                .findById(task.getStoryId())
                .orElseThrow(() -> new NotFoundException("Story not found: " + task.getStoryId()));
        Epic epic = findEpic(story.getEpicId());
        return new ExternalBlockerResolution(
                new ExternalBlockerRef(
                        "task", id, task.getTitle(), epic.getId(), epic.getTitle(), direction, internalItemId),
                task.getStatus().name());
    }

    private Epic findEpic(UUID epicId) {
        return epicRepo.findById(epicId).orElseThrow(() -> new NotFoundException("Epic not found: " + epicId));
    }
}
