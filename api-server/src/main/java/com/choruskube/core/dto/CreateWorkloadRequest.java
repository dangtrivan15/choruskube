package com.choruskube.core.dto;

import java.util.Map;
import java.util.UUID;

/**
 * The orchestrator sends only workflow context. Infrastructure details
 * (image, namespace, secrets, docker config, identity) are resolved by the
 * API server from the stored graph snapshot.
 */
public record CreateWorkloadRequest(UUID templateNodeId, Map<String, Object> configJson) {}
