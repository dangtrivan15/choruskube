package com.choruskube.core.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record TemplateEdgeRequest(
        @NotNull UUID sourceNodeId, @NotNull UUID targetNodeId, String condition) {}
