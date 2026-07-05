package com.choruskube.core.repository;

import com.choruskube.core.model.ExecutionLog;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {
    List<ExecutionLog> findByNodeExecutionIdOrderByTimestampAsc(UUID nodeExecutionId);
}
