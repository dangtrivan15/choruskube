package com.choruskube.core.event;

import java.util.UUID;

public interface OrgScopedFeedPublisher {

    void pendingGatesChanged(UUID runId, Object payload);

    void roadmapItemChanged(String resourceType, UUID resourceId, Object payload);

    /**
     * The Autopilot's configuration or live status changed.
     *
     * <p>Unlike every other method here, this one is called from a timer thread as well as from a
     * request: the Autopilot's own tick publishes through it. An implementation must therefore
     * derive the org it scopes the destination to from {@code autopilotId}, never from a
     * request-scoped tenant context.
     */
    void autopilotChanged(UUID autopilotId, Object payload);
}
