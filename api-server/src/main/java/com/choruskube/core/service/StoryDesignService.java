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
import java.util.function.Function;
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
    private final GraphTemplateRepository graphTemplateRepo;
    private final RunService runService;
    private final AuthorizationService authService;
    private final StorySpecService specService;
    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final TemplateGraphIdResolver graphIdResolver;
    private final RunEventPublisher eventPublisher;
    // The locking finder returns the instance already in this persistence context, carrying state
    // read before the lock was granted; the run must be composed from the locked state.
    private final EntityManager entityManager;

    public StoryDesignService(
            StoryRepository storyRepo,
            EpicRepository epicRepo,
            TaskRepository taskRepo,
            WorkflowRunRepository runRepo,
            GraphTemplateRepository graphTemplateRepo,
            RunService runService,
            AuthorizationService authService,
            StorySpecService specService,
            UploadService uploadService,
            ArtifactService artifactService,
            TemplateGraphIdResolver graphIdResolver,
            RunEventPublisher eventPublisher,
            EntityManager entityManager) {
        this.storyRepo = storyRepo;
        this.epicRepo = epicRepo;
        this.taskRepo = taskRepo;
        this.runRepo = runRepo;
        this.graphTemplateRepo = graphTemplateRepo;
        this.runService = runService;
        this.authService = authService;
        this.specService = specService;
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.graphIdResolver = graphIdResolver;
        this.eventPublisher = eventPublisher;
        this.entityManager = entityManager;
    }

    @Transactional
    public RunResponse startDesign(UUID storyId) {
        requireStory(storyId);
        authService.checkOrgAccess("story", storyId);
        return start(storyId, null, null);
    }

    @Transactional
    public RunResponse startDesignForAutopilot(UUID storyId, UUID autopilotId) {
        requireStory(storyId);
        authService.assertSameOrg("story", storyId, "autopilot", autopilotId);
        return start(storyId, autopilotId, null);
    }

    @Transactional
    public RunResponse startRevision(UUID storyId, String guidance) {
        requireStory(storyId);
        authService.checkOrgAccess("story", storyId);
        return start(storyId, null, new Revision(List.of(), guidance));
    }

    /** System path: no caller to authorize, so off-request it must run inside the Story's org scope. */
    @Transactional
    public RunResponse startRevisionForConflicts(UUID storyId, List<StoryConflict> conflicts, UUID autopilotIdOrNull) {
        requireStory(storyId);
        return start(storyId, autopilotIdOrNull, new Revision(conflicts, null));
    }

    private record Revision(List<StoryConflict> conflicts, String guidance) {}

    private RunResponse start(UUID storyId, UUID autopilotId, Revision revision) {
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
            throw new ConflictException("Story has no approved spec to revise; design it first");
        }
        Optional<WorkflowRun> open = specService.openDesignRun(storyId);
        if (open.isPresent()) {
            WorkflowRun run = open.get();
            throw new ConflictException(
                    run.getStatus() == WorkflowRunStatus.completed
                            ? "Story's design run " + run.getId()
                                    + " is approved but its spec version is not recorded yet"
                            : "Story already has an open design run: " + run.getId());
        }

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
            inputs.put("revision_context", composeRevisionContext(spec, revision));
            stagingRefs = uploadService.stageTexts(revisionInputs(spec));
            name = "Revise spec v" + spec.getVersion() + ": " + story.getTitle();
        }

        RunResponse started = runService.startRun(new CreateRunRequest(template.getId(), inputs, name, stagingRefs));

        // Stamped on the re-fetch, not carried on CreateRunRequest: other modules call its
        // positional constructor.
        WorkflowRun run = runRepo.findById(started.id())
                .orElseThrow(() -> new NotFoundException("Workflow run not found: " + started.id()));
        run.setStoryId(storyId);
        run.setAutopilotId(autopilotId);
        runRepo.save(run);

        eventPublisher.publishRoadmapItemChanged(
                "story", storyId, story.getStage().name());
        return withAutopilotId(started, autopilotId);
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
    private String composeRevisionContext(StorySpecVersion spec, Revision revision) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Current version: v").append(spec.getVersion());
        sb.append("\n\n## Conflicts to answer");
        if (revision.conflicts().isEmpty()) {
            sb.append("\n\nNone.");
        } else {
            Map<UUID, Task> taskByRunId = tasksByRunId(revision.conflicts());
            for (StoryConflict conflict : revision.conflicts()) {
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
        sb.append("\n\n## Human guidance\n\n")
                .append(isNotBlank(revision.guidance()) ? revision.guidance().strip() : "None.");
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
                r.softwareProject());
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
