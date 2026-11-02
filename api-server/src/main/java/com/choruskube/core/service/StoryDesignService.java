package com.choruskube.core.service;

import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.TemplateGraphIdResolver;
import com.choruskube.core.dto.CreateRunRequest;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.StoryConflictRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starts a Story's Story Design runs: its first design, and each revision of an approved spec. */
@Service
public class StoryDesignService {

    private static final Logger log = LoggerFactory.getLogger(StoryDesignService.class);

    static final String SPEC_INPUT = "story_spec.md";
    static final String REVIEW_FILE = "story_spec_review.md";

    private final StoryRepository storyRepo;
    private final EpicRepository epicRepo;
    private final TaskRepository taskRepo;
    private final WorkflowRunRepository runRepo;
    private final StoryConflictRepository conflictRepo;
    private final GraphTemplateRepository graphTemplateRepo;
    private final RunService runService;
    private final AuthorizationService authService;
    private final StorySpecService specService;
    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final TemplateGraphIdResolver graphIdResolver;
    private final RunEventPublisher eventPublisher;
    private final EpicReadinessAssembler readinessAssembler;
    // The locking finder returns the instance already in this persistence context, carrying state
    // read before the lock was granted; the run must be composed from the locked state.
    private final EntityManager entityManager;

    public StoryDesignService(
            StoryRepository storyRepo,
            EpicRepository epicRepo,
            TaskRepository taskRepo,
            WorkflowRunRepository runRepo,
            StoryConflictRepository conflictRepo,
            GraphTemplateRepository graphTemplateRepo,
            RunService runService,
            AuthorizationService authService,
            StorySpecService specService,
            UploadService uploadService,
            ArtifactService artifactService,
            TemplateGraphIdResolver graphIdResolver,
            RunEventPublisher eventPublisher,
            EpicReadinessAssembler readinessAssembler,
            EntityManager entityManager) {
        this.storyRepo = storyRepo;
        this.epicRepo = epicRepo;
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.conflictRepo = conflictRepo;
        this.graphTemplateRepo = graphTemplateRepo;
        this.runService = runService;
        this.authService = authService;
        this.specService = specService;
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.graphIdResolver = graphIdResolver;
        this.eventPublisher = eventPublisher;
        this.readinessAssembler = readinessAssembler;
        this.entityManager = entityManager;
    }

    @Transactional
    public RunResponse startDesign(UUID storyId) {
        requireStory(storyId);
        authService.checkOrgAccess("story", storyId);
        return start(storyId, null, null, runService::startRun).orElseThrow().run();
    }

    @Transactional
    public RunResponse startDesignForAutopilot(UUID storyId, UUID autopilotId) {
        requireStory(storyId);
        authService.assertSameOrg("story", storyId, "autopilot", autopilotId);
        return start(storyId, autopilotId, null, runService::startRun, story -> requireDesignable(story, autopilotId))
                .orElseThrow()
                .run();
    }

    /**
     * Answers every open conflict raised against the current version, including those a revision
     * that ended without a version still holds: the reconciler never restarts a revision for those.
     */
    @Transactional
    public RunResponse startRevision(UUID storyId, String guidance) {
        requireStory(storyId);
        authService.checkOrgAccess("story", storyId);
        Revision revision =
                new Revision(version -> conflictRepo.findOpenRaisedAgainst(storyId, version), guidance, false);
        Started started = start(storyId, null, revision, runService::startRun).orElseThrow();
        List<UUID> ids = idsOf(started.conflicts());
        if (!ids.isEmpty()) {
            // Not count-checked: a conflict closed since the read is only answered needlessly, while
            // throwing would refuse the human's Revise and strand its staged inputs.
            conflictRepo.reassignToRevision(
                    ids, storyId, started.specVersion(), started.run().id());
        }
        return started.run();
    }

