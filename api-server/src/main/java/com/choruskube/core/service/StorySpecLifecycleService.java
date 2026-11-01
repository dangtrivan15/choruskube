package com.choruskube.core.service;

import com.choruskube.core.config.StoryDesignGraph;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Records each approved Story Design run's spec as its Story's next version. A run reaches {@code
 * completed} only after its approval gate has materialized the breakdown, so recording any earlier
 * would let the Story's Tasks start before the breakdown exists.
 */
@Service
public class StorySpecLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(StorySpecLifecycleService.class);

    static final String SPEC_FILE = "story_spec.md";

    private final WorkflowRunRepository runRepo;
    private final StoryRepository storyRepo;
    private final StorySpecService specService;
    private final ArtifactService artifactService;
    private final RunScopeBinder runScopeBinder;
    private final RunEventPublisher eventPublisher;

    public StorySpecLifecycleService(
            WorkflowRunRepository runRepo,
            StoryRepository storyRepo,
            StorySpecService specService,
            ArtifactService artifactService,
            RunScopeBinder runScopeBinder,
            RunEventPublisher eventPublisher) {
        this.runRepo = runRepo;
        this.storyRepo = storyRepo;
        this.specService = specService;
        this.artifactService = artifactService;
        this.runScopeBinder = runScopeBinder;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Returns how many completed, unrecorded Story Design runs it recorded, oldest first so versions
     * follow run order. Not transactional: each version commits on its own, so a run that cannot be
     * recorded never rolls back another's.
     */
    public int recordCompletedDesigns() {
        List<WorkflowRun> runs = runRepo.findCompletedDesignRunsWithoutVersion().stream()
                .sorted(Comparator.comparing(
                        WorkflowRun::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        int recorded = 0;
        for (WorkflowRun run : runs) {
            AtomicBoolean done = new AtomicBoolean();
            try {
                // The output read is org-checked, and nothing else binds an org on this thread.
                runScopeBinder.runInScopeOf(run.getId(), () -> done.set(record(run)));
            } catch (Exception e) {
                log.error(
                        "Story Design run {} for Story {} completed but its spec could not be recorded; the Story"
                                + " stays blocked until the run is deleted",
                        run.getId(),
                        run.getStoryId(),
                        e);
            }
            if (done.get()) {
                recorded++;
            }
        }
        return recorded;
    }

    /** Logs ids only: a Story's spec must never reach the logs. */
    private boolean record(WorkflowRun run) {
        UUID runId = run.getId();
        UUID storyId = run.getStoryId();
        Optional<NodeExecution> review = specService.finalReview(runId);
        if (review.isEmpty()) {
            log.error(
                    "Story Design run {} for Story {} completed with no completed {} execution, so it has no spec to"
                            + " record; the Story stays blocked until the run is deleted",
                    runId,
                    storyId,
                    StoryDesignGraph.REVIEW);
            return false;
        }
        String content;
        try {
            content = artifactService.getArtifactContent(runId, review.get().getId(), SPEC_FILE);
        } catch (NotFoundException e) {
            log.error(
                    "Story Design run {} for Story {} completed but its final {} execution has no {}; the Story"
                            + " stays blocked until the run is deleted",
                    runId,
                    storyId,
                    StoryDesignGraph.REVIEW,
                    SPEC_FILE);
            return false;
        }
        specService.recordApproved(storyId, runId, content);
        storyRepo
                .findById(storyId)
                .ifPresent(story -> eventPublisher.publishRoadmapItemChanged(
                        "story", storyId, story.getStage().name()));
        return true;
    }
}
