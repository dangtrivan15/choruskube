package com.choruskube.core.dto;

import java.util.UUID;

public record InternalCreateNodeExecutionRequest(
        UUID templateNodeId, int graphVersion, int iteration, String label, int iterationCapEpochStart) {}
