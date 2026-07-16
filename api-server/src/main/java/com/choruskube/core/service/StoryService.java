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

    /** Agent/internal entry: no request-scoped TenantContext; org is asserted against {@code runId}. */
    StoryResponse create(UUID epicId, StoryRequest request, UUID runId);

    List<StoryResponse> list(UUID epicId);

    StoryResponse get(UUID id);

    StoryResponse update(UUID id, StoryRequest request);

    void delete(UUID id);
}
