package com.choruskube.core.dto;

import java.util.UUID;

public record LiveChatMessageEvent(String type, UUID sessionId, String role, String content) {}
