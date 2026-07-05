package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

public record ExecutionLogResponse(UUID id, String level, String message, Instant timestamp) {}
