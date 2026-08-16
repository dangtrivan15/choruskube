package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.DynamicUpdate;

/**
 * The Autopilot: a standing controller that starts READY Tasks unattended (Decision 1). One row
 * per installation (Decision 7); absence of the row means "never configured" rather than
 * "disengaged", which the service turns into a synthetic status on read.
 *
 * <p>Deliberately not a {@code BaseEntity}: this is control-plane configuration, not org-owned
 * work, and it carries its own timestamps rather than auditing metadata.
 */
@Entity
@Table(name = "autopilot")
// Load-bearing, not an optimisation. The emergency stop is a targeted UPDATE that deliberately
// does NOT take the tick's advisory lock, so a tick can be mid-pass when it lands. What keeps the
// stop from being undone is precisely this: the tick's write-back carries only the columns it
// actually changed — its counters and last_tick_at — so `engaged` is absent from the statement
// unless the tick set it itself, and the only thing that does is the failure breaker, which
// writes false too. Remove this and a full-column UPDATE restores engaged = true after a human
// has hit Disengage. AutopilotServiceIntegrationTest pins that with a deterministic interleaving.
@DynamicUpdate
public class Autopilot {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(nullable = false)
    private boolean engaged = false;

    @Column(name = "max_parallel", nullable = false)
    private int maxParallel = 1;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "disengaged_reason")
    private String disengagedReason;

    @Column(name = "last_tick_at")
    private Instant lastTickAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public boolean isEngaged() {
        return engaged;
    }

    public void setEngaged(boolean engaged) {
        this.engaged = engaged;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public String getDisengagedReason() {
        return disengagedReason;
    }

    public void setDisengagedReason(String disengagedReason) {
        this.disengagedReason = disengagedReason;
    }

    public Instant getLastTickAt() {
        return lastTickAt;
    }

    public void setLastTickAt(Instant lastTickAt) {
        this.lastTickAt = lastTickAt;
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
}
