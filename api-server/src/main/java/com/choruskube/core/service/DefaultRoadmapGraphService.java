package com.choruskube.core.service;

import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.RepoRef;
import com.choruskube.core.dto.RoadmapGraphSnapshot;
import com.choruskube.core.dto.RunSummary;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.SoftwareProject;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.util.RepoNameUtil;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sole implementation of {@link RoadmapGraphService} (Decision 8). */
@Service
public class DefaultRoadmapGraphService implements RoadmapGraphService {

    /** Cap on embedded per-Task run history (Decision 3) — the rest is available via the
     * existing paginated {@code GET .../tasks/{id}/runs} (and its internal mirror). */
    private static final int RECENT_RUNS_LIMIT = 5;

    private final EpicService epicService;
    private final TaskService taskService;
    private final SoftwareProjectRepository softwareProjectRepo;
    // Owns dependency-edge loading, external-blocker resolution (org-checked), and the transitive
    // readiness walk (Decision 2) — shared with DefaultStoryService/DefaultTaskService's flat list
    // endpoints so all three read paths agree on exactly one "is this item blocked" answer. Also
    // supplies this Epic's raw Story/Task set (loadEpicCandidates) so this class builds its own
    // response DTOs directly from entities, rather than composing via storyService.list()/
    // taskService.list() — since Decision 1 those now each independently run a full Epic-bounded
    // readiness scan of their own, calling them here (once per Story, for tasks) would multiply
    // that scan N times over just to gather base data before this class's own single pass below.
    private final EpicReadinessAssembler readinessAssembler;

    public DefaultRoadmapGraphService(
            EpicService epicService,
            TaskService taskService,
            SoftwareProjectRepository softwareProjectRepo,
            EpicReadinessAssembler readinessAssembler) {
        this.epicService = epicService;
        this.taskService = taskService;
        this.softwareProjectRepo = softwareProjectRepo;
        this.readinessAssembler = readinessAssembler;
    }

    @Override
    @Transactional(readOnly = true)
    public RoadmapGraphSnapshot getGraph(UUID epicId) {
        // epicService.get is the same org-scoped, NotFound-throwing call DefaultEpicService's own
        // Epic-detail assembly uses; per Decision 5 a Story/Task's org is always inherited from
        // its ancestor Epic, so this one check authorizes everything loaded below it too — the
        // same trust boundary DefaultStoryService/DefaultTaskService's own list() methods already
        // rely on when they call EpicReadinessAssembler#loadEpicCandidates.
        EpicResponse epic = epicService.get(epicId);
        return assemble(epic, epicId, false, null);
    }

    @Override
    @Transactional(readOnly = true)
    public RoadmapGraphSnapshot getGraph(UUID epicId, UUID runId, UUID runSoftwareProjectId) {
        // Mirrors getGraph(UUID) above but through the *Internal variant, which validates via
        // assertSameOrg/project-match instead of checkOrgAccess (no tenant context on this path —
        // see Decision 1, Decision 5, and the javadoc on EpicService#getInternal).
        EpicResponse epic = epicService.getInternal(epicId, runId, runSoftwareProjectId);
        return assemble(epic, epicId, true, runId);
    }

    /**
     * Shared assembly for both the public and internal read paths: loads this Epic's full
     * Story/Task set once, computes dependency edges, external blockers, and per-node readiness
     * (Decision 2, delegated to {@link EpicReadinessAssembler}), and embeds per-Task capped run
     * history (Decision 3).
     *
     * @param internal whether to authorize/resolve cross-epic references via the internal
     *     ({@code assertSameOrg}) path or the public ({@code checkOrgAccess}) path
     * @param runId the calling run's id, used only when {@code internal} is true
     */
    private RoadmapGraphSnapshot assemble(EpicResponse epic, UUID epicId, boolean internal, UUID runId) {
        EpicReadinessAssembler.EpicCandidates candidates = readinessAssembler.loadEpicCandidates(epicId);
        EpicReadinessAssembler.Assembly assembly =
                readinessAssembler.assemble(candidates.candidateIds(), candidates.statusById(), internal, runId);

        List<StoryResponse> stories = candidates.stories().stream()
                .map(s -> toStoryResponse(
                        s,
                        candidates.tasksByStoryId().getOrDefault(s.getId(), List.of()),
                        assembly.readinessById().get(s.getId())))
                .toList();
        List<TaskResponse> tasks = candidates.stories().stream()
                .flatMap(s -> candidates.tasksByStoryId().getOrDefault(s.getId(), List.of()).stream())
                .map(t -> toTaskResponse(t, assembly.readinessById().get(t.getId()), internal))
                .toList();

        return new RoadmapGraphSnapshot(epic, stories, tasks, assembly.dependencies(), assembly.externalBlockers());
    }

    private static StoryResponse toStoryResponse(Story s, List<Task> tasks, Readiness readiness) {
        RollupCalculator.Rollup rollup = RollupCalculator.compute(tasks);
        return new StoryResponse(
                s.getId(),
                s.getEpicId(),
                s.getTitle(),
                s.getDescription(),
                rollup.status(),
                s.getStage().name(),
                s.getPriority().name(),
                s.getTargetDate(),
                readiness,
                new EpicResponse.Progress(rollup.totalTasks(), rollup.doneTasks()),
                s.getCreatedAt(),
                s.getUpdatedAt());
    }

    private TaskResponse toTaskResponse(Task t, Readiness readiness, boolean internal) {
        SoftwareProject project = softwareProjectRepo
                .findById(t.getSoftwareProjectId())
                .orElseThrow(() -> new NotFoundException(
                        "SoftwareProject not found for task " + t.getId() + ": " + t.getSoftwareProjectId()));
        SoftwareProjectRef projectRef = new SoftwareProjectRef(
                project.getId(), (project instanceof RepoGroup) ? "repo_group" : "git_repo", project.getName());
        List<RepoRef> repos = project.resolveRepos().stream()
                .map(g -> new RepoRef(g.getId(), g.getUrl(), RepoNameUtil.deriveRepoName(g.getUrl())))
                .toList();

        // A single newest-first page serves both the embedded recentRuns (Decision 3) and
        // latestRunId/latestRunStatus (its first element) — one query instead of the two separate
        // ones DefaultTaskService#toResponse issues for the single-item read paths, which this
        // Epic-wide pass would otherwise pay per Task.
        Page<RunSummary> page = internal
                ? taskService.listRunsInternal(t.getId(), PageRequest.of(0, RECENT_RUNS_LIMIT))
                : taskService.listRuns(t.getId(), PageRequest.of(0, RECENT_RUNS_LIMIT));
        List<RunSummary> recentRuns = page.getContent();
        UUID latestRunId = recentRuns.isEmpty() ? null : recentRuns.get(0).id();
        String latestRunStatus = recentRuns.isEmpty() ? null : recentRuns.get(0).status();

        return new TaskResponse(
                t.getId(),
                t.getStoryId(),
                t.getTitle(),
                t.getDescription(),
                t.getStatus().name(),
                projectRef,
                repos,
                latestRunId,
                latestRunStatus,
                readiness,
                recentRuns,
                page.getTotalElements(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
