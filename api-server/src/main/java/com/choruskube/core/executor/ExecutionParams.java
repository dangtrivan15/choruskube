package com.choruskube.core.executor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ExecutionParams(
        UUID nodeExecutionId,
        UUID runId,
        UUID nodeId,
        String image,
        Map<String, Object> configJson,
        boolean enableDocker,
        List<CredentialSpec> nodeCredentials,
        IdentitySpec identity) {}
