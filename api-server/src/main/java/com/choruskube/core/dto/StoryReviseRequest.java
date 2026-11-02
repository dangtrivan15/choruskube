package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StoryReviseRequest(
        @NotBlank @Size(max = 20000) String guidance) {}
