package com.choruskube.core.model;

import com.choruskube.core.model.enums.WorkflowRunStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "workflow_run")
@SQLRestriction("deleted_at IS NULL")
public class WorkflowRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "graph_template_id", nullable = false)
    private UUID graphTemplateId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "autopilot_id")
    private UUID autopilotId;

    /**
     * When the Autopilot's failure breaker counted this run's outcome. Set once, never cleared —
     * it is what makes settling idempotent, since {@code awaiting_retry} is a durable status and
     * not an event, so a time window over "runs that finished recently" would re-count the same
     * dead run on every tick that touched it.
     */
    @Column(name = "autopilot_settled_at")
    private Instant autopilotSettledAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private WorkflowRunStatus status = WorkflowRunStatus.pending;

    @Column(name = "external_run_id")
    private String externalRunId;

    @Column(name = "graph_version", nullable = false)
    private int graphVersion = 1;

    @Column(name = "name", length = 255)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "inputs", columnDefinition = "jsonb", nullable = false)
    private String inputs = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_artifact_refs", columnDefinition = "jsonb", nullable = false)
    private String inputArtifactRefs = "{}";

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getGraphTemplateId() {
        return graphTemplateId;
    }

    public void setGraphTemplateId(UUID graphTemplateId) {
        this.graphTemplateId = graphTemplateId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getAutopilotId() {
        return autopilotId;
    }

    public void setAutopilotId(UUID autopilotId) {
        this.autopilotId = autopilotId;
    }

    public Instant getAutopilotSettledAt() {
        return autopilotSettledAt;
    }

    public void setAutopilotSettledAt(Instant autopilotSettledAt) {
        this.autopilotSettledAt = autopilotSettledAt;
    }

    public WorkflowRunStatus getStatus() {
        return status;
    }

    public void setStatus(WorkflowRunStatus status) {
        this.status = status;
    }

    public String getExternalRunId() {
        return externalRunId;
    }

    public void setExternalRunId(String externalRunId) {
        this.externalRunId = externalRunId;
    }

    public int getGraphVersion() {
        return graphVersion;
    }

    public void setGraphVersion(int graphVersion) {
        this.graphVersion = graphVersion;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getInputs() {
        return inputs;
    }

    public void setInputs(String inputs) {
        this.inputs = inputs;
    }

    public String getInputArtifactRefs() {
        return inputArtifactRefs;
    }

    public void setInputArtifactRefs(String inputArtifactRefs) {
        this.inputArtifactRefs = inputArtifactRefs;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }
}
