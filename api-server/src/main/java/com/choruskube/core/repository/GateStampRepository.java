package com.choruskube.core.repository;

import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.enums.GateStampStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GateStampRepository extends JpaRepository<GateStamp, UUID> {

    Optional<GateStamp> findByIdAndStatus(UUID id, GateStampStatus status);

    Optional<GateStamp> findByIdAndStatusIn(UUID id, Collection<GateStampStatus> statuses);

    /** Holds the row until the transaction ends, so a second finalize of the same approval waits and then sees it done. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM GateStamp s WHERE s.id = :id")
    Optional<GateStamp> findLockedById(@Param("id") UUID id);

    List<GateStamp> findByWorkflowRunIdAndGateTemplateNodeIdAndStatus(
            UUID workflowRunId, UUID gateTemplateNodeId, GateStampStatus status);

    List<GateStamp> findByStampedNodeExecutionIdAndStatusIn(
            UUID stampedNodeExecutionId, Collection<GateStampStatus> statuses);

    List<GateStamp> findByWorkflowRunIdAndGateTemplateNodeIdAndStatusIn(
            UUID workflowRunId, UUID gateTemplateNodeId, Collection<GateStampStatus> statuses);

    List<GateStamp> findByWorkflowRunIdAndStatusIn(UUID workflowRunId, Collection<GateStampStatus> statuses);

    List<GateStamp> findByWorkflowRunIdOrderByCreatedAtAsc(UUID workflowRunId);
}
