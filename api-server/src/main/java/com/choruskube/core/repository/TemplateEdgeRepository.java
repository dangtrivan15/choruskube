package com.choruskube.core.repository;

import com.choruskube.core.model.TemplateEdge;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TemplateEdgeRepository extends JpaRepository<TemplateEdge, UUID> {
    List<TemplateEdge> findByGraphTemplateId(UUID graphTemplateId);
}
