package com.choruskube.core.dto;

import java.util.UUID;

public record RunEvent(String type, UUID runId, UUID nodeExecutionId, String status) {}
