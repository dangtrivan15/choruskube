package com.choruskube.core.dto;

import java.util.UUID;

public record RoadmapItemEvent(String itemType, UUID itemId, String status) {}
