package com.choruskube.core.model;

import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.ReviewerType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "node_execution")
public class NodeExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "template_node_id", nullable = false)
    private UUID templateNodeId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private NodeExecutionStatus status = NodeExecutionStatus.pending;

    private String result;

    private String decision;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifact_refs", columnDefinition = "jsonb", nullable = false)
    private String artifactRefs = "{}";

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "traversed_edge_ids", columnDefinition = "uuid[]")
    private UUID[] traversedEdgeIds;

    @Column(name = "pod_name")
    private String podName;

    @Column(nullable = false)
    private int iteration = 1;

    @Column(name = "iteration_cap_epoch_start", nullable = false)
    private int iterationCapEpochStart = 1;

    @Column(name = "graph_version", nullable = false)
    private int graphVersion;

    @Column(name = "job_secret_hash")
    private String jobSecretHash;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "label")
    private String label;

    @Column(name = "loop_group")
    private String loopGroup;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "reviewer_type")
    private ReviewerType reviewerType;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(UUID workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public UUID getTemplateNodeId() {
        return templateNodeId;
    }

    public void setTemplateNodeId(UUID templateNodeId) {
        this.templateNodeId = templateNodeId;
    }

    public NodeExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(NodeExecutionStatus status) {
        this.status = status;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getArtifactRefs() {
        return artifactRefs;
    }

    public void setArtifactRefs(String artifactRefs) {
        this.artifactRefs = artifactRefs;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int iteration) {
        this.iteration = iteration;
    }

    public int getIterationCapEpochStart() {
        return iterationCapEpochStart;
    }

    public void setIterationCapEpochStart(int iterationCapEpochStart) {
        this.iterationCapEpochStart = iterationCapEpochStart;
    }

    public int getGraphVersion() {
        return graphVersion;
    }

    public void setGraphVersion(int graphVersion) {
        this.graphVersion = graphVersion;
    }

    public String getJobSecretHash() {
        return jobSecretHash;
    }

    public void setJobSecretHash(String jobSecretHash) {
        this.jobSecretHash = jobSecretHash;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getLoopGroup() {
        return loopGroup;
    }

    public void setLoopGroup(String loopGroup) {
        this.loopGroup = loopGroup;
    }

    public ReviewerType getReviewerType() {
        return reviewerType;
    }

    public void setReviewerType(ReviewerType reviewerType) {
        this.reviewerType = reviewerType;
    }

    public UUID[] getTraversedEdgeIds() {
        return traversedEdgeIds;
    }

    public void setTraversedEdgeIds(UUID[] traversedEdgeIds) {
        this.traversedEdgeIds = traversedEdgeIds;
    }
}
