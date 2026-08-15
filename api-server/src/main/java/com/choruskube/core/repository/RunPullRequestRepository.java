package com.choruskube.core.repository;

import com.choruskube.core.model.RunPullRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RunPullRequestRepository extends JpaRepository<RunPullRequest, UUID> {

    List<RunPullRequest> findByWorkflowRunId(UUID workflowRunId);

    Optional<RunPullRequest> findByWorkflowRunIdAndPrUrl(UUID workflowRunId, String prUrl);

    /**
     * Unmerged PRs, least-recently-checked first, for the state reconciler's batch. Never-checked
     * rows sort first so a freshly registered PR is picked up on the next tick. Uses
     * {@link Pageable} for the limit rather than the native {@code LIMIT} that
     * {@code findTombstonedBatch} uses — this query needs no native features.
     */
    @Query("SELECT p FROM RunPullRequest p WHERE p.mergedAt IS NULL ORDER BY p.stateCheckedAt ASC NULLS FIRST")
    List<RunPullRequest> findUnmergedBatch(Pageable pageable);
}
