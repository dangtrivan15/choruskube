package com.choruskube.core.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.choruskube.core.credential.AiCredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateVolumeCmd;
import com.github.dockerjava.api.command.CreateVolumeResponse;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveVolumeCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;

class SingleTenantDockerExecutorTest {

    private ListAppender<ILoggingEvent> listAppender;

    private record DockerMocks(DockerClient docker, CreateContainerCmd createCmd) {}

    @AfterEach
    void detachAppender() {
        if (listAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(SingleTenantDockerExecutor.class);
            logger.detachAppender(listAppender);
        }
    }

    private ListAppender<ILoggingEvent> attachListAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SingleTenantDockerExecutor.class);
        listAppender = new ListAppender<>();
        listAppender.start();
        logger.addAppender(listAppender);
        return listAppender;
    }

    private SingleTenantDockerExecutor.DockerExecutorConfig testConfig() {
        // stagingDir=null → fall back to the JVM temp dir (no host-mounted shared dir in tests).
        return new SingleTenantDockerExecutor.DockerExecutorConfig(
                "tcp://localhost:2375", Map.of(), "test-network", List.of(), null);
    }

    /** Builds a Docker client mock with the minimal stubs required to run {@code execute()}. */
    private DockerMocks mockDockerClient() {
        DockerClient docker = mock(DockerClient.class);

        // Inspect-image command: by default the image is treated as ABSENT (throws
        // NotFoundException) so pullImageBestEffort proceeds to pull — preserving the
        // pull-path behavior the existing assertions rely on.
        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(inspectImageCmd.exec()).thenThrow(new NotFoundException("image not present"));
        when(docker.inspectImageCmd(anyString())).thenReturn(inspectImageCmd);

        // Pull command: best-effort, so throwing here is caught silently by pullImageBestEffort.
        when(docker.pullImageCmd(anyString())).thenThrow(new RuntimeException("mocked pull fail"));

        // Create container command: fluent chain returns itself via RETURNS_SELF; exec() returns
        // a response with a fixed container ID.
        CreateContainerCmd createCmd =
                mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn("test-container-id");
        when(createCmd.exec()).thenReturn(createResponse);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

        // Start container command: exec() is void by default in Mockito.
        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(anyString())).thenReturn(startCmd);

        // Volume commands for DinD
        CreateVolumeCmd createVolumeCmd =
                mock(CreateVolumeCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(createVolumeCmd.exec()).thenReturn(mock(CreateVolumeResponse.class));
        when(docker.createVolumeCmd()).thenReturn(createVolumeCmd);

        RemoveVolumeCmd removeVolumeCmd = mock(RemoveVolumeCmd.class);
        when(docker.removeVolumeCmd(anyString())).thenReturn(removeVolumeCmd);

        // Inspect container for DinD health check — healthy by default
        HealthState healthState = mock(HealthState.class);
        when(healthState.getStatus()).thenReturn("healthy");
        InspectContainerResponse.ContainerState containerState = mock(InspectContainerResponse.ContainerState.class);
        when(containerState.getHealth()).thenReturn(healthState);
        InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
        when(inspectResponse.getState()).thenReturn(containerState);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);

        return new DockerMocks(docker, createCmd);
    }

    /**
     * Builds a minimal {@link ExecutionParams} with a valid Docker-resolvable api_server_url.
     * Defaults to "ai" executor type (no explicit executor_type key) so that
     * {@code needsOauthToken} returns true, exercising the credential-resolution path.
     */
    private ExecutionParams baseParams() {
        return new ExecutionParams(
                UUID.randomUUID(), // nodeExecutionId
                UUID.randomUUID(), // runId
                UUID.randomUUID(), // nodeId — not applicable in Docker mode
                "choruskube/agent:test", // image
                Map.of("api_server_url", "http://api-server:8080"), // configJson — valid URL + "ai" type
                false, // enableDocker
                List.of(), // nodeCredentials
                null); // identity — not applicable in Docker mode
    }

    /**
     * Builds a minimal {@link ExecutionParams} with the given executionId and credentials.
     * Uses a valid Docker-resolvable api_server_url.
     */
    private ExecutionParams paramsWithCredentials(UUID executionId, List<CredentialSpec> creds) {
        return new ExecutionParams(
                executionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://api-server:8080"),
                false,
                creds,
                null);
    }

    // -----------------------------------------------------------------------
    // execute — token resolved via runId
    // -----------------------------------------------------------------------

    @Test
    void execute_usesRunIdForOauthTokenResolution() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerClient docker = mockDockerClient().docker();
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = baseParams();
        ExecutionResult result = executor.execute(params);

        // Execution must succeed and return the container handle and a non-null secret hash.
        assertThat(result).isNotNull();
        assertThat(result.executionHandle()).isEqualTo("test-container-id");
        assertThat(result.jobSecretHash()).isNotNull();

        // Token must be resolved once, keyed by the run id, for the oauth env var.
        verify(credentialService, times(1)).resolveOauthToken(params.runId());
    }

    // -----------------------------------------------------------------------
    // Local-first image resolution: skip the pull when the image is present
    // -----------------------------------------------------------------------

    @Test
    void execute_whenAgentImagePresentLocally_skipsPull() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        // Override the default "absent" stub: the agent image IS present locally.
        InspectImageCmd inspectImageCmd = mock(InspectImageCmd.class);
        when(inspectImageCmd.exec()).thenReturn(mock(InspectImageResponse.class));
        when(mocks.docker().inspectImageCmd(anyString())).thenReturn(inspectImageCmd);

        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService);

        ExecutionResult result = executor.execute(baseParams());

        // Execution still succeeds using the locally present image...
        assertThat(result.executionHandle()).isEqualTo("test-container-id");
        // ...and no registry pull is attempted (local-first: avoids the per-run round-trip
        // that 404s for an architecture the published image lacks).
        verify(mocks.docker(), never()).pullImageCmd(anyString());
    }

    @Test
    void execute_whenAgentImageAbsentLocally_pullsImage() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        // mockDockerClient() defaults inspectImageCmd to throw NotFoundException (absent).
        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService);

        executor.execute(baseParams());

        // An absent image must fall through to a best-effort pull.
        verify(mocks.docker()).pullImageCmd("choruskube/agent:test");
    }

    // -----------------------------------------------------------------------
    // Constructor emits the single-tenant startup log notice
    // -----------------------------------------------------------------------

    @Test
    void constructor_logsInitializationNotice() {
        ListAppender<ILoggingEvent> appender = attachListAppender();

        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        DockerClient docker = mock(DockerClient.class);

        // The test constructor must emit the initialization log on every construction.
        new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        boolean hasInitLog = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.INFO
                        && e.getFormattedMessage().contains("single-tenant Docker mode active"));
        assertThat(hasInitLog).isTrue();
    }

    // -----------------------------------------------------------------------
    // URL validation — reject loopback / missing api_server_url
    // -----------------------------------------------------------------------

    @Test
    void execute_throwsWhenApiServerUrlIsLocalhost() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://localhost:8080"),
                false,
                List.of(),
                null);

        assertThatThrownBy(() -> executor.execute(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void execute_throwsWhenApiServerUrlIs127() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://127.0.0.1:8080"),
                false,
                List.of(),
                null);

        assertThatThrownBy(() -> executor.execute(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void execute_throwsWhenApiServerUrlAbsent() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of(), // no api_server_url key
                false,
                List.of(),
                null);

        assertThatThrownBy(() -> executor.execute(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("api_server_url");
    }

    @Test
    void execute_succeedsWhenApiServerUrlIsDockerResolvable() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerClient docker = mockDockerClient().docker();
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://api-server:8080"),
                false,
                List.of(),
                null);

        // Should not throw — validation passes for a Docker-network-resolvable hostname.
        ExecutionResult result = executor.execute(params);
        assertThat(result).isNotNull();
        assertThat(result.executionHandle()).isEqualTo("test-container-id");
    }

    @Test
    void execute_throwsWhenApiServerUrlIsLocalhostUppercase() {
        // Validates that the equalsIgnoreCase() comparison catches case variants such as LOCALHOST.
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://LOCALHOST:8080"),
                false,
                List.of(),
                null);

        assertThatThrownBy(() -> executor.execute(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("loopback");
    }

    @Test
    void execute_throwsWhenApiServerUrlIsMalformed() {
        // Validates that an unparseable URL (URISyntaxException path) is rejected with a clear error.
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://[invalid"), // unclosed IPv6 bracket → URISyntaxException
                false,
                List.of(),
                null);

        assertThatThrownBy(() -> executor.execute(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid URL");
    }

    @Test
    void execute_throwsWhenApiServerUrlHasNoHost() {
        // URI "api-server:8080" is syntactically valid (scheme="api-server", ssp="8080")
        // but has no authority / host component. This is a realistic misconfiguration when
        // the http:// scheme is omitted. getHost() returns null → distinct error message.
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "api-server:8080"), // missing http:// → URI parses with null host
                false,
                List.of(),
                null);

        assertThatThrownBy(() -> executor.execute(params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no host component");
    }

    // -----------------------------------------------------------------------
    // Credential staging tests
    // -----------------------------------------------------------------------

    @Test
    void execute_withVolumeCredential_stagesPrivateCopyNotOriginalPath() throws Exception {
        Path credFile = Files.createTempFile("test-cred-", ".json");
        try {
            Files.writeString(credFile, "{\"token\":\"abc\"}");

            AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
            when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

            DockerMocks mocks = mockDockerClient();
            SingleTenantDockerExecutor executor =
                    new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService);

            CredentialSpec cred =
                    new CredentialSpec(credFile.toString(), "volume", "/workspace/creds/cred.json", false);
            UUID execId = UUID.randomUUID();
            ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);

            executor.execute(paramsWithCredentials(execId, List.of(cred)));

            verify(mocks.createCmd()).withHostConfig(hostConfigCaptor.capture());
            HostConfig captured = hostConfigCaptor.getValue();

            Bind credBind = findBindByContainerPath(captured, "/workspace/creds/cred.json");
            assertThat(credBind).isNotNull();
            String stagedPath = credBind.getPath();

            // Staged path must differ from the original host file path.
            assertThat(stagedPath).isNotEqualTo(credFile.toString());
            // Staged path must live under a temp dir created for this execution.
            assertThat(Path.of(stagedPath).toString()).contains("ck-config-");
            // Content of staged file must match the original.
            assertThat(Files.readString(Path.of(stagedPath))).isEqualTo(Files.readString(credFile));
        } finally {
            Files.deleteIfExists(credFile);
        }
    }

    @Test
    void execute_twoSimultaneousExecutions_haveDistinctStagedCredentialPaths() throws Exception {
        Path credFile = Files.createTempFile("test-cred-", ".json");
        try {
            Files.writeString(credFile, "{\"token\":\"shared\"}");

            AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
            when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

            DockerMocks mocks = mockDockerClient();
            SingleTenantDockerExecutor executor =
                    new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService);

            CredentialSpec cred = new CredentialSpec(credFile.toString(), "volume", "/workspace/creds/cred.json", true);

            ArgumentCaptor<HostConfig> captor = ArgumentCaptor.forClass(HostConfig.class);

            // Sequential calls are sufficient to verify path-distinctness (structural guarantee
            // from Files.createTempDirectory uniqueness).
            executor.execute(paramsWithCredentials(UUID.randomUUID(), List.of(cred)));
            executor.execute(paramsWithCredentials(UUID.randomUUID(), List.of(cred)));

            verify(mocks.createCmd(), times(2)).withHostConfig(captor.capture());
            List<HostConfig> configs = captor.getAllValues();

            String path1 = findBindByContainerPath(configs.get(0), "/workspace/creds/cred.json")
                    .getPath();
            String path2 = findBindByContainerPath(configs.get(1), "/workspace/creds/cred.json")
                    .getPath();

            assertThat(path1).isNotEqualTo(path2);
            assertThat(path1).isNotEqualTo(credFile.toString());
            assertThat(path2).isNotEqualTo(credFile.toString());
        } finally {
            Files.deleteIfExists(credFile);
        }
    }

    @Test
    void execute_withVolumeCredential_hostFileUnchangedAfterStagedWrite() throws Exception {
        Path credFile = Files.createTempFile("test-cred-", ".txt");
        try {
            Files.writeString(credFile, "original");

            AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
            when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

            DockerMocks mocks = mockDockerClient();
            SingleTenantDockerExecutor executor =
                    new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService);

            CredentialSpec cred = new CredentialSpec(credFile.toString(), "volume", "/workspace/creds/cred.txt", false);

            ArgumentCaptor<HostConfig> captor = ArgumentCaptor.forClass(HostConfig.class);
            executor.execute(paramsWithCredentials(UUID.randomUUID(), List.of(cred)));

            verify(mocks.createCmd()).withHostConfig(captor.capture());
            String stagedPath = findBindByContainerPath(captor.getValue(), "/workspace/creds/cred.txt")
                    .getPath();

            // Simulate an in-container token rotation writing to the staged copy.
            Files.writeString(Path.of(stagedPath), "modified");

            // The original host file must remain unchanged.
            assertThat(Files.readString(credFile)).isEqualTo("original");
        } finally {
            Files.deleteIfExists(credFile);
        }
    }

    @Test
    void cleanup_deletesWholeTempdirIncludingCredentials() throws Exception {
        Path credFile = Files.createTempFile("test-cred-", ".txt");
        try {
            Files.writeString(credFile, "credential-content");

            AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
            when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

            DockerMocks mocks = mockDockerClient();
            DockerClient docker = mocks.docker();
            SingleTenantDockerExecutor executor =
                    new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

            CredentialSpec cred = new CredentialSpec(credFile.toString(), "volume", "/workspace/creds/cred.txt", true);
            UUID execId = UUID.randomUUID();

            ArgumentCaptor<HostConfig> captor = ArgumentCaptor.forClass(HostConfig.class);
            executor.execute(paramsWithCredentials(execId, List.of(cred)));

            verify(mocks.createCmd()).withHostConfig(captor.capture());
            String stagedPath = findBindByContainerPath(captor.getValue(), "/workspace/creds/cred.txt")
                    .getPath();
            // stagedPath = tmpDir/creds/{index}/cred.txt
            // getParent()         = tmpDir/creds/{index}/   (stagingDir)
            // getParent().getParent()         = tmpDir/creds/  (credsDir)
            // getParent().getParent().getParent() = tmpDir     (root temp dir, stored in label)
            Path credsDir = Path.of(stagedPath).getParent().getParent();
            Path tmpDir = credsDir.getParent();

            // Set up cleanup mocks — choruskube/tmp-dir must match what execute() stores: tmpDir.
            Container container = mock(Container.class);
            when(container.getId()).thenReturn("cleanup-container-id");
            when(container.getLabels())
                    .thenReturn(Map.of(
                            "choruskube/tmp-dir", tmpDir.toString(),
                            "choruskube/exec-id", execId.toString()));

            ListContainersCmd listCmd =
                    mock(ListContainersCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(listCmd.exec()).thenReturn(List.of(container));
            when(docker.listContainersCmd()).thenReturn(listCmd);

            RemoveContainerCmd removeCmd =
                    mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

            executor.cleanup(execId);

            // The entire tmpDir (including creds/ subtree) must be gone.
            assertThat(tmpDir).doesNotExist();
            assertThat(credsDir).doesNotExist();
            // The original credential file must still be present.
            assertThat(credFile).exists();
        } finally {
            Files.deleteIfExists(credFile);
        }
    }

    @Test
    void execute_withVolumeCredential_preStartFailureCleansUpStagedFiles() throws Exception {
        Path credFile = Files.createTempFile("test-cred-", ".txt");
        try {
            Files.writeString(credFile, "secret-content");

            AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
            when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

            // Override exec() to throw so we exercise the pre-start failure path.
            DockerClient docker = mock(DockerClient.class);
            when(docker.pullImageCmd(anyString())).thenThrow(new RuntimeException("mocked pull fail"));

            CreateContainerCmd createCmd =
                    mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            when(createCmd.exec()).thenThrow(new RuntimeException("simulated container-create failure"));
            when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

            SingleTenantDockerExecutor executor =
                    new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

            CredentialSpec cred = new CredentialSpec(credFile.toString(), "volume", "/workspace/creds/cred.txt", false);

            // withHostConfig is called before exec(), so the captor captures the bind even when
            // execute() ultimately throws.
            ArgumentCaptor<HostConfig> captor = ArgumentCaptor.forClass(HostConfig.class);

            assertThatThrownBy(() -> executor.execute(paramsWithCredentials(UUID.randomUUID(), List.of(cred))))
                    .isInstanceOf(RuntimeException.class);

            verify(createCmd).withHostConfig(captor.capture());
            String stagedPath = findBindByContainerPath(captor.getValue(), "/workspace/creds/cred.txt")
                    .getPath();

            // Catch block must have deleted the staged file along with tmpDir.
            assertThat(Path.of(stagedPath)).doesNotExist();
            // Original credential must be intact.
            assertThat(credFile).exists();
            assertThat(Files.readString(credFile)).isEqualTo("secret-content");
        } finally {
            Files.deleteIfExists(credFile);
        }
    }

    @Test
    void execute_withDirectoryVolumeCredential_stagesEntireDirectory() throws Exception {
        Path credDir = Files.createTempDirectory("test-creddir-");
        try {
            Files.writeString(credDir.resolve("token.json"), "{\"access_token\":\"t1\"}");
            Files.writeString(credDir.resolve("config.ini"), "key=value");

            AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
            when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

            DockerMocks mocks = mockDockerClient();
            SingleTenantDockerExecutor executor =
                    new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService);

            CredentialSpec cred = new CredentialSpec(credDir.toString(), "volume", "/workspace/creds/creddir", true);

            ArgumentCaptor<HostConfig> captor = ArgumentCaptor.forClass(HostConfig.class);
            executor.execute(paramsWithCredentials(UUID.randomUUID(), List.of(cred)));

            verify(mocks.createCmd()).withHostConfig(captor.capture());
            String stagedPath = findBindByContainerPath(captor.getValue(), "/workspace/creds/creddir")
                    .getPath();

            Path staged = Path.of(stagedPath);
            // Staged path must be a directory, not the original.
            assertThat(staged).isDirectory();
            assertThat(stagedPath).isNotEqualTo(credDir.toString());
            // Staged directory must contain the same files.
            assertThat(staged.resolve("token.json")).exists();
            assertThat(staged.resolve("config.ini")).exists();
            assertThat(Files.readString(staged.resolve("token.json"))).isEqualTo("{\"access_token\":\"t1\"}");
        } finally {
            // Recursively delete the temp credential directory
            try (var walk = Files.walk(credDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }

    @Test
    void execute_withMissingVolumeCredential_failsFastBeforeContainerCreate() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        CredentialSpec cred = new CredentialSpec(
                "/nonexistent/path/that/does/not/exist/cred.json", "volume", "/workspace/creds/cred.json", true);

        assertThatThrownBy(() -> executor.execute(paramsWithCredentials(UUID.randomUUID(), List.of(cred))))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(IOException.class);

        // No Docker API calls must occur — staging failure happens before pullImageBestEffort.
        verifyNoInteractions(docker);
    }

    @Test
    void execute_fourConcurrentVolumeCredentials_allReceiveDistinctPaths() throws Exception {
        Path credFile = Files.createTempFile("test-cred-concurrent-", ".txt");
        try {
            Files.writeString(credFile, "shared-credential");

            AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
            when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

            // Build a docker mock where createResponse.getId() returns distinct IDs per call.
            DockerClient docker = mock(DockerClient.class);
            when(docker.pullImageCmd(anyString())).thenThrow(new RuntimeException("mocked pull fail"));

            CreateContainerCmd createCmd =
                    mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
            CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
            when(createResponse.getId())
                    .thenReturn("id1")
                    .thenReturn("id2")
                    .thenReturn("id3")
                    .thenReturn("id4");
            when(createCmd.exec()).thenReturn(createResponse);
            when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

            StartContainerCmd startCmd = mock(StartContainerCmd.class);
            when(docker.startContainerCmd(anyString())).thenReturn(startCmd);

            SingleTenantDockerExecutor executor =
                    new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

            CredentialSpec cred = new CredentialSpec(credFile.toString(), "volume", "/workspace/creds/cred.txt", true);

            ExecutorService pool = Executors.newFixedThreadPool(4);
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(() -> {
                    executor.execute(paramsWithCredentials(UUID.randomUUID(), List.of(cred)));
                    return (String) null;
                }));
            }
            pool.shutdown();
            for (Future<String> f : futures) {
                f.get(); // rethrow any exceptions
            }

            // Capture all 4 HostConfig invocations after all threads finish.
            ArgumentCaptor<HostConfig> captor = ArgumentCaptor.forClass(HostConfig.class);
            verify(createCmd, times(4)).withHostConfig(captor.capture());
            List<String> paths = captor.getAllValues().stream()
                    .map(hc -> findBindByContainerPath(hc, "/workspace/creds/cred.txt")
                            .getPath())
                    .toList();

            assertThat(paths).hasSize(4);
            // All paths must be distinct.
            assertThat(paths.stream().distinct().count()).isEqualTo(4);
            // None must equal the original host file.
            paths.forEach(p -> assertThat(p).isNotEqualTo(credFile.toString()));
        } finally {
            Files.deleteIfExists(credFile);
        }
    }

    // -----------------------------------------------------------------------
    // DinD (Docker-in-Docker) tests
    // -----------------------------------------------------------------------

    private ExecutionParams paramsWithDockerEnabled(boolean enableDocker) {
        return new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://api-server:8080"),
                enableDocker,
                List.of(),
                null);
    }

    @Test
    void enableDockerTrue_doesNotMountHostSocket() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        // Use timeout=1 so health poll returns fast (mock is already "healthy" on first call)
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService, 1);

        ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        executor.execute(paramsWithDockerEnabled(true));

        // Capture all HostConfig arguments across both DinD and agent createContainerCmd calls
        verify(mocks.createCmd(), atLeast(1)).withHostConfig(hostConfigCaptor.capture());
        List<HostConfig> allConfigs = hostConfigCaptor.getAllValues();

        for (HostConfig cfg : allConfigs) {
            if (cfg.getBinds() != null) {
                for (Bind bind : cfg.getBinds()) {
                    assertThat(bind.getPath())
                            .as("Host Docker socket must not be mounted in DinD mode")
                            .isNotEqualTo("/var/run/docker.sock");
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void enableDockerTrue_setsDOCKER_HOST_envVar() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService, 1);

        ArgumentCaptor<List<String>> envCaptor = ArgumentCaptor.forClass((Class) List.class);
        executor.execute(paramsWithDockerEnabled(true));

        verify(mocks.createCmd(), atLeast(1)).withEnv(envCaptor.capture());
        List<List<String>> allEnvLists = envCaptor.getAllValues();

        boolean hasDindDockerHost =
                allEnvLists.stream().flatMap(List::stream).anyMatch(e -> e.startsWith("DOCKER_HOST=tcp://ck-dind-"));
        assertThat(hasDindDockerHost)
                .as("Agent must have DOCKER_HOST pointing to DinD sidecar")
                .isTrue();
    }

    @Test
    void enableDockerTrue_startsPrivilegedDindContainerFirst() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService, 1);

        executor.execute(paramsWithDockerEnabled(true));

        // DinD container must be created before the agent container
        InOrder inOrder = inOrder(mocks.docker());
        inOrder.verify(mocks.docker()).createContainerCmd("docker:29-dind");
        inOrder.verify(mocks.docker()).createContainerCmd("choruskube/agent:test");

        // DinD container must be privileged — it is the first HostConfig captured
        ArgumentCaptor<HostConfig> hostConfigCaptor = ArgumentCaptor.forClass(HostConfig.class);
        verify(mocks.createCmd(), atLeast(2)).withHostConfig(hostConfigCaptor.capture());
        HostConfig dindConfig = hostConfigCaptor.getAllValues().get(0);
        assertThat(dindConfig.getPrivileged())
                .as("DinD container must be privileged")
                .isTrue();
    }

    @Test
    void enableDockerTrue_pullsDindSidecarImageBeforeCreatingIt() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService, 1);

        executor.execute(paramsWithDockerEnabled(true));

        // The Engine API's /containers/create does NOT auto-pull (that is a `docker run`
        // CLI convenience), so the executor must pull the DinD sidecar image itself —
        // best-effort, before creating the container — exactly as it does for the agent
        // image. Otherwise a fresh daemon 404s with "No such image: docker:29-dind".
        InOrder inOrder = inOrder(mocks.docker());
        inOrder.verify(mocks.docker()).pullImageCmd("docker:29-dind");
        inOrder.verify(mocks.docker()).createContainerCmd("docker:29-dind");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enableDockerTrue_setsHasDindLabel() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService, 1);

        ArgumentCaptor<Map<String, String>> labelsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        executor.execute(paramsWithDockerEnabled(true));

        verify(mocks.createCmd(), atLeast(1)).withLabels(labelsCaptor.capture());
        List<Map<String, String>> allLabels = labelsCaptor.getAllValues();
        // Agent labels are the last withLabels call
        Map<String, String> agentLabels = allLabels.get(allLabels.size() - 1);
        assertThat(agentLabels).containsEntry("choruskube/has-dind", "true");
    }

    @Test
    void enableDockerFalse_doesNotStartDindContainer() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService, 1);

        executor.execute(paramsWithDockerEnabled(false));

        // No DinD: createVolumeCmd must never be called
        verify(mocks.docker(), never()).createVolumeCmd();
        // No DinD container image used
        verify(mocks.docker(), never()).createContainerCmd("docker:29-dind");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enableDockerFalse_doesNotSetHasDindLabel() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService, 1);

        ArgumentCaptor<Map<String, String>> labelsCaptor = ArgumentCaptor.forClass((Class) Map.class);
        executor.execute(paramsWithDockerEnabled(false));

        verify(mocks.createCmd(), atLeast(1)).withLabels(labelsCaptor.capture());
        // No label map should contain choruskube/has-dind=true
        boolean hasDindLabel =
                labelsCaptor.getAllValues().stream().anyMatch(m -> "true".equals(m.get("choruskube/has-dind")));
        assertThat(hasDindLabel)
                .as("has-dind label must not be set when enableDocker=false")
                .isFalse();
    }

    @Test
    void cleanup_withHasDindLabel_removesDindContainerAndVolume() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);

        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        UUID execId = UUID.fromString("12345678-0000-0000-0000-000000000000");
        String execIdShort = "12345678";

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("agent-container-id");
        when(container.getLabels())
                .thenReturn(Map.of("choruskube/exec-id", execId.toString(), "choruskube/has-dind", "true"));

        ListContainersCmd listCmd = mock(ListContainersCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(listCmd.exec()).thenReturn(List.of(container));
        when(docker.listContainersCmd()).thenReturn(listCmd);

        RemoveContainerCmd removeCmd =
                mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        RemoveVolumeCmd removeVolumeCmd = mock(RemoveVolumeCmd.class);
        when(docker.removeVolumeCmd(anyString())).thenReturn(removeVolumeCmd);

        executor.cleanup(execId);

        // Agent container removed
        verify(docker).removeContainerCmd("agent-container-id");
        // DinD container removed by name
        verify(docker).removeContainerCmd("ck-dind-" + execIdShort);
        // DinD volume removed
        verify(docker).removeVolumeCmd("ck-dind-data-" + execIdShort);
    }

    @Test
    void cleanup_withoutHasDindLabel_doesNotRemoveDindResources() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);

        DockerClient docker = mock(DockerClient.class);
        SingleTenantDockerExecutor executor = new SingleTenantDockerExecutor(testConfig(), docker, credentialService);

        UUID execId = UUID.fromString("abcdef01-0000-0000-0000-000000000000");

        Container container = mock(Container.class);
        when(container.getId()).thenReturn("agent-container-id");
        when(container.getLabels()).thenReturn(Map.of("choruskube/exec-id", execId.toString()));

        ListContainersCmd listCmd = mock(ListContainersCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(listCmd.exec()).thenReturn(List.of(container));
        when(docker.listContainersCmd()).thenReturn(listCmd);

        RemoveContainerCmd removeCmd =
                mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        executor.cleanup(execId);

        // Only the agent container should be removed, not any DinD resources
        verify(docker, times(1)).removeContainerCmd("agent-container-id");
        verify(docker, never()).removeVolumeCmd(anyString());
    }

    @Test
    void dindStartupFailure_cleansUpAndPropagates() {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerClient docker = mock(DockerClient.class);
        when(docker.pullImageCmd(anyString())).thenThrow(new RuntimeException("mocked pull fail"));

        // createContainerCmd returns a mock that yields an ID — needed for DinD container creation
        CreateContainerCmd createCmd =
                mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn("dind-container-id");
        when(createCmd.exec()).thenReturn(createResponse);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(anyString())).thenReturn(startCmd);

        // Volume commands
        CreateVolumeCmd createVolumeCmd =
                mock(CreateVolumeCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(createVolumeCmd.exec()).thenReturn(mock(CreateVolumeResponse.class));
        when(docker.createVolumeCmd()).thenReturn(createVolumeCmd);

        RemoveVolumeCmd removeVolumeCmd = mock(RemoveVolumeCmd.class);
        when(docker.removeVolumeCmd(anyString())).thenReturn(removeVolumeCmd);

        // DinD cleanup: removeContainerCmd must be stub-able for both agent and dind names
        RemoveContainerCmd removeCmd =
                mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        // Inspect always returns "starting" so the sidecar never becomes healthy
        HealthState unhealthyState = mock(HealthState.class);
        when(unhealthyState.getStatus()).thenReturn("starting");
        InspectContainerResponse.ContainerState containerState = mock(InspectContainerResponse.ContainerState.class);
        when(containerState.getHealth()).thenReturn(unhealthyState);
        InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
        when(inspectResponse.getState()).thenReturn(containerState);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);

        // Use timeout=2 to keep the test under 3 seconds
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), docker, credentialService, 2);

        assertThatThrownBy(() -> executor.execute(paramsWithDockerEnabled(true)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DinD sidecar did not become healthy");

        // DinD cleanup must have been attempted
        verify(docker, atLeastOnce()).removeContainerCmd(argThat(name -> name.startsWith("ck-dind-")));
        verify(docker, atLeastOnce()).removeVolumeCmd(argThat(name -> name.startsWith("ck-dind-data-")));
    }

    @Test
    void enableDockerTrue_postStartupFailure_cleansDindResourcesViaOuterCatch() {
        // Scenario: DinD sidecar starts and becomes healthy, but a subsequent step (credential
        // staging) fails. The outer catch block — not the DinD inner catch — must clean up
        // the already-started DinD container and volume.
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerClient docker = mock(DockerClient.class);
        when(docker.pullImageCmd(anyString())).thenThrow(new RuntimeException("mocked pull fail"));

        // DinD container creation succeeds and returns a container ID
        CreateContainerCmd createCmd =
                mock(CreateContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        CreateContainerResponse createResponse = mock(CreateContainerResponse.class);
        when(createResponse.getId()).thenReturn("dind-container-id");
        when(createCmd.exec()).thenReturn(createResponse);
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

        StartContainerCmd startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(anyString())).thenReturn(startCmd);

        // Named volume creation succeeds
        CreateVolumeCmd createVolumeCmd =
                mock(CreateVolumeCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(createVolumeCmd.exec()).thenReturn(mock(CreateVolumeResponse.class));
        when(docker.createVolumeCmd()).thenReturn(createVolumeCmd);

        // DinD health check: healthy immediately so waitForDindReady() returns without error
        HealthState healthState = mock(HealthState.class);
        when(healthState.getStatus()).thenReturn("healthy");
        InspectContainerResponse.ContainerState containerState = mock(InspectContainerResponse.ContainerState.class);
        when(containerState.getHealth()).thenReturn(healthState);
        InspectContainerResponse inspectResponse = mock(InspectContainerResponse.class);
        when(inspectResponse.getState()).thenReturn(containerState);
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(docker.inspectContainerCmd(anyString())).thenReturn(inspectCmd);

        // DinD cleanup stubs (outer catch will invoke these)
        RemoveContainerCmd removeCmd =
                mock(RemoveContainerCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);

        RemoveVolumeCmd removeVolumeCmd = mock(RemoveVolumeCmd.class);
        when(docker.removeVolumeCmd(anyString())).thenReturn(removeVolumeCmd);

        // Use timeout=1: DinD is already "healthy" on first poll so this has no effect on timing
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), docker, credentialService, 1);

        // A non-existent credential path causes stageCredential to throw IOException,
        // triggering the outer catch block (not the inner DinD catch).
        CredentialSpec missingCred = new CredentialSpec(
                "/nonexistent/cred-for-dind-test.json", "volume", "/workspace/creds/cred.json", false);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://api-server:8080"),
                true, // enableDocker=true — DinD starts successfully before credential staging fails
                List.of(missingCred),
                null);

        assertThatThrownBy(() -> executor.execute(params)).isInstanceOf(RuntimeException.class);

        // Outer catch must have cleaned up DinD container and volume even though DinD startup
        // succeeded (i.e. the cleanup was not triggered by the inner DinD catch, but by the
        // outer catch's unconditional DinD cleanup guard).
        verify(docker, atLeastOnce()).removeContainerCmd(argThat(name -> name.startsWith("ck-dind-")));
        verify(docker, atLeastOnce()).removeVolumeCmd(argThat(name -> name.startsWith("ck-dind-data-")));
    }

    // -----------------------------------------------------------------------
    // configJson pass-through — arbitrary keys (e.g. "effort") reach the mounted config.json
    // verbatim. SingleTenantDockerExecutor writes params.configJson() through unchanged via
    // Files.writeString — no production code change needed, this only proves the pass-through.
    // -----------------------------------------------------------------------

    @Test
    void execute_writesEffortIntoMountedConfigJsonVerbatim() throws IOException {
        AiCredentialResolver credentialService = mock(AiCredentialResolver.class);
        when(credentialService.resolveOauthToken(any())).thenReturn("test-oauth-token");

        DockerMocks mocks = mockDockerClient();
        SingleTenantDockerExecutor executor =
                new SingleTenantDockerExecutor(testConfig(), mocks.docker(), credentialService);

        ExecutionParams params = new ExecutionParams(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "choruskube/agent:test",
                Map.of("api_server_url", "http://api-server:8080", "effort", "ultracode"),
                false,
                List.of(),
                null);

        executor.execute(params);

        ArgumentCaptor<HostConfig> captor = ArgumentCaptor.forClass(HostConfig.class);
        verify(mocks.createCmd()).withHostConfig(captor.capture());
        HostConfig captured = captor.getValue();

        Bind configBind = findBindByContainerPath(captured, "/workspace/config.json");
        assertThat(configBind).isNotNull();

        String mountedConfig = Files.readString(Path.of(configBind.getPath()));
        JsonNode configNode = new ObjectMapper().readTree(mountedConfig);
        assertThat(configNode.get("effort").asText()).isEqualTo("ultracode");
    }

    // -----------------------------------------------------------------------
    // Test helpers
    // -----------------------------------------------------------------------

    /**
     * Finds the {@link Bind} in the given {@link HostConfig} whose container-side
     * volume path matches {@code containerPath}. Returns {@code null} if not found.
     */
    private static Bind findBindByContainerPath(HostConfig hostConfig, String containerPath) {
        if (hostConfig.getBinds() == null) return null;
        for (Bind bind : hostConfig.getBinds()) {
            if (containerPath.equals(bind.getVolume().getPath())) {
                return bind;
            }
        }
        return null;
    }
}
