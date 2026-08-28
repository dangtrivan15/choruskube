package com.choruskube.core.dto;

import java.util.UUID;

public record OrgRef(UUID id, String slug, String displayName) {}
