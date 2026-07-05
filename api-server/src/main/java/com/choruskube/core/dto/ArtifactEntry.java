package com.choruskube.core.dto;

import java.time.Instant;

public record ArtifactEntry(String name, long size, Instant lastModified) {}
