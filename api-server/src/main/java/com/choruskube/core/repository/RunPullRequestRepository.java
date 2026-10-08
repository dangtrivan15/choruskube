package com.choruskube.core.repository;

import com.choruskube.core.model.RunPullRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RunPullRequestRepository extends JpaRepository<RunPullRequest, UUID> {

    List<RunPullRequest> findByWorkflowRunId(UUID workflowRunId);

    Optional<RunPullRequest> findByWorkflowRunIdAndPrUrl(UUID workflowRunId, String prUrl);

    /**
     * Unmerged PRs on this Autopilot's own runs that carry no PR number, and so can never be
     * merged on approval. Counted rather than listed: the panel needs to say that some work can
     * never complete, not which rows.
     *
     * <p>Scoped through {@code workflow_run.autopilot_id} because the tick runs on a timer thread
     * with no request scope — the same reason every other Autopilot query derives scope from an id
     * the caller already holds.
     */
    @Query("""
            SELECT COUNT(p) FROM RunPullRequest p, WorkflowRun r
            WHERE p.workflowRunId = r.id AND r.autopilotId = :autopilotId
              AND p.mergedAt IS NULL AND p.prNumber IS NULL
            """)
    long countUnresolvableForAutopilot(UUID autopilotId);
}
