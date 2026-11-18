package com.choruskube.core.model;

import java.io.Serializable;
import java.util.UUID;

public record ArtifactUploadBookingId(UUID workflowRunId, String objectKey) implements Serializable {}
