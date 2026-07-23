package com.choruskube.core.service;

import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalUpdateEpicRequest;
import com.choruskube.core.model.enums.WorkItemStatus;
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

    Page<EpicResponse> list(String title, Pageable pageable);

    EpicResponse get(UUID id);

    EpicResponse update(UUID id, EpicRequest request);

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
}
