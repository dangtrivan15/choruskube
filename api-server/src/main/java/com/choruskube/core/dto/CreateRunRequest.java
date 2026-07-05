package com.choruskube.core.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record CreateRunRequest(
        @NotNull UUID graphTemplateId,
        Map<String, Object> inputs,
        @Size(max = 255) String name,
        String inputAttachmentRefs) {}
