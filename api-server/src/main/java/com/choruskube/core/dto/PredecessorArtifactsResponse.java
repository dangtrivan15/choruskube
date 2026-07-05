package com.choruskube.core.dto;

import java.util.UUID;

public record PredecessorArtifactsResponse(UUID templateNodeId, String label, String artifactRefs, String result) {}
