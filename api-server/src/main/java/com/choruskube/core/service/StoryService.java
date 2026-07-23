package com.choruskube.core.service;

import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import java.util.List;
import java.util.UUID;

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
     * Agent/internal mirror of {@link #list} for the Roadmap Graph View internal route (Decision
     * 1): validated the same way as {@link #create(UUID, StoryRequest, UUID, UUID)} —
     * {@code assertSameOrg} plus a direct project match — instead of {@link #list}'s
     * {@code checkOrgAccess}, which reads a request-scoped tenant context that does not exist on
     * the {@code /internal/**} JOB_SECRET path.
     */
    List<StoryResponse> listInternal(UUID epicId, UUID runId, UUID runSoftwareProjectId);

    StoryResponse get(UUID id);

    StoryResponse update(UUID id, StoryRequest request);

    void delete(UUID id);
}