    /**
     * System path: no caller to authorize, so off-request it must run inside the Story's org scope.
     * Assigns the conflicts in the transaction that creates the run: assigned after it, a crash in
     * between leaves them unassigned under an open revision, and they are released unanswered.
     * Empty, starting nothing, when none of {@code conflicts} is still open, unassigned and raised
     * against the current version.
     */
    @Transactional
    public Optional<RunResponse> startRevisionForConflicts(
            UUID storyId, List<StoryConflict> conflicts, UUID autopilotIdOrNull) {
        requireStory(storyId);
        List<UUID> foreign = conflicts.stream()
                .filter(c -> !storyId.equals(c.getStoryId()))
                .map(StoryConflict::getId)
                .toList();
        if (!foreign.isEmpty()) {
            throw new IllegalArgumentException("Conflicts " + foreign + " do not belong to Story " + storyId);
        }
        if (conflicts.isEmpty()) {
            return Optional.empty();
        }
        List<UUID> requested = idsOf(conflicts);
        // Re-read and row-locked under the Story lock: a conflict closed or taken since the caller read
        // it would fail the assignment below, rolling this start back and stranding its staged inputs.
        Revision revision = new Revision(
                version -> conflictRepo.lockOpenUnassignedRaisedAgainst(storyId, version, requested), null, true);
        // Skips the concurrent-run cap: it counts the parked Task runs this revision unblocks, so at
        // the cap a checked start is refused on every pass until their gates time out.
        Optional<Started> started =
                start(storyId, autopilotIdOrNull, revision, runService::startRunExemptFromConcurrentQuota);
        if (started.isEmpty()) {
            return Optional.empty();
        }
        RunResponse run = started.get().run();
        List<UUID> ids = idsOf(started.get().conflicts());
        if (conflictRepo.assignToRevision(ids, storyId, run.id()) != ids.size()) {
            throw new IllegalStateException(
                    "Conflicts " + ids + " of Story " + storyId + " are no longer all open and unassigned");
        }
        return Optional.of(run);
    }

    /**
     * {@code conflictsAt} reads the conflicts to answer against the spec version read under the Story
     * lock. With {@code conflictsRequired}, a revision none qualify for starts nothing.
     */
    private record Revision(IntFunction<List<StoryConflict>> conflictsAt, String guidance, boolean conflictsRequired) {}

    private record Started(RunResponse run, int specVersion, List<StoryConflict> conflicts) {}

    private static ConflictException openRunConflict(WorkflowRun run) {
        return new ConflictException(
                run.getStatus() == WorkflowRunStatus.completed
                        ? "Story's design run " + run.getId() + " is approved but its spec version is not recorded yet"
                        : "Story already has an open design run: " + run.getId());
    }

    private Optional<Started> start(
            UUID storyId, UUID autopilotId, Revision revision, Function<CreateRunRequest, RunResponse> startRun) {
        return start(storyId, autopilotId, revision, startRun, story -> {});
    }

