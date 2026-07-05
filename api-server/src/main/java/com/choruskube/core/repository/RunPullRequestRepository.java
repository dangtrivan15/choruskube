package com.choruskube.core.repository;

import com.choruskube.core.model.RunPullRequest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunPullRequestRepository extends JpaRepository<RunPullRequest, UUID> {

    List<RunPullRequest> findByWorkflowRunId(UUID workflowRunId);

    Optional<RunPullRequest> findByWorkflowRunIdAndPrUrl(UUID workflowRunId, String prUrl);
}
