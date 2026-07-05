package com.choruskube.core.observability;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAuditSink implements AuditSink {
    @Override
    public void record(String action, String resourceType, UUID resourceId, String detail) {
        // no-op: single-tenant core does not persist audit events
    }
}
