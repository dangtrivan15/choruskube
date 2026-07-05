package com.choruskube.core.config;

import java.util.UUID;

public interface StompSubscriptionAuthorizer {

    /**
     * @param destination the STOMP SUBSCRIBE destination (e.g. {@code /topic/runs/{runId}})
     * @param sessionOrgId the org stamped on the session at CONNECT (never null — the interceptor
     *     rejects null-org sessions before calling this)
     * @param sessionUserId the user stamped on the session at CONNECT (may be null)
     * @return true if the session may subscribe to {@code destination}
     */
    boolean canSubscribe(String destination, UUID sessionOrgId, UUID sessionUserId);
}
