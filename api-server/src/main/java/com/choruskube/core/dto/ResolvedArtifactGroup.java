package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

public record ResolvedArtifactGroup(UUID nodeExecutionId, String nodeLabel, List<ResolvedArtifactEntry> artifacts) {}
