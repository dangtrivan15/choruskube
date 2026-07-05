package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

public record InternalSetTraversedEdgesRequest(List<UUID> edgeIds) {}
