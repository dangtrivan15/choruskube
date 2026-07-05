package com.choruskube.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateRunPullRequestRequest(
        @NotNull UUID gitRepoId, @NotBlank String prUrl, Integer prNumber, String title, String repoName) {}
