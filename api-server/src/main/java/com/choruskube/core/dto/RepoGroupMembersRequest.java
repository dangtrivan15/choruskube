package com.choruskube.core.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

public record RepoGroupMembersRequest(@NotEmpty List<UUID> memberRepoIds) {}
