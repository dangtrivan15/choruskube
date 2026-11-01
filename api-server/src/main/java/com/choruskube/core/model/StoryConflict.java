package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** A Task run's report that the Story spec it read cannot be delivered as written. */
@Entity
@Table(name = "story_conflict")
public class StoryConflict {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "story_id", nullable = false)
    private UUID storyId;

    @Column(name = "task_run_id", nullable = false)
    private UUID taskRunId;

    @Column(name = "analyze_node_execution_id", nullable = false)
    private UUID analyzeNodeExecutionId;

    @Column(name = "raised_against_version", nullable = false)
    private int raisedAgainstVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String report;

    @Column(name = "revision_run_id")
    private UUID revisionRunId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getStoryId() {
        return storyId;
    }

    public void setStoryId(UUID storyId) {
        this.storyId = storyId;
    }

    public UUID getTaskRunId() {
        return taskRunId;
    }

    public void setTaskRunId(UUID taskRunId) {
        this.taskRunId = taskRunId;
    }

    public UUID getAnalyzeNodeExecutionId() {
        return analyzeNodeExecutionId;
    }

    public void setAnalyzeNodeExecutionId(UUID analyzeNodeExecutionId) {
        this.analyzeNodeExecutionId = analyzeNodeExecutionId;
    }

    public int getRaisedAgainstVersion() {
        return raisedAgainstVersion;
    }

    public void setRaisedAgainstVersion(int raisedAgainstVersion) {
        this.raisedAgainstVersion = raisedAgainstVersion;
    }

    public String getReport() {
        return report;
    }

    public void setReport(String report) {
        this.report = report;
    }

    public UUID getRevisionRunId() {
        return revisionRunId;
    }

    public void setRevisionRunId(UUID revisionRunId) {
        this.revisionRunId = revisionRunId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt == null ? null : createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt == null ? null : releasedAt.truncatedTo(ChronoUnit.MICROS);
    }
}
