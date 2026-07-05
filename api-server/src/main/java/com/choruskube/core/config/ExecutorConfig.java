package com.choruskube.core.config;

import com.choruskube.core.credential.AiCredentialResolver;
import com.choruskube.core.executor.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that creates the appropriate {@link WorkloadExecutor} bean
 * based on the {@code EXECUTOR_TYPE} environment variable.
 *
 * <p>This holds the OSS-side executor wiring: the Docker ({@code executor.type=docker}) and
 * no-op ({@code executor.type=none}) beans, plus the neutral callback/URL beans used by all
 * modes. The Kubernetes beans ({@code executor.type=k8s}) live in a separate module
 * (not part of OSS core); they reuse {@link #parseJson} from here.
 */
@Configuration
public class ExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ExecutorConfig.class);

    @Value("${executor.type:k8s}")
    private String executorType;

    // --- Docker Config ---
    @Value("${executor.docker.host:unix:///var/run/docker.sock}")
    private String dockerHost;

    @Value("${executor.docker.network:choruskube}")
    private String dockerNetwork;

    @Value("${executor.docker.secret-map:{}}")
    private String dockerSecretMapJson;

    @Value("${executor.docker.agent-secrets:[]}")
    private String dockerAgentSecretsJson;

    // Shared, host-mounted base dir for per-execution config/credential staging. Required for
    // Docker-out-of-Docker (the api-server stages files the sibling agent bind-mounts); blank
    // falls back to the JVM temp dir for direct-on-host runs.
    @Value("${executor.docker.staging-dir:}")
    private String dockerStagingDir;

    // --- Default agent image (system-wide fallback) ---
    @Value("${executor.default-agent-image:}")
    private String defaultAgentImage;

    // --- Callback ---
    @Value("${executor.callback-url:}")
    private String callbackUrl;

    // --- API Server self-URL (used by chat pods to call back) ---
    @Value("${executor.api-server-url:http://localhost:8080}")
    private String apiServerUrl;

    @Bean
    public String executorCallbackUrl() {
        if (!"none".equals(executorType) && (callbackUrl == null || callbackUrl.isBlank())) {
            throw new IllegalStateException("CALLBACK_URL is required when executor.type=" + executorType);
        }
        return callbackUrl;
    }

    @Bean
    public String executorApiServerUrl() {
        return apiServerUrl;
    }

    @Bean
    public String executorDefaultAgentImage() {
        if (!"none".equals(executorType) && (defaultAgentImage == null || defaultAgentImage.isBlank())) {
            throw new IllegalStateException("DEFAULT_AGENT_IMAGE is required when executor.type=" + executorType);
        }
        return defaultAgentImage;
    }

    @Bean
    @ConditionalOnProperty(name = "executor.type", havingValue = "docker")
    public WorkloadExecutor singleTenantDockerExecutor(AiCredentialResolver aiCredentialResolver) {
        Map<String, String> secretMap =
                parseJson(dockerSecretMapJson, new TypeReference<Map<String, String>>() {}, "DOCKER_SECRET_MAP");

        List<CredentialSpec> infraCredentials =
                parseJson(dockerAgentSecretsJson, new TypeReference<List<CredentialSpec>>() {}, "DOCKER_AGENT_SECRETS");

        var config = new SingleTenantDockerExecutor.DockerExecutorConfig(
                dockerHost, secretMap, dockerNetwork, infraCredentials, dockerStagingDir);

        log.info(
                "SingleTenantDockerExecutor active — single-tenant Docker mode (single-tenant only; "
                        + "multi-org execution not supported). host={}, network={}",
                dockerHost,
                dockerNetwork);
        return new SingleTenantDockerExecutor(config, aiCredentialResolver);
    }

    /**
     * Parses a JSON string into the target type, failing fast on malformed input.
     *
     * <p>This prevents silent fallbacks to empty collections that could leave the
     * executor running with missing credentials — a scenario that is difficult to
     * diagnose in production.
     *
     * <p>Public because the Kubernetes executor wiring (in a separate module, not part
     * of OSS core) reuses it to parse {@code K8S_AGENT_SECRETS}.
     *
     * @throws IllegalStateException if the JSON cannot be parsed
     */
    public static <T> T parseJson(String json, TypeReference<T> typeRef, String envVarName) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(json, typeRef);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse " + envVarName + " — fix the JSON value or unset the variable. Input: " + json, e);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "executor.type", havingValue = "none", matchIfMissing = true)
    public WorkloadExecutor noopWorkloadExecutor() {
        log.info("No executor configured (executor.type=none). Workload endpoints will return 503.");
        return new NoopWorkloadExecutor();
    }
}
