package com.choruskube.core.repository;

import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GateStampRepository extends JpaRepository<GateStamp, UUID> {

    Optional<GateStamp> findByIdAndStatus(UUID id, GateStampStatus status);

    List<GateStamp> findByWorkflowRunIdAndGateTemplateNodeIdAndStatus(
            UUID workflowRunId, UUID gateTemplateNodeId, GateStampStatus status);

    List<GateStamp> findByWorkflowRunIdOrderByCreatedAtAsc(UUID workflowRunId);

    @Query("""
            SELECT s FROM GateStamp s
            WHERE s.status = :pending
              AND EXISTS (SELECT e.id FROM NodeExecution e
                          WHERE e.workflowRunId = s.workflowRunId
                            AND e.templateNodeId = s.gateTemplateNodeId
                            AND e.status = :awaiting)
            """)
    List<GateStamp> findPendingWithGateIn(GateStampStatus pending, NodeExecutionStatus awaiting);

    @Query("""
            SELECT s FROM GateStamp s, WorkflowRun r
            WHERE s.workflowRunId = r.id AND s.status = :pending AND r.status IN :ended
            """)
    List<GateStamp> findPendingOnRunsIn(GateStampStatus pending, Collection<WorkflowRunStatus> ended);
}
