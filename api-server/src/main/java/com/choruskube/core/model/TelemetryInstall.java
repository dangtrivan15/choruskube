package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted, stable, anonymous install identifier for telemetry. A single row is created
 * once by {@link com.choruskube.core.service.TelemetryInstallService} and remains stable
 * across restarts. Carries no PII — see PRIVACY.md.
 */
@Entity
@Table(name = "telemetry_install")
public class TelemetryInstall {

    @Id
    @Column(name = "install_id", nullable = false, updatable = false)
    private UUID installId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public TelemetryInstall() {}

    public TelemetryInstall(UUID installId) {
        this.installId = installId;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getInstallId() {
        return installId;
    }

    public void setInstallId(UUID installId) {
        this.installId = installId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
