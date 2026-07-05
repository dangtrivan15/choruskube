package com.choruskube.core.dto;

import java.util.UUID;

public record TemplateEdgeResponse(
        UUID id, UUID graphTemplateId, UUID sourceNodeId, UUID targetNodeId, String condition) {}
