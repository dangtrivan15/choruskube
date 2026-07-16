package com.choruskube.core.service;

import com.choruskube.core.dto.RunSummary;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
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

    /** Agent/internal entry: no request-scoped TenantContext; org is asserted against {@code runId}. */
    TaskResponse create(UUID storyId, TaskRequest request, UUID runId);

    List<TaskResponse> list(UUID storyId);

    TaskResponse get(UUID id);

    TaskResponse update(UUID id, TaskRequest request);

    void delete(UUID id);

    /** Starts (or restarts, once the most recent run is terminal) a workflow run for this Task. */
    TaskResponse start(UUID id);

    /** Marks the Task done, gated on its most recent run being terminal. */
    TaskResponse complete(UUID id);

    /** Full run history for this Task, newest first (Decision 1). */
    Page<RunSummary> listRuns(UUID id, Pageable pageable);
}
