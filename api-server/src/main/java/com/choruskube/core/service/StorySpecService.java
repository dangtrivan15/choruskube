package com.choruskube.core.service;

import com.choruskube.core.config.StoryDesignGraph;
import com.choruskube.core.dto.StoryConflictResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.model.enums.WorkflowRunStatusGroups;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.StoryConflictRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.StorySpecVersionRepository;
import com.choruskube.core.repository.StoryVersionView;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Story's approved spec versions and its design runs. No method authorizes: callers reach it
 * either through an org-checked Story read or from a system path that already owns the Story.
 */
@Service
public class StorySpecService {

    private final StorySpecVersionRepository versionRepo;
    private final StoryConflictRepository conflictRepo;
    private final StoryRepository storyRepo;
    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository execRepo;
    private final TaskRepository taskRepo;

    public StorySpecService(
            StorySpecVersionRepository versionRepo,
            StoryConflictRepository conflictRepo,
            StoryRepository storyRepo,
            WorkflowRunRepository runRepo,
            NodeExecutionRepository execRepo,
            TaskRepository taskRepo) {
        this.versionRepo = versionRepo;
        this.conflictRepo = conflictRepo;
        this.storyRepo = storyRepo;
        this.runRepo = runRepo;
        this.execRepo = execRepo;
        this.taskRepo = taskRepo;
    }

    @Transactional(readOnly = true)
    public Optional<StorySpecVersion> current(UUID storyId) {
        return versionRepo.findFirstByStoryIdOrderByVersionDesc(storyId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Integer> currentVersions(Collection<UUID> storyIds) {
        if (storyIds.isEmpty()) {
            return Map.of();
        }
        return versionRepo.findCurrentVersions(storyIds).stream()
                .collect(Collectors.toMap(StoryVersionView::getStoryId, StoryVersionView::getVersion));
    }

    @Transactional(readOnly = true)
    public List<StorySpecVersion> versions(UUID storyId) {
        return versionRepo.findByStoryIdOrderByVersionAsc(storyId);
    }

    @Transactional(readOnly = true)
    public StorySpecVersion version(UUID storyId, int v) {
        return versionRepo
                .findByStoryIdAndVersion(storyId, v)
                .orElseThrow(() -> new NotFoundException("Story " + storyId + " has no spec version " + v));
    }

    /** Idempotent on {@code runId}: a repeated call returns the row the first one wrote. */
    @Transactional
    public StorySpecVersion recordApproved(UUID storyId, UUID runId, String content) {
        Optional<StorySpecVersion> recorded = versionRepo.findByRunId(runId);
        if (recorded.isPresent()) {
            return recorded.get();
        }
        storyRepo.findWithLockById(storyId).orElseThrow(() -> new NotFoundException("Story not found: " + storyId));
        // Re-read under the lock: a writer we queued behind may have recorded this run already.
        recorded = versionRepo.findByRunId(runId);
        if (recorded.isPresent()) {
            return recorded.get();
        }
        StorySpecVersion row = new StorySpecVersion();
        row.setStoryId(storyId);
        row.setVersion(current(storyId).map(v -> v.getVersion() + 1).orElse(1));
        row.setContent(content);
        row.setRunId(runId);
        return versionRepo.save(row);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowRun> openDesignRun(UUID storyId) {
        return openDesignRuns(List.of(storyId)).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public Set<UUID> storiesWithOpenDesignRun(Collection<UUID> storyIds) {
        return openDesignRunIds(storyIds).keySet();
    }

    /** Story id to its open design run's id, for the Stories that have one. */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> openDesignRunIds(Collection<UUID> storyIds) {
        Map<UUID, UUID> byStory = new HashMap<>();
        for (WorkflowRun run : openDesignRuns(storyIds)) {
            byStory.putIfAbsent(run.getStoryId(), run.getId());
        }
        return byStory;
    }

    /** A Story's spec version and open design run, as the Story responses report them. */
    public record DesignState(Integer specVersion, UUID openDesignRunId) {

        /** A design run on top of an approved spec is a revision; with no spec, it is the first design. */
        public boolean underRevision() {
            return specVersion != null && openDesignRunId != null;
        }
    }

    /** Two queries for any number of Stories; every requested id gets an entry. */
    @Transactional(readOnly = true)
    public Map<UUID, DesignState> designStates(Collection<UUID> storyIds) {
        Map<UUID, Integer> versions = currentVersions(storyIds);
        Map<UUID, UUID> openRuns = openDesignRunIds(storyIds);
        Map<UUID, DesignState> states = new HashMap<>();
        for (UUID storyId : storyIds) {
            states.put(storyId, new DesignState(versions.get(storyId), openRuns.get(storyId)));
        }
        return states;
    }

    /**
     * The execution whose output a design run's approval adopts: its latest completed Story Spec
     * Review. Empty when the run never completed one.
     */
    @Transactional(readOnly = true)
    public Optional<NodeExecution> finalReview(UUID designRunId) {
        return execRepo.findFirstByWorkflowRunIdAndLabelAndStatusOrderByIterationDesc(
                designRunId, StoryDesignGraph.REVIEW, NodeExecutionStatus.completed);
    }

    /** The Story's unreleased conflicts, oldest first, each with the Task whose run raised it. */
    @Transactional(readOnly = true)
    public List<StoryConflictResponse> openConflicts(UUID storyId) {
        List<StoryConflict> conflicts = conflictRepo.findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(storyId);
        if (conflicts.isEmpty()) {
            return List.of();
        }
        Map<UUID, UUID> taskIdByRunId =
                runRepo
                        .findAllById(conflicts.stream()
                                .map(StoryConflict::getTaskRunId)
                                .collect(Collectors.toSet()))
                        .stream()
                        .filter(run -> run.getTaskId() != null)
                        .collect(Collectors.toMap(WorkflowRun::getId, WorkflowRun::getTaskId));
        Map<UUID, Task> taskById = taskRepo.findAllById(Set.copyOf(taskIdByRunId.values())).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));
        return conflicts.stream()
                .map(c -> {
                    UUID taskId = taskIdByRunId.get(c.getTaskRunId());
                    Task task = taskId == null ? null : taskById.get(taskId);
                    return new StoryConflictResponse(
                            c.getId(),
                            c.getTaskRunId(),
                            taskId,
                            task == null ? null : task.getTitle(),
                            c.getRaisedAgainstVersion(),
                            c.getRevisionRunId(),
                            c.getCreatedAt());
                })
                .toList();
    }

    private List<WorkflowRun> openDesignRuns(Collection<UUID> storyIds) {
        if (storyIds.isEmpty()) {
            return List.of();
        }
        return runRepo
                .findOpenDesignRuns(storyIds, WorkflowRunStatusGroups.ACTIVE, WorkflowRunStatus.completed)
                .stream()
                .filter(run -> Objects.nonNull(run.getStoryId()))
                .sorted(Comparator.comparing(
                        WorkflowRun::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
}
