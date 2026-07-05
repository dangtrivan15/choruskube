package com.choruskube.core.model;

import com.choruskube.core.model.enums.FeatureProposalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "feature_proposal")
public class FeatureProposal extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String motivation;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "feature_proposal_status")
    private FeatureProposalStatus status = FeatureProposalStatus.backlog;

    @Column(name = "software_project_id", nullable = false)
    private UUID softwareProjectId;

    @Column(name = "workflow_run_id")
    private UUID workflowRunId;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMotivation() {
        return motivation;
    }

    public void setMotivation(String motivation) {
        this.motivation = motivation;
    }

    public FeatureProposalStatus getStatus() {
        return status;
    }

    public void setStatus(FeatureProposalStatus status) {
        this.status = status;
    }

    public UUID getSoftwareProjectId() {
        return softwareProjectId;
    }

    public void setSoftwareProjectId(UUID softwareProjectId) {
        this.softwareProjectId = softwareProjectId;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(UUID workflowRunId) {
        this.workflowRunId = workflowRunId;
    }
}
