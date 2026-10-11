package com.choruskube.core.model;

import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.enums.GateStampStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** An approval held while its run re-checks against a moved default branch. */
@Entity
@Table(name = "gate_stamp")
public class GateStamp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Column(name = "gate_template_node_id", nullable = false)
    private UUID gateTemplateNodeId;

    @Column(name = "gate_label", nullable = false)
    private String gateLabel;

    @Column(name = "stamped_node_execution_id", nullable = false)
    private UUID stampedNodeExecutionId;

    @Column(name = "feedback")
    private String feedback;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attachment_refs", columnDefinition = "jsonb")
    private String attachmentRefs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "approved_candidates", columnDefinition = "jsonb")
    private String approvedCandidates;

    @Enumerated(EnumType.STRING)
    @Column(name = "stamp_mode", nullable = false)
    private StampMode stampMode;

    @Column(name = "recheck_rounds", nullable = false)
    private int recheckRounds = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GateStampStatus status = GateStampStatus.pending;

    @Column(name = "void_reason")
    private String voidReason;

    /** The gate execution that was awaiting a decision when this stamp was voided, if it was. */
    @Column(name = "parked_node_execution_id")
    private UUID parkedNodeExecutionId;

    @Column(name = "materialized_at")
    private Instant materializedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

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

    public UUID getGateTemplateNodeId() {
        return gateTemplateNodeId;
    }

    public void setGateTemplateNodeId(UUID gateTemplateNodeId) {
        this.gateTemplateNodeId = gateTemplateNodeId;
    }

    public String getGateLabel() {
        return gateLabel;
    }

    public void setGateLabel(String gateLabel) {
        this.gateLabel = gateLabel;
    }

    public UUID getStampedNodeExecutionId() {
        return stampedNodeExecutionId;
    }

    public void setStampedNodeExecutionId(UUID stampedNodeExecutionId) {
        this.stampedNodeExecutionId = stampedNodeExecutionId;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public String getApprovedCandidates() {
        return approvedCandidates;
    }

    public void setApprovedCandidates(String approvedCandidates) {
        this.approvedCandidates = approvedCandidates;
    }

    public StampMode getStampMode() {
        return stampMode;
    }

    public void setStampMode(StampMode stampMode) {
        this.stampMode = stampMode;
    }

    public int getRecheckRounds() {
        return recheckRounds;
    }

    public void setRecheckRounds(int recheckRounds) {
        this.recheckRounds = recheckRounds;
    }

    public GateStampStatus getStatus() {
        return status;
    }

    public void setStatus(GateStampStatus status) {
        this.status = status;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public void setVoidReason(String voidReason) {
        this.voidReason = voidReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getAttachmentRefs() {
        return attachmentRefs;
    }

    public void setAttachmentRefs(String attachmentRefs) {
        this.attachmentRefs = attachmentRefs;
    }

    public UUID getParkedNodeExecutionId() {
        return parkedNodeExecutionId;
    }

    public void setParkedNodeExecutionId(UUID parkedNodeExecutionId) {
        this.parkedNodeExecutionId = parkedNodeExecutionId;
    }

    public Instant getMaterializedAt() {
        return materializedAt;
    }

    public void setMaterializedAt(Instant materializedAt) {
        this.materializedAt = materializedAt;
    }
}
