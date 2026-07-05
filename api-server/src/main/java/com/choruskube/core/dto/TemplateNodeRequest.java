package com.choruskube.core.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TemplateNodeRequest(
        @NotNull UUID nodeDefinitionId, @NotBlank String label, JsonNode configOverrides, Boolean entrypoint) {}
