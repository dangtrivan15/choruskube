package com.choruskube.core.service;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateMilestone;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateDependencyRequest;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.MaterializationSummary;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Default {@link RoadmapCandidateMaterializer}. Order of operations:
 *
 * <ol>
 *   <li>Milestones: find-or-create by name via {@link MilestoneService#findOrCreate}, mapping
 *       {@code key -> milestoneId};
 *   <li>Epics, then their Stories, then their Tasks, via the {@link RoadmapItemWriter} — {@link
 *       InternalRunService}'s agent-facing write path for a gate, the public services for an
 *       import — wrapping each top-level candidate in its own try/catch so one failure doesn't stop
 *       the rest of the batch. An {@code existingId} anchor is never created: its key (if any) is
 *       recorded directly against the anchor's id, and its children (if any) attach under it.
 *       Each created item's {@code key} (if any) is recorded in a {@code key -> (BlockableItemType,
 *       id)} map as it's created;
 *   <li>In {@code roadmap_extension} mode, right after each new Task is created, a matching GitHub
 *       issue is filed and the linkage persisted — best-effort: a failure here is recorded in
 *       {@code errors} but never un-creates the Task;
 *   <li>Dependency edges: each {@link CandidateDependency} resolves its {@code blocking}/{@code
 *       blocked} keys against the same map and calls the writer's {@code createDependency} — a
 *       project/org-checked path, needed because a key may resolve to an anchor (an item outside
 *       this batch) rather than only to something just created in it — on a cycle or any other
 *       validation error the edge is skipped and recorded in {@code errors} rather than aborting
 *       the batch.
 * </ol>
 *
 * <p>Best-effort applies per call, not per caller: a caller holding one transaction around the
 * whole materialization (an import) sees every error in the summary and rolls back all of it.
 *
 * <p>{@code title}/{@code description}/{@code motivation} are forwarded to {@code
 * InternalCreateEpicRequest} as-is, and each item's free-text {@code priority} — a
 * {@code "High"}/{@code "Medium"}/{@code "Low"} triage signal, now available at Epic/Story/Task
 * level — is parsed (case-insensitively, via {@link #parsePriority}) onto the
 * materialized item's initial {@link Priority}, which the reviewer can re-prioritize afterwards.
 * Anything null/blank/unrecognized falls back to {@link Priority#medium}. {@link
 * CandidateEpicProposal#repos()} still has no corresponding field on the Epic and is intentionally
 * dropped (a materialized Epic's {@code repos} is always derived from its software
 * project).
 */
@Service
public class DefaultRoadmapCandidateMaterializer implements RoadmapCandidateMaterializer {

    private static final Logger logger = LoggerFactory.getLogger(DefaultRoadmapCandidateMaterializer.class);

    private final InternalRunService internalRunService;
    private final MilestoneService milestoneService;
    private final GitHubAppService gitHubAppService;
    private final GitHubCredentialResolver gitHubCredentialResolver;
    private final TaskGithubIssueRepository taskGithubIssueRepository;

    public DefaultRoadmapCandidateMaterializer(
            InternalRunService internalRunService,
            MilestoneService milestoneService,
            GitHubAppService gitHubAppService,
            GitHubCredentialResolver gitHubCredentialResolver,
            TaskGithubIssueRepository taskGithubIssueRepository) {
        this.internalRunService = internalRunService;
        this.milestoneService = milestoneService;
        this.gitHubAppService = gitHubAppService;
        this.gitHubCredentialResolver = gitHubCredentialResolver;
        this.taskGithubIssueRepository = taskGithubIssueRepository;
    }

    /** {@code key -> (BlockableItemType, id)} for every candidate item (anchor or created) carrying a {@code key}. */
    private record ItemRef(BlockableItemType type, UUID id) {}

    @Override
    public MaterializationSummary materialize(
            UUID runId, RoadmapCandidatesDocument document, RoadmapMaterializeMode mode) {
        return materialize(
                new RunWriter(internalRunService, runId), document, mode == RoadmapMaterializeMode.roadmap_extension);
    }

    @Override
    public MaterializationSummary materialize(RoadmapItemWriter writer, RoadmapCandidatesDocument document) {
        return materialize(writer, document, false);
    }

    /** The gate path: JOB_SECRET-safe writes, each scoped to the run's own project and org. */
    private record RunWriter(InternalRunService runs, UUID runId) implements RoadmapItemWriter {

        @Override
        public UUID softwareProjectId() {
            return runs.resolveSoftwareProjectId(runId);
        }

        @Override
        public EpicResponse createEpic(InternalCreateEpicRequest request) {
            return runs.createEpic(runId, request);
        }

        @Override
        public StoryResponse createStory(UUID epicId, InternalCreateStoryRequest request) {
            return runs.createStory(runId, epicId, request);
        }

        @Override
        public TaskResponse createTask(UUID epicId, UUID storyId, InternalCreateTaskRequest request) {
            return runs.createTask(runId, epicId, storyId, request);
        }

        @Override
        public void createDependency(InternalCreateDependencyRequest request) {
            runs.createDependency(runId, request);
        }
    }

    private MaterializationSummary materialize(
            RoadmapItemWriter writer, RoadmapCandidatesDocument document, boolean fileGithubIssues) {
        List<UUID> createdEpicIds = new ArrayList<>();
        List<UUID> createdStoryIds = new ArrayList<>();
        List<UUID> createdTaskIds = new ArrayList<>();
        List<UUID> createdMilestoneIds = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        if (document == null) {
            return new MaterializationSummary(
                    createdEpicIds, createdStoryIds, createdTaskIds, createdMilestoneIds, 0, errors);
        }

        Map<String, UUID> milestoneIdByKey = new HashMap<>();
        materializeMilestones(writer, document.milestones(), createdMilestoneIds, milestoneIdByKey, errors);

        Map<String, ItemRef> itemByKey = new HashMap<>();
        List<CandidateEpicProposal> epics = document.epics();
        if (epics != null) {
            for (CandidateEpicProposal candidate : epics) {
                materializeEpic(
                        writer,
                        candidate,
                        fileGithubIssues,
                        createdEpicIds,
                        createdStoryIds,
                        createdTaskIds,
                        errors,
                        milestoneIdByKey,
                        itemByKey);
            }
        }

        int createdDependencyCount = materializeDependencies(writer, document.dependencies(), itemByKey, errors);

        return new MaterializationSummary(
                createdEpicIds, createdStoryIds, createdTaskIds, createdMilestoneIds, createdDependencyCount, errors);
    }

    /**
     * Best-effort per Milestone (same best-effort semantics as Epics/Stories/Tasks): resolving the run's software
     * project or an individual find-or-create call can fail without aborting the rest of the
     * batch — Epics/Stories/Tasks are still worth materializing even if every Milestone failed.
     */
    private void materializeMilestones(
            RoadmapItemWriter writer,
            List<CandidateMilestone> milestones,
            List<UUID> createdMilestoneIds,
            Map<String, UUID> milestoneIdByKey,
            List<String> errors) {
        if (milestones == null || milestones.isEmpty()) {
            return;
        }
        UUID softwareProjectId;
        try {
            softwareProjectId = writer.softwareProjectId();
        } catch (Exception e) {
            String message = "Failed to resolve software project for Milestone materialization: " + e.getMessage();
            logger.warn(message, e);
            errors.add(message);
            return;
        }
        for (CandidateMilestone candidate : milestones) {
            MilestoneResponse milestone = findOrCreateMilestoneWithRetry(softwareProjectId, candidate, errors);
            if (milestone != null) {
                createdMilestoneIds.add(milestone.id());
                if (candidate.key() != null) {
                    milestoneIdByKey.put(candidate.key(), milestone.id());
                }
            }
        }
    }

    /**
     * {@link MilestoneService#findOrCreate}'s find-then-save isn't atomic, so two concurrent gate
     * approvals materializing the same software project can both miss the find and race the save;
     * the loser gets a {@link DataIntegrityViolationException} from the unique-name index. Each
     * call here is its own transaction (this class has no {@code @Transactional} of its own), so a
     * retry runs fresh and finds the winner's now-committed row instead of this Epic silently
     * losing its Milestone association.
     */
    private MilestoneResponse findOrCreateMilestoneWithRetry(
            UUID softwareProjectId, CandidateMilestone candidate, List<String> errors) {
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                return milestoneService.findOrCreate(
                        softwareProjectId, candidate.name(), candidate.description(), candidate.targetDate());
            } catch (DataIntegrityViolationException raceLoss) {
                if (attempt == 2) {
                    recordMilestoneError(candidate, raceLoss, errors);
                }
            } catch (Exception e) {
                recordMilestoneError(candidate, e, errors);
                return null;
            }
        }
        return null;
    }

    private void recordMilestoneError(CandidateMilestone candidate, Exception e, List<String> errors) {
        String name = candidate != null ? candidate.name() : "<null>";
        String message = "Failed to materialize candidate Milestone '" + name + "': " + e.getMessage();
        logger.warn(message, e);
        errors.add(message);
    }

    /**
     * Resolves (anchor) or creates (new) the Epic, then best-effort processes each of its
     * Stories/Tasks. A newly-created Epic's id is recorded in {@code createdEpicIds} as soon as the
     * Epic itself is created — separately from whether its nested Stories/Tasks all succeed —
     * because each {@code create*} call is its own committed transaction. If a nested Story/Task
     * failed and the Epic id were only recorded after the whole tree succeeded, a partial failure
     * would leave an already-persisted Epic (and possibly some of its Stories) completely absent
     * from the summary: the reviewer would be told that candidate was "skipped" while an orphaned
     * Epic silently exists in the database with no trace of the decision that created it.
     */
    private void materializeEpic(
            RoadmapItemWriter writer,
            CandidateEpicProposal candidate,
            boolean fileGithubIssues,
            List<UUID> createdEpicIds,
            List<UUID> createdStoryIds,
            List<UUID> createdTaskIds,
            List<String> errors,
            Map<String, UUID> milestoneIdByKey,
            Map<String, ItemRef> itemByKey) {
        if (candidate == null) {
            // Bean Validation's @Valid cascade skips (does not reject) a null element inside a
            // list, so a document like {"epics":[null,{...}]} reaches here un-guarded.
            String message = "Failed to materialize candidate Epic '<null>': entry is null";
            logger.warn(message);
            errors.add(message);
            return;
        }
        UUID epicId;
        if (candidate.existingId() != null) {
            epicId = candidate.existingId();
        } else {
            EpicResponse epic;
            try {
                UUID milestoneId = candidate.milestone() != null ? milestoneIdByKey.get(candidate.milestone()) : null;
                epic = writer.createEpic(new InternalCreateEpicRequest(
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
            epicId = epic.id();
            createdEpicIds.add(epicId);
        }
        if (candidate.key() != null) {
            itemByKey.put(candidate.key(), new ItemRef(BlockableItemType.epic, epicId));
        }

        List<CandidateStoryProposal> stories = candidate.stories();
        if (stories != null) {
            for (CandidateStoryProposal story : stories) {
                try {
                    materializeStory(
                            writer,
                            epicId,
                            story,
                            fileGithubIssues,
                            createdStoryIds,
                            createdTaskIds,
                            errors,
                            itemByKey);
                } catch (Exception e) {
                    String storyTitle = story != null ? story.title() : "<null>";
                    String epicLabel = candidate.title() != null ? candidate.title() : String.valueOf(epicId);
                    String message = "Failed to materialize Story '" + storyTitle + "' under candidate Epic '"
                            + epicLabel + "': " + e.getMessage();
                    logger.warn(message, e);
                    errors.add(message);
                }
            }
        }
    }

    private void materializeStory(
            RoadmapItemWriter writer,
            UUID epicId,
            CandidateStoryProposal story,
            boolean fileGithubIssues,
            List<UUID> createdStoryIds,
            List<UUID> createdTaskIds,
            List<String> errors,
            Map<String, ItemRef> itemByKey) {
        UUID storyId;
        if (story.existingId() != null) {
            storyId = story.existingId();
        } else {
            StoryResponse createdStory = writer.createStory(
                    epicId,
                    new InternalCreateStoryRequest(
                            story.title(), orEmpty(story.description()), parsePriority(story.priority())));
            storyId = createdStory.id();
            createdStoryIds.add(storyId);
        }
        if (story.key() != null) {
            itemByKey.put(story.key(), new ItemRef(BlockableItemType.story, storyId));
        }

        List<CandidateTaskProposal> tasks = story.tasks();
        if (tasks != null) {
            for (CandidateTaskProposal task : tasks) {
                materializeTask(writer, epicId, storyId, task, fileGithubIssues, createdTaskIds, errors, itemByKey);
            }
        }
    }

    private void materializeTask(
            RoadmapItemWriter writer,
            UUID epicId,
            UUID storyId,
            CandidateTaskProposal task,
            boolean fileGithubIssues,
            List<UUID> createdTaskIds,
            List<String> errors,
            Map<String, ItemRef> itemByKey) {
        if (task.existingId() != null) {
            if (task.key() != null) {
                itemByKey.put(task.key(), new ItemRef(BlockableItemType.task, task.existingId()));
            }
            return;
        }
        TaskResponse createdTask = writer.createTask(
                epicId,
                storyId,
                new InternalCreateTaskRequest(
                        task.title(), orEmpty(task.description()), parsePriority(task.priority())));
        createdTaskIds.add(createdTask.id());
        if (task.key() != null) {
            itemByKey.put(task.key(), new ItemRef(BlockableItemType.task, createdTask.id()));
        }
        if (fileGithubIssues) {
            fileGithubIssue(writer, createdTask, task.repoId(), errors);
        }
    }

    /**
     * Files a matching GitHub issue for a newly-created Task and persists the linkage — best-effort,
     * same as every other per-item step here: a failure is recorded in {@code errors} and the Task
     * itself, already created and committed, stays created.
     */
    private void fileGithubIssue(
            RoadmapItemWriter writer, TaskResponse createdTask, UUID candidateRepoId, List<String> errors) {
        try {
            UUID projectId = writer.softwareProjectId();
            List<GitRepo> repos = internalRunService.resolveRepos(projectId);
            GitRepo repo = resolveRepoForTask(repos, candidateRepoId);
            String ownerRepo = RepoNameUtil.deriveOwnerRepoName(repo.getUrl());
            String token = gitHubCredentialResolver.getTokenForRepo(repo.getId());
            GitHubAppService.CreatedIssue issue =
                    gitHubAppService.createIssue(token, ownerRepo, createdTask.title(), createdTask.description());

            TaskGithubIssue linkage = new TaskGithubIssue();
            linkage.setTaskId(createdTask.id());
            linkage.setGitRepoId(repo.getId());
            linkage.setIssueNumber(issue.number());
            linkage.setIssueUrl(issue.htmlUrl());
            linkage.setState(GithubIssueState.open);
            taskGithubIssueRepository.save(linkage);
        } catch (Exception e) {
            String message = "Failed to file GitHub issue for Task '" + createdTask.title() + "': " + e.getMessage();
            logger.warn(message, e);
            errors.add(message);
        }
    }

    private GitRepo resolveRepoForTask(List<GitRepo> repos, UUID candidateRepoId) {
        if (candidateRepoId != null) {
            return repos.stream()
                    .filter(r -> r.getId().equals(candidateRepoId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "repoId " + candidateRepoId + " is not part of this run's software project"));
        }
        if (repos.size() == 1) {
            return repos.get(0);
        }
        throw new IllegalStateException("Cannot determine repo for GitHub issue: project has " + repos.size()
                + " repos and no repoId was given");
    }

    /**
     * Resolves each {@link CandidateDependency}'s keys against the just-materialized item map and
     * creates the edge through {@link InternalRunService#createDependency} — the project-checked
     * agent path, needed because a key may resolve to an anchor (an item outside this batch) rather
     * than only to something just created in it. A key that doesn't resolve (unknown, or its item's
     * own creation failed above) or an edge the write path itself rejects (cycle, duplicate,
     * self-reference, cross-project) is skipped and recorded in {@code errors}; the batch continues
     * either way.
     */
    private int materializeDependencies(
            RoadmapItemWriter writer,
            List<CandidateDependency> dependencies,
            Map<String, ItemRef> itemByKey,
            List<String> errors) {
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
                writer.createDependency(new InternalCreateDependencyRequest(
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
