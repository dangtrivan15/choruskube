package com.choruskube.core.service;

import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.ExternalBlockerRef;
import com.choruskube.core.dto.RoadmapGraphSnapshot;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkItemDependency;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link RoadmapGraphService} (Decision 8). */
@Service
public class DefaultRoadmapGraphService implements RoadmapGraphService {

    private final EpicService epicService;
    private final StoryService storyService;
    private final TaskService taskService;
    private final WorkItemDependencyRepository dependencyRepo;
    // Used only to resolve title/owning-Epic for items OUTSIDE the requested Epic (external
    // blockers) — everything belonging to the requested Epic itself goes through
    // epicService/storyService/taskService above, the same org-scoped path Epic-detail code uses.
    // Reads through these repositories are gated by authService.checkOrgAccess below (the same
    // per-item check DefaultWorkItemDependencyService#create/delete use) rather than the
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

        Set<UUID> candidateIds = new HashSet<>();
        stories.forEach(s -> candidateIds.add(s.id()));
        tasks.forEach(t -> candidateIds.add(t.id()));

        List<WorkItemDependency> rows = candidateIds.isEmpty()
                ? List.of()
                : dependencyRepo.findByBlockingItemIdInOrBlockedItemIdIn(candidateIds, candidateIds);

        List<DependencyEdgeResponse> dependencies = new ArrayList<>();
        List<ExternalBlockerRef> externalBlockers = new ArrayList<>();
        for (WorkItemDependency row : rows) {
            boolean blockingInside = candidateIds.contains(row.getBlockingItemId());
            boolean blockedInside = candidateIds.contains(row.getBlockedItemId());
            if (blockingInside && blockedInside) {
                dependencies.add(toEdgeResponse(row));
            } else if (!blockingInside) {
                externalBlockers.add(toExternalBlockerRef(row.getBlockingItemType(), row.getBlockingItemId()));
            } else {
                externalBlockers.add(toExternalBlockerRef(row.getBlockedItemType(), row.getBlockedItemId()));
            }
        }

        return new RoadmapGraphSnapshot(epic, stories, tasks, dependencies, externalBlockers);
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

    private ExternalBlockerRef toExternalBlockerRef(BlockableItemType type, UUID id) {
        // Guards against leaking an external item's title/owning-Epic to a caller outside its org.
        // Today every dependency edge is created with both endpoints in the same org as the
        // caller (DefaultWorkItemDependencyService#create), so this never rejects in practice —
        // but it closes the gap defensively for any future path (bulk import, admin
        // ownership-transfer, direct repository writes) that could insert an edge without going
        // through create()'s own checkOrgAccess pair.
        authService.checkOrgAccess(type.name(), id);
        if (type == BlockableItemType.story) {
            Story story = storyRepo.findById(id).orElseThrow(() -> new NotFoundException("Story not found: " + id));
            Epic epic = findEpic(story.getEpicId());
            return new ExternalBlockerRef("story", id, story.getTitle(), epic.getId(), epic.getTitle());
        }
        Task task = taskRepo.findById(id).orElseThrow(() -> new NotFoundException("Task not found: " + id));
        Story story = storyRepo
                .findById(task.getStoryId())
                .orElseThrow(() -> new NotFoundException("Story not found: " + task.getStoryId()));
        Epic epic = findEpic(story.getEpicId());
        return new ExternalBlockerRef("task", id, task.getTitle(), epic.getId(), epic.getTitle());
    }

    private Epic findEpic(UUID epicId) {
        return epicRepo.findById(epicId).orElseThrow(() -> new NotFoundException("Epic not found: " + epicId));
    }
}
