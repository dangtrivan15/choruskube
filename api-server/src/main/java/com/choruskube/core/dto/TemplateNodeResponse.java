package com.choruskube.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record TemplateNodeResponse(
        UUID id,
        UUID graphTemplateId,
        UUID nodeDefinitionId,
        String label,
        JsonNode configOverrides,
        boolean entrypoint) {}
