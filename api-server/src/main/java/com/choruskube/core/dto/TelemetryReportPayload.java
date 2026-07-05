package com.choruskube.core.dto;

import java.util.UUID;

public record TelemetryReportPayload(
        int schemaVersion, UUID installId, String appVersion, String os, String arch, int runCount) {}
