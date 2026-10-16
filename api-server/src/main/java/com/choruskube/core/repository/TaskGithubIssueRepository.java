package com.choruskube.core.repository;

import com.choruskube.core.model.TaskGithubIssue;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskGithubIssueRepository extends JpaRepository<TaskGithubIssue, UUID> {

    Optional<TaskGithubIssue> findByTaskId(UUID taskId);

    /** @return 1 if the open linkage with this issue number was closed, 0 otherwise */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value = "UPDATE task_github_issue SET state = 'closed', closed_at = :closedAt, updated_at = :closedAt"
                    + " WHERE task_id = :taskId AND issue_number = :issueNumber AND state = 'open'",
            nativeQuery = true)
    int markClosed(
            @Param("taskId") UUID taskId, @Param("issueNumber") int issueNumber, @Param("closedAt") Instant closedAt);
}
