package com.choruskube.core.model;

import com.choruskube.core.model.enums.LiveChatStatus;
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
@Table(name = "live_chat_session")
public class LiveChatSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "node_execution_id", nullable = false)
    private UUID nodeExecutionId;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "source_node_execution_id")
    private UUID sourceNodeExecutionId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private LiveChatStatus status = LiveChatStatus.pending;

    @Column(columnDefinition = "TEXT")
    private String transcript;

    @Column(name = "chat_pod_name")
    private String chatPodName;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

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

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public void setWorkflowRunId(UUID workflowRunId) {
        this.workflowRunId = workflowRunId;
    }

    public UUID getSourceNodeExecutionId() {
        return sourceNodeExecutionId;
    }

    public void setSourceNodeExecutionId(UUID sourceNodeExecutionId) {
        this.sourceNodeExecutionId = sourceNodeExecutionId;
    }

    public LiveChatStatus getStatus() {
        return status;
    }

    public void setStatus(LiveChatStatus status) {
        this.status = status;
    }

    public String getTranscript() {
        return transcript;
    }

    public void setTranscript(String transcript) {
        this.transcript = transcript;
    }

    public String getChatPodName() {
        return chatPodName;
    }

    public void setChatPodName(String chatPodName) {
        this.chatPodName = chatPodName;
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
}
