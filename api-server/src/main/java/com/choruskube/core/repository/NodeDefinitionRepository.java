package com.choruskube.core.repository;

import com.choruskube.core.model.NodeDefinition;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NodeDefinitionRepository
        extends JpaRepository<NodeDefinition, UUID>, JpaSpecificationExecutor<NodeDefinition> {}
