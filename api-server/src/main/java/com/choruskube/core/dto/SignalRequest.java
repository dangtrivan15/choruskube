package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;

public record SignalRequest(@NotBlank String decision, String feedback, String attachmentRefs) {}
