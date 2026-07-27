package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.StoryResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default {@link RoadmapCandidateMaterializer}: for each candidate Epic, creates the Epic then its
 * Stories then their Tasks via {@link InternalRunService}, wrapping each top-level candidate in its
 * own try/catch so one failure doesn't stop the rest of the batch (Decision 3, Caveat 3).
 *
 * <p>Only {@code title}/{@code description}/{@code motivation} are forwarded to {@code
 * InternalCreateEpicRequest} — {@link CandidateEpicProposal#repos()}/{@link
 * CandidateEpicProposal#priority()} have no corresponding field there and are intentionally
 * dropped (see Caveat 6: a materialized Epic's {@code repos} is always derived from its software
 * project, and the Epic model has no {@code priority} column at all).
 */
@Service
public class DefaultRoadmapCandidateMaterializer implements RoadmapCandidateMaterializer {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRoadmapCandidateMaterializer.class);

    private final InternalRunService internalRunService;

    public DefaultRoadmapCandidateMaterializer(InternalRunService internalRunService) {
        this.internalRunService = internalRunService;
    }

    @Override
    public MaterializationSummary materialize(UUID runId, List<CandidateEpicProposal> candidates) {
        List<UUID> createdEpicIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (candidates != null) {
            for (CandidateEpicProposal candidate : candidates) {
                materializeEpic(runId, candidate, createdEpicIds, errors);
            }
        }

        return new MaterializationSummary(createdEpicIds, errors);
    }

    /**
     * Creates the Epic, then best-effort creates each of its Stories/Tasks. The Epic id is recorded
     * in {@code createdEpicIds} as soon as the Epic itself is created — separately from whether its
     * nested Stories/Tasks all succeed — because each {@code create*} call is its own committed
     * transaction (Caveat 3). If a nested Story/Task failed and the Epic id were only recorded after
     * the whole tree succeeded, a partial failure would leave an already-persisted Epic (and
     * possibly some of its Stories) completely absent from the summary: the reviewer would be told
     * that candidate was "skipped" while an orphaned Epic silently exists in the database with no
     * trace of the decision that created it.
     */
    private void materializeEpic(
            UUID runId, CandidateEpicProposal candidate, List<UUID> createdEpicIds, List<String> errors) {
        EpicResponse epic;
        try {
            epic = internalRunService.createEpic(
                    runId,
                    new InternalCreateEpicRequest(
                            candidate.title(), orEmpty(candidate.description()), candidate.motivation()));
        } catch (Exception e) {
            String title = candidate != null ? candidate.title() : "<null>";
            String message = "Failed to materialize candidate Epic '" + title + "': " + e.getMessage();
            logger.warn(message, e);
            errors.add(message);
            return;
        }

        createdEpicIds.add(epic.id());

        List<CandidateStoryProposal> stories = candidate.stories();
        if (stories != null) {
            for (CandidateStoryProposal story : stories) {
                try {
                    materializeStory(runId, epic.id(), story);
                } catch (Exception e) {
                    String storyTitle = story != null ? story.title() : "<null>";
                    String message = "Failed to materialize Story '" + storyTitle + "' under candidate Epic '"
                            + candidate.title() + "': " + e.getMessage();
                    logger.warn(message, e);
                    errors.add(message);
                }
            }
        }
    }

    private void materializeStory(UUID runId, UUID epicId, CandidateStoryProposal story) {
        StoryResponse createdStory = internalRunService.createStory(
                runId, epicId, new InternalCreateStoryRequest(story.title(), orEmpty(story.description())));

        List<CandidateTaskProposal> tasks = story.tasks();
        if (tasks != null) {
            for (CandidateTaskProposal task : tasks) {
                internalRunService.createTask(
                        runId,
                        epicId,
                        createdStory.id(),
                        new InternalCreateTaskRequest(task.title(), orEmpty(task.description())));
            }
        }
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
