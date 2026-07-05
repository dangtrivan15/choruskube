package com.choruskube.core.repository;

import com.choruskube.core.model.TemplateNode;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TemplateNodeRepository extends JpaRepository<TemplateNode, UUID> {
    List<TemplateNode> findByGraphTemplateId(UUID graphTemplateId);

    boolean existsByNodeDefinitionId(UUID nodeDefinitionId);

    @Query("""
            SELECT CASE WHEN COUNT(tn) > 0 THEN true ELSE false END
            FROM TemplateNode tn
            JOIN GraphTemplate gt ON gt.id = tn.graphTemplateId
            WHERE tn.nodeDefinitionId = :nodeDefId AND gt.system = true
            """)
    boolean existsInSystemTemplate(@Param("nodeDefId") UUID nodeDefinitionId);
}
