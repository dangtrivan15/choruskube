package com.choruskube.core.observability;

import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpUsageSink implements UsageSink {
    @Override
    public void record(String eventType, String resourceType, UUID resourceId, String metadata) {
        // no-op: single-tenant core does not persist usage events
    }
}
