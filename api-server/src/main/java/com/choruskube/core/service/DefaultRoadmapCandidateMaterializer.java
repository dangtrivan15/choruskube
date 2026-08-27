package com.choruskube.core.service;

import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateMilestone;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.Priority;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Default {@link RoadmapCandidateMaterializer}. Order of operations (Decision 3/4/5):
 *
 * <ol>
 *   <li>Milestones: find-or-create by name via {@link MilestoneService#findOrCreate}, mapping
 *       {@code key -> milestoneId};
 *   <li>Epics, then their Stories, then their Tasks, via {@link InternalRunService}'s existing
 *       agent-facing write path — the materializer's only injected dependency for item creation
 *       itself, wrapping each top-level candidate in its own try/catch so one failure doesn't stop
 *       the rest of the batch (Caveat 3). Each item's {@code key} (if any) is recorded in a {@code
 *       key -> (BlockableItemType, id)} map as it's created;
 *   <li>Dependency edges: each {@link CandidateDependency} resolves its {@code blocking}/{@code
 *       blocked} keys against that same map and calls {@link WorkItemDependencyService#create}
 *       DIRECTLY — not routed through {@code InternalRunService.createDependency}, whose
 *       cross-project check is structurally redundant here (candidate keys only ever resolve to
 *       items just created in this same batch, all in the run's one project by construction) — on
 *       a cycle or any other validation error the edge is skipped and recorded in {@code errors}
 *       rather than aborting the batch.
 * </ol>
 *
 * <p>{@code title}/{@code description}/{@code motivation} are forwarded to {@code
 * InternalCreateEpicRequest} as-is, and each item's free-text {@code priority} — a
 * {@code "High"}/{@code "Medium"}/{@code "Low"} triage signal, now available at Epic/Story/Task
 * level (Decision 4) — is parsed (case-insensitively, via {@link #parsePriority}) onto the
 * materialized item's initial {@link Priority}, which the reviewer can re-prioritize afterwards.
 * Anything null/blank/unrecognized falls back to {@link Priority#medium}. {@link
 * CandidateEpicProposal#repos()} still has no corresponding field on the Epic and is intentionally
 * dropped (see Caveat 6: a materialized Epic's {@code repos} is always derived from its software
 * project).
 */
@Service
public class DefaultRoadmapCandidateMaterializer implements RoadmapCandidateMaterializer {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRoadmapCandidateMaterializer.class);

    private final InternalRunService internalRunService;
    private final MilestoneService milestoneService;
    private final WorkItemDependencyService workItemDependencyService;

    public DefaultRoadmapCandidateMaterializer(
            InternalRunService internalRunService,
            MilestoneService milestoneService,
            WorkItemDependencyService workItemDependencyService) {
        this.internalRunService = internalRunService;
        this.milestoneService = milestoneService;
        this.workItemDependencyService = workItemDependencyService;
    }

    /** {@code key -> (BlockableItemType, id)} for every materialized Epic/Story/Task carrying a {@code key}. */
    private record ItemRef(BlockableItemType type, UUID id) {}

    @Override
    public MaterializationSummary materialize(UUID runId, RoadmapCandidatesDocument document) {
        List<UUID> createdEpicIds = new ArrayList<>();
        List<UUID> createdMilestoneIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (document == null) {
            return new MaterializationSummary(createdEpicIds, createdMilestoneIds, 0, errors);
        }

        Map<String, UUID> milestoneIdByKey = new HashMap<>();
        materializeMilestones(runId, document.milestones(), createdMilestoneIds, milestoneIdByKey, errors);

        Map<String, ItemRef> itemByKey = new HashMap<>();
        List<CandidateEpicProposal> epics = document.epics();
        if (epics != null) {
            for (CandidateEpicProposal candidate : epics) {
                materializeEpic(runId, candidate, createdEpicIds, errors, milestoneIdByKey, itemByKey);
            }
        }

        int createdDependencyCount = materializeDependencies(document.dependencies(), itemByKey, errors);

        return new MaterializationSummary(createdEpicIds, createdMilestoneIds, createdDependencyCount, errors);
    }

    /**
     * Best-effort per Milestone (Decision 3, extended to Milestones): resolving the run's software
     * project or an individual find-or-create call can fail without aborting the rest of the
     * batch — Epics/Stories/Tasks are still worth materializing even if every Milestone failed.
     */
    private void materializeMilestones(
            UUID runId,
            List<CandidateMilestone> milestones,
            List<UUID> createdMilestoneIds,
            Map<String, UUID> milestoneIdByKey,
            List<String> errors) {
        if (milestones == null || milestones.isEmpty()) {
            return;
        }
        UUID softwareProjectId;
        try {
            softwareProjectId = internalRunService.resolveSoftwareProjectId(runId);
        } catch (Exception e) {
            String message = "Failed to resolve software project for Milestone materialization: " + e.getMessage();
            logger.warn(message, e);
            errors.add(message);
            return;
        }
        for (CandidateMilestone candidate : milestones) {
            try {
                MilestoneResponse milestone = milestoneService.findOrCreate(
                        softwareProjectId, candidate.name(), candidate.description(), candidate.targetDate());
                createdMilestoneIds.add(milestone.id());
                if (candidate.key() != null) {
                    milestoneIdByKey.put(candidate.key(), milestone.id());
                }
            } catch (Exception e) {
                String name = candidate != null ? candidate.name() : "<null>";
                String message = "Failed to materialize candidate Milestone '" + name + "': " + e.getMessage();
                logger.warn(message, e);
                errors.add(message);
            }
        }
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
            UUID runId,
            CandidateEpicProposal candidate,
            List<UUID> createdEpicIds,
            List<String> errors,
            Map<String, UUID> milestoneIdByKey,
            Map<String, ItemRef> itemByKey) {
        EpicResponse epic;
        try {
            UUID milestoneId = candidate.milestone() != null ? milestoneIdByKey.get(candidate.milestone()) : null;
            epic = internalRunService.createEpic(
                    runId,
                    new InternalCreateEpicRequest(
                            candidate.title(),
                            orEmpty(candidate.description()),
                            candidate.motivation(),
                            parsePriority(candidate.priority()),
                            milestoneId));
        } catch (Exception e) {
            String title = candidate != null ? candidate.title() : "<null>";
            String message = "Failed to materialize candidate Epic '" + title + "': " + e.getMessage();
            logger.warn(message, e);
            errors.add(message);
            return;
        }

        createdEpicIds.add(epic.id());
        if (candidate.key() != null) {
            itemByKey.put(candidate.key(), new ItemRef(BlockableItemType.epic, epic.id()));
        }

        List<CandidateStoryProposal> stories = candidate.stories();
        if (stories != null) {
            for (CandidateStoryProposal story : stories) {
                try {
                    materializeStory(runId, epic.id(), story, itemByKey);
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

    private void materializeStory(
            UUID runId, UUID epicId, CandidateStoryProposal story, Map<String, ItemRef> itemByKey) {
        StoryResponse createdStory = internalRunService.createStory(
                runId,
                epicId,
                new InternalCreateStoryRequest(
                        story.title(), orEmpty(story.description()), parsePriority(story.priority())));
        if (story.key() != null) {
            itemByKey.put(story.key(), new ItemRef(BlockableItemType.story, createdStory.id()));
        }

        List<CandidateTaskProposal> tasks = story.tasks();
        if (tasks != null) {
            for (CandidateTaskProposal task : tasks) {
                TaskResponse createdTask = internalRunService.createTask(
                        runId,
                        epicId,
                        createdStory.id(),
                        new InternalCreateTaskRequest(
                                task.title(), orEmpty(task.description()), parsePriority(task.priority())));
                if (task.key() != null) {
                    itemByKey.put(task.key(), new ItemRef(BlockableItemType.task, createdTask.id()));
                }
            }
        }
    }

    /**
     * Resolves each {@link CandidateDependency}'s keys against the just-materialized item map and
     * creates the edge directly through {@link WorkItemDependencyService#create} (Decision 3) — a
     * key that doesn't resolve (unknown, or its item's own creation failed above) or an edge {@code
     * WorkItemDependencyService} itself rejects (cycle, duplicate, self-reference) is skipped and
     * recorded in {@code errors}; the batch continues either way.
     */
    private int materializeDependencies(
            List<CandidateDependency> dependencies, Map<String, ItemRef> itemByKey, List<String> errors) {
        if (dependencies == null) {
            return 0;
        }
        int created = 0;
        for (CandidateDependency dep : dependencies) {
            ItemRef blocking = itemByKey.get(dep.blocking());
            ItemRef blocked = itemByKey.get(dep.blocked());
            if (blocking == null || blocked == null) {
                errors.add("Skipped dependency edge referencing an item that was not materialized: '" + dep.blocking()
                        + "' -> '" + dep.blocked() + "'");
                continue;
            }
            try {
                workItemDependencyService.create(new CreateDependencyRequest(
                        blocking.type().name(), blocking.id(), blocked.type().name(), blocked.id()));
                created++;
            } catch (Exception e) {
                String message = "Failed to materialize dependency edge '" + dep.blocking() + "' -> '" + dep.blocked()
                        + "': " + e.getMessage();
                logger.warn(message, e);
                errors.add(message);
            }
        }
        return created;
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }

    /**
     * Maps a candidate item's free-text {@code priority} signal ({@code "High"}/{@code "Medium"}/
     * {@code "Low"}, case-insensitive) onto the {@link Priority} enum. Null, blank, or unrecognized
     * values fall back to {@link Priority#medium} — the same default a hand-created item gets — so a
     * missing or malformed analyzer signal never blocks materialization.
     */
    private static Priority parsePriority(String value) {
        if (value == null || value.isBlank()) {
            return Priority.medium;
        }
        try {
            // Locale.ROOT so case-folding is locale-independent: under a Turkish default locale a
            // naive toLowerCase() maps "HIGH" to "hıgh" (dotless ı), which would miss the enum and
            // silently fall back to medium.
            return Priority.valueOf(value.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognized) {
            return Priority.medium;
        }
    }
}
