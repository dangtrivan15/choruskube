package com.choruskube.core.service;

import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalUpdateEpicRequest;
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
}
