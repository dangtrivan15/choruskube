package com.choruskube.core.dto;

import java.util.Map;
import java.util.UUID;

/**
 * What a Worker sends to prepare a workload: the template node it will launch and the
 * {@code config.json} it will launch with. Image, namespace, credentials and identity are
 * resolved server-side from the stored graph snapshot, not supplied here.
 */
public record PrepareWorkloadRequest(UUID templateNodeId, Map<String, Object> configJson) {}
