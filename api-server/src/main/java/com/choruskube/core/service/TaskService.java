package com.choruskube.core.service;

import com.choruskube.core.dto.RunSummary;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * CRUD plus run lifecycle (start/complete/history) for Tasks — the only startable leaf of the
 * work hierarchy (Decision 1, Decision 2). Defined as an interface, with
 * {@link DefaultTaskService} as its sole implementation (Decision 8).
 *
 * <p>Per Decision 5, a Task is never treated as top-level for ownership purposes — it always
 * inherits its organization from its immediate parent Story, regardless of caller.
 */
public interface TaskService {

    TaskResponse create(UUID storyId, TaskRequest request);

    /**
     * Agent/internal entry: no request-scoped TenantContext; org is asserted against {@code
     * runId}, and the ancestor Epic (via the Story's parent) must belong to {@code
     * runSoftwareProjectId} — mirrors {@link EpicService#updateInternal}'s project guard, since a
     * same-org check alone isn't enough: an org can have multiple SoftwareProjects, and a Task
     * must not attach to a Story outside the run's own target project.
     */
    TaskResponse create(UUID storyId, TaskRequest request, UUID runId, UUID runSoftwareProjectId);

    List<TaskResponse> list(UUID storyId);

    /**
     * Global, cross-Story Task listing for the Kanban board view — Backlog/In Progress/Done
     * columns map directly onto {@code status}, so no separate board "stage" field exists (unlike
     * {@link EpicService#updateStage}). Mirrors {@link EpicService#list(String, Pageable)}'s
     * shape: {@code scopeProvider}-scoped, optionally filtered, page-returning. Uses the same
     * shared single-item mapper as {@link #get}/{@link #create} — {@code readiness} stays {@code
     * null} here (Decision 1 scopes real readiness to the per-Story {@link #list(UUID)} and the
     * Roadmap Graph View only).
     */
    Page<TaskResponse> list(WorkItemStatus status, Pageable pageable);

    /**
     * Agent/internal mirror of {@link #list} for the Roadmap Graph View internal route (Decision
     * 1): validated the same way as {@link #create(UUID, TaskRequest, UUID, UUID)} —
     * {@code assertSameOrg} plus a direct project match — instead of {@link #list}'s
     * {@code checkOrgAccess}, which reads a request-scoped tenant context that does not exist on
     * the {@code /internal/**} JOB_SECRET path.
     */
    List<TaskResponse> listInternal(UUID storyId, UUID runId, UUID runSoftwareProjectId);

    TaskResponse get(UUID id);

    TaskResponse update(UUID id, TaskRequest request);

    void delete(UUID id);

    /** Starts (or restarts, once the most recent run is terminal) a workflow run for this Task. */
    TaskResponse start(UUID id);

    /** Marks the Task done, gated on its most recent run being terminal. */
    TaskResponse complete(UUID id);

    /**
     * Validated-transition status write (Decision 4) covering both success and failure outcomes
     * for a request-scoped (checkOrgAccess-gated) caller: {@code backlog→in_progress} (delegates
     * to {@link #start}), {@code in_progress→done} (delegates to {@link #complete}, after
     * optionally verifying {@code runId} matches the Task's most recent linked run), and the new
     * {@code in_progress→backlog} "reopen" transition (requires the most recent run to be
     * terminal, mirroring {@link #complete}'s gate). Any other (current, target) pair throws
     * {@link com.choruskube.core.exception.InvalidStatusTransitionException}. {@code note} is
     * recorded on the audit trail (not persisted as a column).
     */
    TaskResponse updateStatus(UUID id, WorkItemStatus target, UUID runId, String note);

    /**
     * Internal/agent mirror of {@link #updateStatus} for the {@code /internal/**} JOB_SECRET path
     * (Decision 1, Decision 5): validated via {@code assertSameOrg}/project-match against
     * {@code callingRunId}/{@code runSoftwareProjectId} instead of {@code checkOrgAccess}. Scoped
     * to just {@code in_progress→done} and {@code in_progress→backlog} — an agent reports the
     * outcome of a run it is already inside; it does not self-initiate a Task via this endpoint
     * (that remains {@link #start}'s public-path-only contract, since starting creates a brand new
     * workflow run). {@code outcomeRunId} is the optional run being reported on (defaults to
     * whichever run is actually most recent when omitted); {@code note} is recorded on the audit
     * trail.
     */
    TaskResponse updateStatusInternal(
            UUID id,
            WorkItemStatus target,
            UUID callingRunId,
            UUID runSoftwareProjectId,
            UUID outcomeRunId,
            String note);

    /** Full run history for this Task, newest first (Decision 1). */
    Page<RunSummary> listRuns(UUID id, Pageable pageable);

    /**
     * Internal/agent mirror of {@link #listRuns}, used to embed capped run history in the
     * Roadmap Graph View internal response (Decision 3). Skips {@code checkOrgAccess} — the
     * caller (RoadmapGraphService's internal path) has already validated ownership of the whole
     * Epic/Story/Task tree via {@code assertSameOrg}/project-match before reaching this method, so
     * a second, request-scoped check here would be both redundant and unsafe (no tenant context
     * exists on this thread to check against).
     */
    Page<RunSummary> listRunsInternal(UUID id, Pageable pageable);
}
