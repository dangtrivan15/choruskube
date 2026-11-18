package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One run's reservation for one object key — the largest size that key was ever signed for.
 * Rows are written only through {@link com.choruskube.core.repository.ArtifactUploadBookingRepository}'s
 * native upsert; this entity exists for read access and cascades with its run (V37).
 */
@Entity
@Table(name = "artifact_upload_booking")
@IdClass(ArtifactUploadBookingId.class)
public class ArtifactUploadBooking {

    @Id
    @Column(name = "workflow_run_id", nullable = false)
    private UUID workflowRunId;

    @Id
    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "booked_bytes", nullable = false)
    private long bookedBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ArtifactUploadBooking() {}

    public UUID getWorkflowRunId() {
        return workflowRunId;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public long getBookedBytes() {
        return bookedBytes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
