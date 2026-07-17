package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StoryRequest(
        @NotBlank @Size(max = 255) String title, @NotBlank String description) {}
