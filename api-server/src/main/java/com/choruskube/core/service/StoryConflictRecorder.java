package com.choruskube.core.service;

import com.choruskube.core.config.TaskDeliveryGraph;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.StoryConflictRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Files a Task Delivery run's {@code story_conflict} against the Story spec version the run read,
 * when its Analyze completes. The Await gate it parks at does not exist yet at that point, so the
 * row names the Analyze execution, and the reconciler finds the gate later.
 */
@Component
public class StoryConflictRecorder {

    private static final Logger log = LoggerFactory.getLogger(StoryConflictRecorder.class);

    static final String REPORT_FILE = "story_conflict.md";
    static final String MISSING_REPORT = "(Analyze wrote no story_conflict.md)";

    private final StoryConflictRepository conflictRepo;
    private final TaskRepository taskRepo;
    private final StoryRepository storyRepo;
    private final ArtifactService artifactService;
    private final RunEventPublisher eventPublisher;

    public StoryConflictRecorder(
            StoryConflictRepository conflictRepo,
            TaskRepository taskRepo,
            StoryRepository storyRepo,
            ArtifactService artifactService,
            RunEventPublisher eventPublisher) {
        this.conflictRepo = conflictRepo;
        this.taskRepo = taskRepo;
        this.storyRepo = storyRepo;
        this.artifactService = artifactService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Idempotent on the Analyze execution: an activity retry repeats the completed-status call.
     * A storage failure reading the report throws, so the status call fails and is retried; a
     * swallowed one would leave the run parked with nothing to release it.
     */
    public void onNodeCompleted(WorkflowRun run, NodeExecution exec) {
        if (!TaskDeliveryGraph.ANALYZE.equals(exec.getLabel())
                || !TaskDeliveryGraph.STORY_CONFLICT.equals(exec.getDecision())) {
            return;
        }
        if (conflictRepo.existsByAnalyzeNodeExecutionId(exec.getId())) {
            return;
        }
        UUID storyId = run.getTaskId() == null
                ? null
                : taskRepo.findById(run.getTaskId()).map(Task::getStoryId).orElse(null);
        if (storyId == null || run.getStorySpecVersion() == null) {
            log.warn(
                    "Analyze execution {} of run {} raised a story conflict, but the run has no Task with a Story"
                            + " spec version to raise it against; its {} gate must be resumed by hand",
                    exec.getId(),
                    run.getId(),
                    TaskDeliveryGraph.AWAIT_STORY_REVISION);
            return;
        }
        StoryConflict conflict = new StoryConflict();
        conflict.setStoryId(storyId);
        conflict.setTaskRunId(run.getId());
        conflict.setAnalyzeNodeExecutionId(exec.getId());
        conflict.setRaisedAgainstVersion(run.getStorySpecVersion());
        conflict.setReport(artifactService
                .readArtifactTextInternal(exec.getArtifactRefs(), REPORT_FILE)
                .orElse(MISSING_REPORT));
        try {
            conflictRepo.save(conflict);
        } catch (DataIntegrityViolationException e) {
            if (conflictRepo.existsByAnalyzeNodeExecutionId(exec.getId())) {
                return; // a concurrent retry of the same completion inserted it first
            }
            throw e;
        }
        announce(storyId);
    }

    private void announce(UUID storyId) {
        try {
            storyRepo
                    .findById(storyId)
                    .ifPresent(story -> eventPublisher.publishRoadmapItemChanged(
                            "story", storyId, story.getStage().name()));
        } catch (RuntimeException e) {
            log.warn("Recorded a conflict on Story {} but could not announce it: {}", storyId, e.getMessage());
        }
    }
}
