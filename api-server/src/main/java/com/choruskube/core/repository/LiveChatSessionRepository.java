package com.choruskube.core.repository;

import com.choruskube.core.model.LiveChatSession;
import com.choruskube.core.model.enums.LiveChatStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveChatSessionRepository extends JpaRepository<LiveChatSession, UUID> {

    Optional<LiveChatSession> findByNodeExecutionIdAndStatusIn(UUID nodeExecutionId, List<LiveChatStatus> statuses);

    List<LiveChatSession> findByWorkflowRunIdOrderByCreatedAtDesc(UUID workflowRunId);

    List<LiveChatSession> findByNodeExecutionIdOrderByCreatedAtDesc(UUID nodeExecutionId);
}
