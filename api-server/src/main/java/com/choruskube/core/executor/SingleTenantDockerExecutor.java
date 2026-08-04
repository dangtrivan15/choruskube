package com.choruskube.core.executor;

import com.choruskube.core.credential.AiCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SingleTenantDockerExecutor implements WorkloadExecutor {

    private static final Logger log = LoggerFactory.getLogger(SingleTenantDockerExecutor.class);
    private static final String CONTAINER_NAME_PREFIX = "ck-agent-";
    private static final String LABEL_APP = "choruskube-agent";
    private static final int LOG_LIMIT_BYTES = 64 * 1024;
    private static final String DIND_IMAGE = "docker:29-dind";
    private static final String DIND_NAME_PREFIX = "ck-dind-";
    private static final String DIND_VOLUME_PREFIX = "ck-dind-data-";
    private static final String LABEL_HAS_DIND = "choruskube/has-dind";
    // Instance field so tests can inject a short timeout
    private int dindReadyTimeoutSecs = 30;

    private final DockerClient docker;
    private final DockerExecutorConfig config;
    private final ObjectMapper objectMapper;
    private final AiCredentialResolver aiCredentialResolver;

    /**
     * Docker-specific executor configuration.
     *
     * <p>{@code stagingDir} is the base directory under which per-execution config/credential
     * staging dirs are created. In Docker-out-of-Docker mode the api-server runs in a container
     * but launches the agent as a sibling via the host socket, so a bind source path must resolve
     * to the same bytes on the host daemon — this dir is mounted at an identical path on host and
     * container. Blank → fall back to the JVM temp dir (direct-on-host / test usage).
     */
    public record DockerExecutorConfig(
            String host,
            Map<String, String> secretMap,
            String network,
            List<CredentialSpec> infraCredentials,
            String stagingDir) {}

    public SingleTenantDockerExecutor(DockerExecutorConfig config, AiCredentialResolver aiCredentialResolver) {
        this.config = config;
        this.aiCredentialResolver = aiCredentialResolver;
        this.objectMapper = new ObjectMapper();

        var clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(config.host() != null ? config.host() : "unix:///var/run/docker.sock")
                .build();

        var httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(URI.create(config.host() != null ? config.host() : "unix:///var/run/docker.sock"))
                .connectionTimeout(Duration.ofSeconds(30))
                .responseTimeout(Duration.ofSeconds(45))
                .build();

        this.docker = DockerClientImpl.getInstance(clientConfig, httpClient);

        log.info("SingleTenantDockerExecutor initialized — single-tenant Docker mode active "
                + "(single-tenant only; multi-org execution is not supported).");
    }

    /** Constructor for testing with an injected DockerClient. */
    SingleTenantDockerExecutor(
            DockerExecutorConfig config, DockerClient docker, AiCredentialResolver aiCredentialResolver) {
        this.config = config;
        this.docker = docker;
        this.aiCredentialResolver = aiCredentialResolver;
        this.objectMapper = new ObjectMapper();

        log.info("SingleTenantDockerExecutor initialized — single-tenant Docker mode active "
                + "(single-tenant only; multi-org execution is not supported).");
    }

    /** Constructor for testing with an injected DockerClient and custom DinD timeout. */
    SingleTenantDockerExecutor(
            DockerExecutorConfig config,
            DockerClient docker,
            AiCredentialResolver aiCredentialResolver,
            int dindReadyTimeoutSecs) {
        this(config, docker, aiCredentialResolver);
        this.dindReadyTimeoutSecs = dindReadyTimeoutSecs;
    }

    @Override
    public ExecutionResult execute(ExecutionParams params) {
        // Validate api_server_url is present and not a loopback address. The Orchestrator is
        // authoritative for this value; the executor enforces the contract so that
        // misconfiguration is caught fast (before any container or filesystem operations)
        // rather than silently writing an unreachable URL to config.json.
        Object rawApiServerUrl = params.configJson().get("api_server_url");
        if (rawApiServerUrl == null || rawApiServerUrl.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "configJson must contain a non-empty api_server_url for Docker executor mode; "
                            + "ensure the Orchestrator's API_SERVER_URL env var is set to a Docker-network-resolvable "
                            + "hostname (e.g., http://api-server:8080)");
        }
        String apiServerUrl = rawApiServerUrl.toString();
        try {
            String host = new URI(apiServerUrl).getHost();
            if (host == null) {
                throw new IllegalArgumentException("api_server_url '" + apiServerUrl
                        + "' has no host component (missing scheme?); "
                        + "set the Orchestrator's API_SERVER_URL env var to a Docker-network-resolvable "
                        + "hostname (e.g., http://api-server:8080)");
            }
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)) {
                throw new IllegalArgumentException(
                        "api_server_url '" + apiServerUrl + "' is a loopback address and is not reachable "
                                + "from inside a Docker container; set the Orchestrator's API_SERVER_URL env var to a "
                                + "Docker-network-resolvable hostname (e.g., http://api-server:8080)");
            }
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("api_server_url '" + apiServerUrl + "' is not a valid URL; "
                    + "set the Orchestrator's API_SERVER_URL env var to a Docker-network-resolvable "
                    + "hostname (e.g., http://api-server:8080)");
        }

        // --- Parameters not applicable in single-tenant Docker mode ---
        // params.identity()          Not used: K8s ServiceAccount (name, runAsUser, API token mount)
        //                            is a Kubernetes-only construct; Docker containers run with
        //                            inherited host daemon privileges.
        // params.nodeId()            Not used: K8s uses the template node ID for Job/ConfigMap label
        //                            selectors; Docker names containers by execution ID prefix only.
        // ---------------------------------------------------------------

        String execIdShort = params.nodeExecutionId().toString().substring(0, 8);
        String containerName = CONTAINER_NAME_PREFIX + execIdShort;

        // 1. Generate JOB_SECRET
        var secretAndHash = JobSecretGenerator.generate();

        // 2. Write config.json to a staging dir reachable by the host Docker daemon.
        // In DooD mode the bind source path is resolved against the HOST filesystem, so it must
        // live under the shared, host-mounted staging dir (see createStagingDir / DockerExecutorConfig).
        Path tmpDir;
        Path configPath;
        try {
            tmpDir = createStagingDir(execIdShort);
            configPath = tmpDir.resolve("config.json");
            String configJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(params.configJson());
            Files.writeString(configPath, configJson, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write config.json", e);
        }

        Path credsDir = tmpDir.resolve("creds");

        try {
            // 3. Build container configuration
            String networkName =
                    config.network() != null && !config.network().isBlank() ? config.network() : "choruskube";

            List<String> env = new ArrayList<>();
            env.add("JOB_SECRET=" + secretAndHash.secret());
            // Inject Claude OAuth token for non-script nodes only — script nodes don't invoke
            // `claude` and don't need the credential. Mirrors KubernetesWorkloadExecutor.needsOauthToken.
            if (needsOauthToken(params)) {
                env.add("CLAUDE_CODE_OAUTH_TOKEN=" + aiCredentialResolver.resolveOauthToken(params.runId()));
            }
            // Test-node (executor_type=="script") dogfood executions run run-all-tests, which
            // eval's this repo's test_command (./scripts/e2e.sh) as a single subprocess tree
            // inside this one container — there is no shard-level fan-out available to it, only
            // in-stack Playwright worker parallelism. Without an explicit env var it would
            // silently inherit playwright.config.ts's "unset -> serial" default, which is correct
            // for local dev but means a Test-node run never benefits from suite parallelization.
            // The worker count is a starting estimate (unmeasured against real dogfood-run
            // duration), not an empirically tuned value — tune after observing rollout. No
            // matching CPU/memory bump is needed here: this executor sets no resource limits on
            // agent containers today.
            if (isScriptExecution(params)) {
                env.add("E2E_WORKERS=3");
            }

            List<Bind> binds = new ArrayList<>();
            binds.add(new Bind(configPath.toString(), new Volume("/workspace/config.json"), AccessMode.ro));

            // DinD sidecar: launch isolated Docker daemon for this execution
            if (params.enableDocker()) {
                try {
                    String dindContainerId = startDindSidecar(execIdShort, networkName);
                    waitForDindReady(dindContainerId);
                } catch (Exception dindEx) {
                    cleanupDindResources(execIdShort);
                    throw new RuntimeException("Failed to start DinD sidecar: " + dindEx.getMessage(), dindEx);
                }
                env.add("DOCKER_HOST=tcp://" + DIND_NAME_PREFIX + execIdShort + ":2375");
            }

            // Process infrastructure credentials + per-node credentials
            List<CredentialSpec> allCreds = new ArrayList<>();
            if (config.infraCredentials() != null) {
                allCreds.addAll(config.infraCredentials());
            }
            if (params.nodeCredentials() != null) {
                allCreds.addAll(params.nodeCredentials());
            }

            Map<String, String> secretMap = config.secretMap() != null ? config.secretMap() : Map.of();

            try {
                Files.createDirectories(credsDir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create credential staging directory", e);
            }

            int credIdx = 0;
            for (CredentialSpec cred : allCreds) {
                String hostPath = cred.source();

                // For Docker, Source is already a host path. But if it looks like a
                // logical name (no path separator), try the SecretMap.
                if (!hostPath.contains("/") && !hostPath.contains("\\")) {
                    String mapped = secretMap.get(hostPath);
                    if (mapped == null) {
                        deleteTempDir(tmpDir);
                        throw new RuntimeException(
                                "Secret not mapped: '" + hostPath + "' not in secret map and not a path");
                    }
                    hostPath = mapped;
                }

                switch (cred.delivery()) {
                    case "volume" -> {
                        try {
                            Path staged = stageCredential(credsDir, credIdx, hostPath);
                            binds.add(new Bind(
                                    staged.toString(),
                                    new Volume(cred.mountPath()),
                                    cred.isReadOnly() ? AccessMode.ro : AccessMode.rw));
                        } catch (IOException e) {
                            deleteTempDir(tmpDir);
                            throw new RuntimeException("Failed to stage credential at " + hostPath, e);
                        }
                    }
                    case "env" -> {
                        List<String> envVars = readEnvFile(hostPath);
                        if (envVars != null) {
                            env.addAll(envVars);
                        } else {
                            env.add("SECRET_" + cred.source().toUpperCase() + "_PATH=" + hostPath);
                        }
                    }
                }
                credIdx++;
            }

            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("app", LABEL_APP);
            labels.put("choruskube/run-id", params.runId().toString());
            labels.put("choruskube/exec-id", params.nodeExecutionId().toString());
            labels.put("choruskube/tmp-dir", tmpDir.toString());
            if (params.enableDocker()) {
                labels.put(LABEL_HAS_DIND, "true");
            }

            // Best-effort image pull
            pullImageBestEffort(params.image());

            // Create container
            CreateContainerResponse response = docker.createContainerCmd(params.image())
                    .withName(containerName)
                    .withEnv(env)
                    .withLabels(labels)
                    .withHostConfig(HostConfig.newHostConfig()
                            .withBinds(binds.toArray(Bind[]::new))
                            .withNetworkMode(networkName))
                    .exec();

            // Start container
            docker.startContainerCmd(response.getId()).exec();

            return new ExecutionResult(response.getId(), secretAndHash.hash());
        } catch (Exception e) {
            deleteTempDir(tmpDir);
            if (params.enableDocker()) {
                cleanupDindResources(execIdShort);
            }
            throw e instanceof RuntimeException re ? re : new RuntimeException("Failed to execute", e);
        }
    }

    @Override
    public void cleanup(UUID executionId) {
        var container = findContainer(executionId);
        if (container == null) {
            return; // already gone — idempotent
        }

        // Remove temp config dir (stored in label)
        String tmpDir = container.getLabels().get("choruskube/tmp-dir");
        if (tmpDir != null && !tmpDir.isBlank()) {
            deleteTempDir(Path.of(tmpDir));
        }

        // Force-remove container
        docker.removeContainerCmd(container.getId())
                .withForce(true)
                .withRemoveVolumes(true)
                .exec();

        // Remove DinD sidecar and data volume if this was a DinD-enabled execution
        if ("true".equals(container.getLabels().get(LABEL_HAS_DIND))) {
            String execId = container.getLabels().get("choruskube/exec-id");
            if (execId != null && !execId.isBlank()) {
                String execIdShort = execId.substring(0, 8);
                cleanupDindResources(execIdShort);
            }
        }
    }

    @Override
    public String getLogs(UUID executionId, int tailLines) {
        var container = findContainer(executionId);
        if (container == null) {
            return "(no container found)";
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            LogContainerCmd cmd = docker.logContainerCmd(container.getId())
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tailLines);

            cmd.exec(
                            new com.github.dockerjava.api.async.ResultCallbackTemplate<
                                    com.github.dockerjava.api.async.ResultCallbackTemplate<?, Frame>, Frame>() {
                                @Override
                                public void onNext(Frame frame) {
                                    try {
                                        output.write(frame.getPayload());
                                    } catch (IOException e) {
                                        // ignore
                                    }
                                }
                            })
                    .awaitCompletion();

            byte[] data = output.toByteArray();
            if (data.length > LOG_LIMIT_BYTES) {
                data = Arrays.copyOfRange(data, data.length - LOG_LIMIT_BYTES, data.length);
            }
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(failed to read logs: " + e.getMessage() + ")";
        }
    }

    @Override
    public void terminate(UUID executionId) {
        var container = findContainer(executionId);
        if (container == null) {
            return; // already gone — idempotent
        }

        try {
            docker.stopContainerCmd(container.getId()).withTimeout(30).exec();
        } catch (com.github.dockerjava.api.exception.NotModifiedException e) {
            // Container already stopped
        } catch (NotFoundException e) {
            // Container already gone
        }
    }

    @Override
    public List<ExecutionInfo> listExecutions() {
        List<Container> containers = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("app", LABEL_APP))
                .exec();

        return containers.stream()
                .map(c -> {
                    UUID nodeExecId = null;
                    UUID runId = null;

                    String execIdStr = c.getLabels().get("choruskube/exec-id");
                    if (execIdStr != null) {
                        try {
                            nodeExecId = UUID.fromString(execIdStr);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }

                    String runIdStr = c.getLabels().get("choruskube/run-id");
                    if (runIdStr != null) {
                        try {
                            runId = UUID.fromString(runIdStr);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }

                    return new ExecutionInfo(nodeExecId, runId, c.getId());
                })
                .collect(Collectors.toList());
    }

    @Override
    public void healthCheck() {
        docker.pingCmd().exec();
    }

    // --- Helpers ---

    private Container findContainer(UUID executionId) {
        List<Container> containers = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of("choruskube/exec-id", executionId.toString()))
                .exec();
        return containers.isEmpty() ? null : containers.getFirst();
    }

    /**
     * Starts the Docker-in-Docker sidecar container for a DinD-enabled execution.
     * Returns the container ID of the started DinD container.
     */
    private String startDindSidecar(String execIdShort, String networkName) {
        String dindName = DIND_NAME_PREFIX + execIdShort;
        String volName = DIND_VOLUME_PREFIX + execIdShort;

        // Create the named volume for the inner daemon's data
        docker.createVolumeCmd().withName(volName).exec();

        // Build health check: CMD docker version, poll every 1s, timeout 3s, start after 5s
        HealthCheck healthCheck = new HealthCheck()
                .withTest(java.util.Arrays.asList("CMD", "docker", "version"))
                .withInterval(1_000_000_000L)
                .withTimeout(3_000_000_000L)
                .withRetries(30)
                .withStartPeriod(5_000_000_000L);

        // Named volume bind: volume name (not a path) → /var/lib/docker
        Bind dindVolBind = new Bind(volName, new Volume("/var/lib/docker"));

        // The Engine API's /containers/create does not auto-pull, so pull the sidecar
        // image first (best-effort, like the agent image) — otherwise a fresh daemon
        // 404s with "No such image: docker:29-dind".
        pullImageBestEffort(DIND_IMAGE);

        CreateContainerResponse dindResp = docker.createContainerCmd(DIND_IMAGE)
                .withName(dindName)
                .withEnv(List.of("DOCKER_TLS_CERTDIR="))
                .withLabels(Map.of("app", LABEL_APP, "choruskube/dind", "true"))
                .withHealthcheck(healthCheck)
                .withHostConfig(HostConfig.newHostConfig()
                        .withBinds(dindVolBind)
                        .withNetworkMode(networkName)
                        .withPrivileged(true))
                .exec();

        docker.startContainerCmd(dindResp.getId()).exec();
        return dindResp.getId();
    }

    /**
     * Polls the DinD container's health status until "healthy" or timeout.
     * Throws RuntimeException if the container does not become healthy within dindReadyTimeoutSecs.
     */
    private void waitForDindReady(String containerId) {
        for (int i = 0; i < dindReadyTimeoutSecs; i++) {
            try {
                InspectContainerResponse inspect =
                        docker.inspectContainerCmd(containerId).exec();
                InspectContainerResponse.ContainerState state = inspect.getState();
                if (state != null
                        && state.getHealth() != null
                        && "healthy".equals(state.getHealth().getStatus())) {
                    log.debug("DinD sidecar {} is healthy after {}s", containerId, i);
                    return;
                }
            } catch (Exception e) {
                log.debug("DinD health poll error (attempt {}): {}", i, e.getMessage());
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for DinD sidecar readiness", ie);
            }
        }
        throw new RuntimeException("DinD sidecar did not become healthy within " + dindReadyTimeoutSecs + "s");
    }

    /**
     * Removes the DinD sidecar container and its data volume. Idempotent — NotFoundException is ignored.
     */
    private void cleanupDindResources(String execIdShort) {
        String dindName = DIND_NAME_PREFIX + execIdShort;
        String volName = DIND_VOLUME_PREFIX + execIdShort;

        try {
            docker.removeContainerCmd(dindName).withForce(true).exec();
            log.debug("DinD container {} removed", dindName);
        } catch (NotFoundException e) {
            log.debug("DinD container {} already gone: {}", dindName, e.getMessage());
        }

        try {
            docker.removeVolumeCmd(volName).exec();
            log.debug("DinD volume {} removed", volName);
        } catch (NotFoundException e) {
            log.debug("DinD volume {} already gone: {}", volName, e.getMessage());
        }
    }

    /**
     * Creates the per-execution staging directory that holds config.json and staged credentials.
     *
     * <p>When {@code config.stagingDir()} is set, the dir is created under that shared, host-mounted
     * base so its absolute path is valid on both the api-server container and the host Docker daemon
     * (required for sibling bind mounts in Docker-out-of-Docker mode). When blank, falls back to the
     * JVM temp dir — correct for direct-on-host runs and unit tests.
     */
    private Path createStagingDir(String execIdShort) throws IOException {
        String prefix = "ck-config-" + execIdShort + "-";
        String base = config.stagingDir();
        if (base != null && !base.isBlank()) {
            Path baseDir = Files.createDirectories(Path.of(base));
            return Files.createTempDirectory(baseDir, prefix);
        }
        return Files.createTempDirectory(prefix);
    }

    /**
     * Ensures {@code imageName} is available locally, pulling only when it is absent.
     *
     * <p>Local-first by design: when the image is already present we skip the registry
     * round-trip entirely. This keeps the local stack fully self-contained and predictable
     * (agent images are built locally — see scripts/build-agent-images.sh), and avoids a
     * per-run pull that 404s on hosts whose architecture the published image lacks (e.g.
     * the amd64-only published images on an arm64 dev host). The pull, when needed, stays
     * best-effort: a failure is logged and execution proceeds against whatever is present.
     */
    private void pullImageBestEffort(String imageName) {
        try {
            docker.inspectImageCmd(imageName).exec();
            log.debug("Image {} present locally — skipping pull", imageName);
            return;
        } catch (NotFoundException e) {
            // Not present locally — fall through and pull it.
        } catch (Exception e) {
            log.debug("Image inspect for {} failed, attempting pull anyway: {}", imageName, e.getMessage());
        }
        try {
            docker.pullImageCmd(imageName).start().awaitCompletion(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug(
                    "Best-effort image pull failed for {} (may already be available locally): {}",
                    imageName,
                    e.getMessage());
        }
    }

    private static List<String> readEnvFile(String path) {
        try {
            return Files.readAllLines(Path.of(path), StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#") && line.contains("="))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Copies the credential at {@code hostPath} into a private subdirectory
     * {@code credsDir/{index}/} and returns the path to the staged copy.
     *
     * <p>If {@code hostPath} is a directory, the entire tree is recursively
     * copied and {@code stagingDir} itself is returned. If it is a regular
     * file (or a symlink treated as a file), the target file path is returned.
     *
     * @throws IOException if the source does not exist or copying fails
     */
    private static Path stageCredential(Path credsDir, int index, String hostPath) throws IOException {
        Path stagingDir = Files.createDirectories(credsDir.resolve(String.valueOf(index)));
        Path source = Path.of(hostPath);
        if (!Files.exists(source)) {
            throw new IOException("Credential source does not exist: " + hostPath);
        }
        if (Files.isDirectory(source)) {
            try (var walk = Files.walk(source)) {
                for (Path entry : (Iterable<Path>) walk::iterator) {
                    Path relative = source.relativize(entry);
                    Path target = stagingDir.resolve(relative);
                    if (Files.isDirectory(entry)) {
                        Files.createDirectories(target);
                    } else {
                        Files.copy(entry, target, StandardCopyOption.COPY_ATTRIBUTES);
                    }
                }
            }
            return stagingDir;
        } else {
            Path target = stagingDir.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return target;
        }
    }

    private static void deleteTempDir(Path dir) {
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException ignored) {
        }
    }

    static boolean needsOauthToken(ExecutionParams params) {
        Object raw = params.configJson() != null ? params.configJson().getOrDefault("executor_type", "ai") : "ai";
        return !"script".equalsIgnoreCase(String.valueOf(raw));
    }

    /** Test-node ("script" executor_type) executions — see the E2E_WORKERS env comment above. */
    static boolean isScriptExecution(ExecutionParams params) {
        return !needsOauthToken(params);
    }
}
