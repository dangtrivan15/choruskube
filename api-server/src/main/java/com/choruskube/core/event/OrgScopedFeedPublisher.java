package com.choruskube.core.event;

import java.util.UUID;

public interface OrgScopedFeedPublisher {

    void pendingGatesChanged(UUID runId, Object payload);

    void roadmapItemChanged(String resourceType, UUID resourceId, Object payload);
}
