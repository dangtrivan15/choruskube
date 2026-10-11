package com.choruskube.core.repository;

import com.choruskube.core.model.NodeExecutionRepoBase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NodeExecutionRepoBaseRepository extends JpaRepository<NodeExecutionRepoBase, UUID> {

    List<NodeExecutionRepoBase> findByNodeExecutionId(UUID nodeExecutionId);

    Optional<NodeExecutionRepoBase> findByNodeExecutionIdAndGitRepoId(UUID nodeExecutionId, UUID gitRepoId);
}
