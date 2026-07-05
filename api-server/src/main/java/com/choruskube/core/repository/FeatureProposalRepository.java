package com.choruskube.core.repository;

import com.choruskube.core.model.FeatureProposal;
import com.choruskube.core.model.enums.FeatureProposalStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeatureProposalRepository
        extends JpaRepository<FeatureProposal, UUID>, JpaSpecificationExecutor<FeatureProposal> {
    List<FeatureProposal> findByStatus(FeatureProposalStatus status);

    List<FeatureProposal> findAllByWorkflowRunIdIn(Collection<UUID> runIds);

    Optional<FeatureProposal> findByWorkflowRunId(UUID workflowRunId);

    List<FeatureProposal> findAllByOrderByCreatedAtDesc();

    List<FeatureProposal> findByStatusOrderByCreatedAtDesc(FeatureProposalStatus status);

    List<FeatureProposal> findBySoftwareProjectIdOrderByCreatedAtDesc(UUID softwareProjectId);

    @Query("SELECT count(p) FROM FeatureProposal p " + "WHERE p.status <> 'rolled_out' AND p.softwareProjectId = :id")
    long countNonRolledOutBySoftwareProjectId(@Param("id") UUID id);

    // --- Analytics queries ---

    @Query(value = """
            SELECT
                fp.status::text                                            AS status,
                COUNT(*)                                                    AS count
            FROM feature_proposal fp
            GROUP BY fp.status
            ORDER BY count DESC
            """, nativeQuery = true)
    List<Object[]> getStatusCounts();

    @Query(value = """
            SELECT
                TO_CHAR(DATE_TRUNC('day', fp.updated_at), 'YYYY-MM-DD')   AS day,
                COUNT(*)                                                    AS count
            FROM feature_proposal fp
            WHERE fp.status = 'rolled_out'
              AND fp.updated_at >= :since
            GROUP BY DATE_TRUNC('day', fp.updated_at)
            ORDER BY DATE_TRUNC('day', fp.updated_at)
            """, nativeQuery = true)
    List<Object[]> getThroughput(@Param("since") Instant since);
}
