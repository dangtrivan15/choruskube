package com.choruskube.core.service;

import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.StoryUpdateRequest;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * CRUD plus rollup status/progress aggregation for Stories (Decision 2). Defined as an
 * interface, with {@link DefaultStoryService} as its sole implementation (Decision 8).
 *
 * <p>Per Decision 5, a Story is never treated as top-level for ownership purposes — it always
 * inherits its organization from its immediate parent Epic, regardless of caller.
 */
public interface StoryService {

    StoryResponse create(UUID epicId, StoryRequest request);

    /**
     * Agent/internal entry: no request-scoped TenantContext; org is asserted against {@code
     * runId}, and the parent Epic must belong to {@code runSoftwareProjectId} — mirrors {@link
     * EpicService#updateInternal}'s project guard, since a same-org check alone isn't enough: an
     * org can have multiple SoftwareProjects, and a Story must not attach to an Epic outside the
     * run's own target project.
     */
    StoryResponse create(UUID epicId, StoryRequest request, UUID runId, UUID runSoftwareProjectId);

    List<StoryResponse> list(UUID epicId);

    /**
     * Global, cross-Epic Story listing for the Kanban board view — mirrors {@link
     * com.choruskube.core.service.TaskService#list(WorkItemStatus, Pageable)}'s shape:
     * {@code scopeProvider}-scoped, optionally filtered by board {@code stage}, page-returning.
     * Uses the same shared single-item mapper as {@link #get}/{@link #create} — {@code readiness}
     * stays {@code null} here (Decision 1 scopes real readiness to the per-Epic {@link #list(UUID)}
     * and the Roadmap Graph View only). A non-null {@code priority} narrows the result to Stories
     * with that priority (a plain persisted-column predicate).
     */
    Page<StoryResponse> list(WorkItemStatus stage, Priority priority, Pageable pageable);

    /**
     * Agent/internal mirror of {@link #list} for the Roadmap Graph View internal route (Decision
     * 1): validated the same way as {@link #create(UUID, StoryRequest, UUID, UUID)} —
     * {@code assertSameOrg} plus a direct project match — instead of {@link #list}'s
     * {@code checkOrgAccess}, which reads a request-scoped tenant context that does not exist on
     * the {@code /internal/**} JOB_SECRET path.
     */
    List<StoryResponse> listInternal(UUID epicId, UUID runId, UUID runSoftwareProjectId);

    StoryResponse get(UUID id);

    StoryResponse update(UUID id, StoryUpdateRequest request);

    void delete(UUID id);

    /**
     * Moves a Story to a new roadmap board stage. Exempt from the "no edit once started" guard
     * that {@link #update} enforces — stage moves must succeed even after descendant Tasks have
     * started. Mirrors {@link EpicService#updateStage} exactly.
     */
    StoryResponse updateStage(UUID id, WorkItemStatus stage);

    /**
     * Sets a Story's priority. Exempt from the "no edit once started" guard that {@link #update}
     * enforces — mirrors {@link #updateStage} / {@link EpicService#updatePriority} exactly.
     */
    StoryResponse updatePriority(UUID id, Priority priority);
}
