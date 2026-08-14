package com.choruskube.core.service;

import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.EpicUpdateRequest;
import com.choruskube.core.dto.InternalUpdateEpicRequest;
import com.choruskube.core.model.enums.Priority;
import com.choruskube.core.model.enums.Readiness;
import com.choruskube.core.model.enums.WorkItemStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * CRUD plus rollup status/progress aggregation for Epics (Decision 2). Defined as an interface,
 * with {@link DefaultEpicService} as its sole implementation, so a future alternative
 * implementation (e.g. one delegating to an external PM tool) can be wired in later without
 * touching {@code EpicController} or {@code InternalRunService} (Decision 8).
 */
public interface EpicService {

    EpicResponse create(EpicRequest request);

    /** Agent/internal entry: no request-scoped TenantContext; org is asserted against {@code runId}. */
    EpicResponse create(EpicRequest request, UUID runId);

    /**
     * Lists Epics, optionally filtered to those with at least one {@code READY} descendant
     * Story/Task (roadmap "ready to start" filter, Decision 1/3 of that feature). {@code
     * readiness == null} preserves the pre-feature behavior exactly (no filter, DB-level
     * pagination); {@code readiness == Readiness.READY} switches to an in-memory-paginated path
     * (Decision 3) since {@code readyItemCount} is not a stored column. Every returned {@link
     * EpicResponse} carries {@code readyItemCount} regardless of whether the filter is active
     * (Decision 2). A non-null {@code priority} additionally narrows the result to Epics with that
     * priority (a plain persisted-column predicate, applied in both the DB and readiness paths).
     */
    Page<EpicResponse> list(String title, Readiness readiness, Priority priority, Pageable pageable);

    EpicResponse get(UUID id);

    EpicResponse update(UUID id, EpicUpdateRequest request);

    void delete(UUID id);

    /**
     * Lists Epics targeting the given software project. Used by the internal API so an agent
     * running against a project sees every Epic that targets that project.
     */
    List<EpicResponse> listBySoftwareProjectId(UUID softwareProjectId);

    /** Updates an Epic on behalf of an agent pod (PATCH semantics). */
    EpicResponse updateInternal(UUID epicId, UUID runSoftwareProjectId, UUID runId, InternalUpdateEpicRequest req);

    /**
     * Reads an Epic on behalf of an agent pod (Roadmap Graph View internal mirror, Decision 1).
     * Validated the same way as {@link #updateInternal}: {@code assertSameOrg} plus a direct
     * {@code runSoftwareProjectId} match against the Epic's own project — NOT {@link #get}'s
     * {@code checkOrgAccess}, which reads a request-scoped tenant context that does not exist on
     * the {@code /internal/**} JOB_SECRET path.
     */
    EpicResponse getInternal(UUID epicId, UUID runId, UUID runSoftwareProjectId);

    /**
     * Moves an Epic to a new roadmap board stage. Exempt from the "no edit once started" guard
     * that {@link #update} enforces — stage moves must succeed even after descendant Tasks have
     * started.
     */
    EpicResponse updateStage(UUID id, WorkItemStatus stage);

    /**
     * Sets an Epic's priority. Exempt from the "no edit once started" guard that {@link #update}
     * enforces — mirrors {@link #updateStage} exactly, not the full PUT edit.
     */
    EpicResponse updatePriority(UUID id, Priority priority);

    /**
     * Sets or clears (via {@code null}) an Epic's target date. Exempt from the "no edit once
     * started" guard that {@link #update} enforces — mirrors {@link #updatePriority} exactly.
     */
    EpicResponse updateTargetDate(UUID id, LocalDate targetDate);
}
