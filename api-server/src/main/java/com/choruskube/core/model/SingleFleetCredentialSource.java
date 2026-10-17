package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The one declared credential mode for a shared Worker registration token, keyed by the token's
 * digest. {@link #instanceId} and {@link #declaredAt} record which Worker most recently declared
 * it and when, for operator visibility — the mode itself is the only field other code reads.
 */
@Entity
@Table(name = "single_fleet_credential_source")
public class SingleFleetCredentialSource {

    @Id
    @Column(name = "credential_digest")
    private String credentialDigest;

    @Convert(converter = WorkerCredentialSourceConverter.class)
    @Column(name = "credential_source", nullable = false)
    private WorkerCredentialSource credentialSource;

    @Column(name = "instance_id", nullable = false)
    private UUID instanceId;

    @Column(name = "declared_at", nullable = false)
    private Instant declaredAt;

    public SingleFleetCredentialSource() {}

    public String getCredentialDigest() {
        return credentialDigest;
    }

    public void setCredentialDigest(String credentialDigest) {
        this.credentialDigest = credentialDigest;
    }

    public WorkerCredentialSource getCredentialSource() {
        return credentialSource;
    }

    public void setCredentialSource(WorkerCredentialSource credentialSource) {
        this.credentialSource = credentialSource;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(UUID instanceId) {
        this.instanceId = instanceId;
    }

    public Instant getDeclaredAt() {
        return declaredAt;
    }

    public void setDeclaredAt(Instant declaredAt) {
        this.declaredAt = declaredAt;
    }
}