    /** {@code underLock} runs on the locked, refreshed Story once the open-run guards have passed. */
    private Optional<Started> start(
            UUID storyId,
            UUID autopilotId,
            Revision revision,
            Function<CreateRunRequest, RunResponse> startRun,
            Consumer<Story> underLock) {
        // Every start serialises on the Story row, so two starters (a human and the Autopilot, or
        // the conflict reconciler) cannot both pass the open-run guard below.
        Story story = storyRepo
                .findWithLockById(storyId)
                .orElseThrow(() -> new NotFoundException("Story not found: " + storyId));
        entityManager.refresh(story);

        Optional<StorySpecVersion> current = specService.current(storyId);
        if (revision == null && current.isPresent()) {
            throw new ConflictException("Story already has an approved spec (v"
                    + current.get().getVersion() + "); revise it instead of designing it again");
        }
        if (revision != null && current.isEmpty()) {
            specService.openDesignRun(storyId).ifPresent(run -> {
                throw openRunConflict(run);
            });
            throw new ConflictException("Story has no approved spec to revise; design it first");
        }
        List<StoryConflict> conflicts = List.of();
        if (revision != null) {
            conflicts = revision.conflictsAt().apply(current.get().getVersion());
            if (conflicts.isEmpty() && revision.conflictsRequired()) {
                return Optional.empty();
            }
        }
        Optional<WorkflowRun> open = specService.openDesignRun(storyId);
        if (open.isPresent()) {
            throw openRunConflict(open.get());
        }
        underLock.accept(story);

        Epic epic = epicRepo.findById(story.getEpicId())
                .orElseThrow(() -> new NotFoundException("Epic not found: " + story.getEpicId()));
        GraphTemplate template = graphTemplateRepo
                .findFirstByGraphIdOrderByVersionDesc(graphIdResolver.resolve(GraphIds.STORY_DESIGN))
                .orElseThrow(() -> new NotFoundException("Story Design template not found"));

        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("software_project_id", epic.getSoftwareProjectId().toString());
        inputs.put("story_request", composeStoryRequest(story, epic));
        String name;
        String stagingRefs = null;
        if (revision == null) {
            // Always supplied: the prompts read {run.revision_context}, and an unresolved
            // placeholder fails the node.
            inputs.put("revision_context", "");
            name = "Design: " + story.getTitle();
        } else {
            StorySpecVersion spec = current.get();
            inputs.put("revision_context", composeRevisionContext(spec, conflicts, revision.guidance()));
            stagingRefs = uploadService.stageTexts(revisionInputs(spec));
            name = "Revise spec v" + spec.getVersion() + ": " + story.getTitle();
        }

        RunResponse started;
        try {
            started = startRun.apply(new CreateRunRequest(template.getId(), inputs, name, stagingRefs));
        } catch (RuntimeException e) {
            uploadService.discardStaged(stagingRefs);
            throw e;
        }

        // Stamped on the re-fetch, not carried on CreateRunRequest: other modules call its
        // positional constructor.
        WorkflowRun run = runRepo.findById(started.id())
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + started.id()));
        run.setStoryId(storyId);
        run.setAutopilotId(autopilotId);
        runRepo.save(run);

        eventPublisher.publishRoadmapItemChanged(
                "story", storyId, story.getStage().name());
        return Optional.of(new Started(
                withAutopilotId(started, autopilotId),
                current.map(StorySpecVersion::getVersion).orElse(0),
                conflicts));
    }

    /**
     * The frontier was swept before this lock was taken. Without this re-check, a blocker or a held
     * design run added since then is ignored, and the Autopilot restarts a design a human cancelled.
     */
    private void requireDesignable(Story story, UUID autopilotId) {
        EpicReadinessAssembler.EpicCandidates candidates = readinessAssembler.loadEpicCandidates(story.getEpicId());
        EpicReadinessAssembler.Assembly assembly =
                readinessAssembler.assemble(candidates, ReadinessAuthMode.AUTOPILOT, autopilotId);
        Set<UUID> thisStory = Set.of(story.getId());
        if (!StoryDesignEligibility.isDesignable(
                story,
                candidates,
                assembly,
                specService.currentVersions(StoryDesignEligibility.versionIds(thisStory, assembly.edges())),
                specService.storiesWithOpenDesignRun(thisStory),
                specService.latestDesignRuns(thisStory))) {
            throw new ConflictException(
                    "Story is no longer eligible for an Autopilot design: its blockers, design runs or Tasks changed"
                            + " since the plan");
        }
    }

    /**
     * The Story leads, so the agent cannot mistake the Epic's background for the unit of work. Each
     * existing item carries its app link: the prompts take every id from a link's last segment.
     */
    private String composeStoryRequest(Story story, Epic epic) {
        String epicPath = "/roadmap/epics/" + epic.getId();
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(story.getTitle());
        sb.append("\n\nLink: [")
                .append(story.getTitle())
                .append("](")
                .append(epicPath)
                .append("/stories/")
                .append(story.getId())
                .append(")");
        appendParagraph(sb, story.getDescription());

        sb.append("\n\n## Parent context");
        sb.append("\n\n### Epic: ").append(epic.getTitle());
        sb.append("\n\nLink: [")
                .append(epic.getTitle())
                .append("](")
                .append(epicPath)
                .append(")");
        appendParagraph(sb, epic.getDescription());
        if (isNotBlank(epic.getMotivation())) {
            sb.append("\n\nMotivation: ").append(epic.getMotivation());
        }

        sb.append("\n\n## Existing Tasks");
        List<Task> tasks = new ArrayList<>(taskRepo.findByStoryIdOrderByCreatedAtDesc(story.getId()));
        Collections.reverse(tasks);
        if (tasks.isEmpty()) {
            sb.append("\n\nNone yet.");
        } else {
            sb.append("\n");
            for (Task task : tasks) {
                sb.append("\n- [")
                        .append(task.getTitle())
                        .append("](/tasks/")
                        .append(task.getId())
                        .append(") — status: ")
                        .append(task.getStatus().name());
                if (isNotBlank(task.getDescription())) {
                    sb.append("\n  ").append(task.getDescription().strip().replace("\n", "\n  "));
                }
            }
        }
        return sb.toString();
    }

    /** The draft prompt reads the version to write next from the first line's {@code v<N>}. */
    private String composeRevisionContext(StorySpecVersion spec, List<StoryConflict> conflicts, String guidance) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Current version: v").append(spec.getVersion());
        sb.append("\n\n## Conflicts to answer");
        if (conflicts.isEmpty()) {
            sb.append("\n\nNone.");
        } else {
            Map<UUID, Task> taskByRunId = tasksByRunId(conflicts);
            for (StoryConflict conflict : conflicts) {
                Task task = taskByRunId.get(conflict.getTaskRunId());
                String title = task == null ? "(deleted Task)" : task.getTitle();
                sb.append("\n\n### Conflict ")
                        .append(conflict.getId())
                        .append(" from Task \"")
                        .append(title)
                        .append("\" (run ")
                        .append(conflict.getTaskRunId())
                        .append(")");
                if (task != null) {
                    sb.append("\n\nTask: [")
                            .append(title)
                            .append("](/tasks/")
                            .append(task.getId())
                            .append(")");
                }
                appendParagraph(sb, conflict.getReport());
            }
        }
        sb.append("\n\n## Human guidance\n\n").append(isNotBlank(guidance) ? guidance.strip() : "None.");
        return sb.toString();
    }

    private Map<UUID, Task> tasksByRunId(List<StoryConflict> conflicts) {
        Set<UUID> runIds = conflicts.stream().map(StoryConflict::getTaskRunId).collect(Collectors.toSet());
        Map<UUID, UUID> taskIdByRunId = runRepo.findAllById(runIds).stream()
                .filter(run -> run.getTaskId() != null)
                .collect(Collectors.toMap(WorkflowRun::getId, WorkflowRun::getTaskId));
        Map<UUID, Task> taskById = taskRepo.findAllById(Set.copyOf(taskIdByRunId.values())).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));
        Map<UUID, Task> byRunId = new LinkedHashMap<>();
        taskIdByRunId.forEach((runId, taskId) -> {
            Task task = taskById.get(taskId);
            if (task != null) {
                byRunId.put(runId, task);
            }
        });
        return byRunId;
    }

    /**
     * The current spec, plus the final review behind every approved version so the revision does
     * not silently reverse what an earlier review decided. A version with no design run (or whose
     * run lost its review file) contributes no review.
     */
    private Map<String, String> revisionInputs(StorySpecVersion current) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put(SPEC_INPUT, current.getContent());
        for (StorySpecVersion version : specService.versions(current.getStoryId())) {
            if (version.getRunId() == null) {
                continue;
            }
            Optional<NodeExecution> review = specService.finalReview(version.getRunId());
            if (review.isEmpty()) {
                continue;
            }
            try {
                files.put(
                        "prior_review_v" + version.getVersion() + ".md",
                        artifactService.getArtifactContent(
                                version.getRunId(), review.get().getId(), REVIEW_FILE));
            } catch (NotFoundException e) {
                log.warn(
                        "Story {} spec v{}: run {} has no {}, so the revision runs without that review",
                        current.getStoryId(),
                        version.getVersion(),
                        version.getRunId(),
                        REVIEW_FILE);
            }
        }
        return files;
    }

    private static RunResponse withAutopilotId(RunResponse r, UUID autopilotId) {
        return new RunResponse(
                r.id(),
                r.graphTemplateId(),
                r.templateName(),
                r.name(),
                r.status(),
                r.externalRunId(),
                r.graphVersion(),
                r.graphSnapshot(),
                r.startedAt(),
                r.completedAt(),
                r.createdAt(),
                r.nodeExecutions(),
                r.pullRequests(),
                r.inputArtifactRefs(),
                r.promptText(),
                r.task(),
                autopilotId,
                r.softwareProject(),
                r.storySpecVersion());
    }

    private static List<UUID> idsOf(List<StoryConflict> conflicts) {
        return conflicts.stream().map(StoryConflict::getId).toList();
    }

    private void requireStory(UUID storyId) {
        if (!storyRepo.existsById(storyId)) {
            throw new NotFoundException("Story not found: " + storyId);
        }
    }

    private static void appendParagraph(StringBuilder sb, String text) {
        if (isNotBlank(text)) {
            sb.append("\n\n").append(text.strip());
        }
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
