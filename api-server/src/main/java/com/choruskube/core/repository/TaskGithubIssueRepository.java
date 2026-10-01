package com.choruskube.core.repository;

import com.choruskube.core.model.TaskGithubIssue;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskGithubIssueRepository extends JpaRepository<TaskGithubIssue, UUID> {

    Optional<TaskGithubIssue> findByTaskId(UUID taskId);
}
