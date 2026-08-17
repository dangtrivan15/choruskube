package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
// An optimisation and nothing more. It used to be load-bearing: while the tick wrote this row
// back through a managed entity, narrowing that UPDATE to the changed columns was the only thing
// stopping it from restoring `engaged = true` over a human's Disengage. Every write is now a
// single-statement UPDATE in AutopilotRepository, so there is no write-back left to narrow and no
// correctness claim resting on this annotation — it is kept because emitting fewer columns is
// still cheaper on the reads-plus-statements path this entity now has.
@DynamicUpdate
public class Autopilot {

    // No identifier generator and no @PrePersist/@PreUpdate, because nothing persists or merges
    // this entity: the row is INSERTed by AutopilotRepository#insertDefaults with a caller-chosen
    // id, and created_at/updated_at are maintained by the statements that write them. Callbacks
    // left here would claim JPA still maintains those columns, and would never fire.
    @Id
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

    /**
     * Which api-server instance owns the pass currently running, and until when. Read-only from
     * here — {@link com.choruskube.core.repository.AutopilotRepository#acquireTickLease} and its
     * two siblings are the only things that move them, and each does so in one conditional
     * statement so that "take the lease" and "check nobody else has it" cannot come apart.
     */
    @Column(name = "tick_owner")
    private String tickOwner;

    @Column(name = "tick_lease_until")
    private Instant tickLeaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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

    public String getTickOwner() {
        return tickOwner;
    }

    public Instant getTickLeaseUntil() {
        return tickLeaseUntil;
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
