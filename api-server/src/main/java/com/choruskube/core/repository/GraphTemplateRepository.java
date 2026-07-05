package com.choruskube.core.repository;

import com.choruskube.core.model.GraphTemplate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GraphTemplateRepository
        extends JpaRepository<GraphTemplate, UUID>, JpaSpecificationExecutor<GraphTemplate> {
    Optional<GraphTemplate> findByName(String name);

    Optional<GraphTemplate> findByGraphIdAndVersion(String graphId, Integer version);

    Optional<GraphTemplate> findFirstByGraphIdOrderByVersionDesc(String graphId);

    List<GraphTemplate> findAllByGraphId(String graphId);

    @Query("SELECT MAX(gt.version) FROM GraphTemplate gt WHERE gt.graphId = :graphId")
    Optional<Integer> findMaxVersionByGraphId(@Param("graphId") String graphId);

    @Query("SELECT gt FROM GraphTemplate gt WHERE gt.version = "
            + "(SELECT MAX(gt2.version) FROM GraphTemplate gt2 WHERE gt2.graphId = gt.graphId)")
    List<GraphTemplate> findLatestPerGraphId();
}
