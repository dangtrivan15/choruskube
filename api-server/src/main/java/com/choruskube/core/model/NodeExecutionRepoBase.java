package com.choruskube.core.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Which default-branch commit one node execution's clone of one repo sat on. */
@Entity
@Table(name = "node_execution_repo_base")
public class NodeExecutionRepoBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "node_execution_id", nullable = false)
    private UUID nodeExecutionId;

    @Column(name = "git_repo_id", nullable = false)
    private UUID gitRepoId;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Column(name = "base_sha")
    private String baseSha;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conflicted_files", columnDefinition = "jsonb", nullable = false)
    private String conflictedFiles = "[]";

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getNodeExecutionId() {
        return nodeExecutionId;
    }

    public void setNodeExecutionId(UUID nodeExecutionId) {
        this.nodeExecutionId = nodeExecutionId;
    }

    public UUID getGitRepoId() {
        return gitRepoId;
    }

    public void setGitRepoId(UUID gitRepoId) {
        this.gitRepoId = gitRepoId;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getBaseSha() {
        return baseSha;
    }

    public void setBaseSha(String baseSha) {
        this.baseSha = baseSha;
    }

    public String getConflictedFiles() {
        return conflictedFiles;
    }

    public void setConflictedFiles(String conflictedFiles) {
        this.conflictedFiles = conflictedFiles;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }
}
