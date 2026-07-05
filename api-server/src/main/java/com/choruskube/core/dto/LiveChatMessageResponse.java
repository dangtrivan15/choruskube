package com.choruskube.core.dto;

import java.time.Instant;
import java.util.UUID;

public record LiveChatMessageResponse(UUID id, UUID sessionId, String role, String content, Instant createdAt) {}
