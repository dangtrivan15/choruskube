package docker

import (
	"archive/tar"
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/docker/docker/api/types/build"
	"github.com/docker/docker/api/types/container"
	"github.com/docker/docker/api/types/image"
	"github.com/docker/docker/api/types/mount"
	dclient "github.com/docker/docker/client"
	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/worker/executor"
)

// --- Pure-logic unit tests (no Docker daemon required) ---

func TestBuildRegistryAuthConfigJSON(t *testing.T) {
	reg := &executor.RegistryCredentials{
		Host:     "registry.example.com",
		Username: "produser",
		Password: "prodpass",
	}
	raw, err := buildRegistryAuthConfigJSON(reg)
	require.NoError(t, err)

	var parsed struct {
		Auths map[string]struct {
			Username string `json:"username"`
			Password string `json:"password"`
			Auth     string `json:"auth"`
		} `json:"auths"`
	}
	require.NoError(t, json.Unmarshal(raw, &parsed))

	entry, ok := parsed.Auths["registry.example.com"]
	require.True(t, ok, "auths must key by registry host")
	assert.Equal(t, "produser", entry.Username)
	assert.Equal(t, "prodpass", entry.Password)

	decoded, err := base64.StdEncoding.DecodeString(entry.Auth)
	require.NoError(t, err)
	assert.Equal(t, "produser:prodpass", string(decoded))
}

func TestRegistryAuthHeader_MatchesHost(t *testing.T) {
	reg := &executor.RegistryCredentials{Host: "registry.example.com", Username: "u", Password: "p"}

	header, err := registryAuthHeader(reg, "registry.example.com/team/agent:latest")
	require.NoError(t, err)
	assert.NotEmpty(t, header)

	// Different registry host: credentials must not be attached to the wrong host's pull.
	header, err = registryAuthHeader(reg, "docker.io/library/alpine:latest")
	require.NoError(t, err)
	assert.Empty(t, header)
}

// --- Docker-daemon-backed tests ---

var (
	bindMountOnce   sync.Once
	bindMountWorks  bool
	bindMountReason string
)

// probeBindMount checks whether the Docker daemon can see files this process creates on disk.
// DinD setups where the daemon runs in a different mount namespace fail silently — the
// ContainerCreate call returns "bind source path does not exist" instead of a usable container.
func probeBindMount() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	cli, err := dclient.NewClientWithOpts(dclient.WithHost("unix:///var/run/docker.sock"), dclient.WithAPIVersionNegotiation())
	if err != nil {
		bindMountReason = fmt.Sprintf("docker client: %v", err)
		return
	}
	defer cli.Close()

	dir, err := os.MkdirTemp("", "ck-bind-probe-")
	if err != nil {
		bindMountReason = fmt.Sprintf("mktempdir: %v", err)
		return
	}
	defer os.RemoveAll(dir)

	probe := filepath.Join(dir, "probe")
	if err := os.WriteFile(probe, []byte("ok"), 0o644); err != nil {
		bindMountReason = fmt.Sprintf("write probe: %v", err)
		return
	}

	resp, err := cli.ContainerCreate(ctx, &container.Config{
		Image: "alpine:latest",
		Cmd:   []string{"cat", "/mnt/probe"},
	}, &container.HostConfig{
		Mounts: []mount.Mount{{Type: mount.TypeBind, Source: probe, Target: "/mnt/probe", ReadOnly: true}},
	}, nil, nil, "ck-bind-probe-"+uuid.New().String()[:8])
	if err != nil {
		bindMountReason = fmt.Sprintf("bind mount not visible to daemon: %v", err)
		return
	}
	_ = cli.ContainerRemove(ctx, resp.ID, container.RemoveOptions{Force: true})
	bindMountWorks = true
}

func skipUnlessBindMountWorks(t *testing.T) {
	t.Helper()
	if testing.Short() {
		t.Skip("requires Docker daemon")
	}
	bindMountOnce.Do(probeBindMount)
	if !bindMountWorks {
		t.Skipf("Docker bind mounts unavailable: %s", bindMountReason)
	}
}

// testBounds is an arbitrary positive Bounds for tests that don't exercise deadline/retention
// behavior directly -- Execute refuses a zero ActivityTimeout, so every fixture needs one.
var testBounds = executor.Bounds{ActivityTimeout: 10 * time.Minute, HeartbeatTimeout: time.Minute}

func newTestExecutor(t *testing.T) *DockerExecutor {
	t.Helper()
	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: t.TempDir(),
		Retention:  10 * time.Minute,
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)
	return exec
}

// New() runs client.FromEnv, which eagerly loads TLS certs when DOCKER_TLS_VERIFY is set in the
// ambient environment (some CI does, pointing at a docker-in-docker daemon whose certs this
// process can't read). These tests only check config defaulting, so clear it before constructing.
func withoutAmbientDockerTLS(t *testing.T) {
	t.Helper()
	t.Setenv("DOCKER_TLS_VERIFY", "")
	t.Setenv("DOCKER_CERT_PATH", "")
}

func TestNew_DindImage_DefaultsWhenUnset(t *testing.T) {
	withoutAmbientDockerTLS(t)
	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: t.TempDir(),
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)
	assert.Equal(t, defaultDindImage, exec.dindImage)
}

func TestNew_DindImage_UsesConfigOverride(t *testing.T) {
	withoutAmbientDockerTLS(t)
	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: t.TempDir(),
		DindImage:  "registry.example.com/choruskube-dind:latest",
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)
	assert.Equal(t, "registry.example.com/choruskube-dind:latest", exec.dindImage)
}

func TestDockerExecutor_Execute_CreatesContainer(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		Environment:     map[string]string{"TEST_VAR": "hello"},
		JobSecret:       "test-secret-123",
		ConfigJSON:      map[string]any{"run_id": uuid.New().String()},
		CallbackURL:     "http://localhost:9090/api/v1/callback",
	}

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	assert.NotEmpty(t, result.PodName)
	assert.NotEmpty(t, result.JobSecretHash)
	assert.Equal(t, executor.HashSecret(params.JobSecret), result.JobSecretHash)

	// The container must actually be running, with the config.json bind mount, JOB_SECRET,
	// and caller-supplied Environment all present -- silently dropping any of these would
	// strand the agent with no config.json or no way to authenticate its callback.
	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)

	inspect, err := exec.client.ContainerInspect(context.Background(), c.ID)
	require.NoError(t, err)
	assert.True(t, inspect.State.Running)
	assert.Contains(t, inspect.Config.Env, "JOB_SECRET=test-secret-123")
	assert.Contains(t, inspect.Config.Env, "TEST_VAR=hello")

	foundConfigMount := false
	for _, m := range inspect.Mounts {
		if m.Destination == "/workspace/config.json" {
			foundConfigMount = true
			assert.False(t, m.RW, "config.json mount must be read-only")
		}
	}
	assert.True(t, foundConfigMount, "expected a bind mount at /workspace/config.json")

	// Cleanup
	err = exec.Cleanup(context.Background(), params.NodeExecutionID)
	assert.NoError(t, err)

	c, err = exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	assert.Nil(t, c, "container must be gone after Cleanup")
}

func TestDockerExecutor_ResolveJobSecretHash(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	secret := "resolve-test-secret"
	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       secret,
		ConfigJSON:      map[string]any{},
		CallbackURL:     "http://localhost:9090/api/v1/callback",
	}

	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	hash, err := exec.ResolveJobSecretHash(context.Background(), uuid.Nil, params.NodeExecutionID)
	require.NoError(t, err)
	assert.Equal(t, executor.HashSecret(secret), hash)
}

func TestDockerExecutor_ResolveJobSecretHash_MissingContainerReturnsError(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	_, err := exec.ResolveJobSecretHash(context.Background(), uuid.Nil, uuid.New())
	assert.Error(t, err)
}

// The executor injects only the env the caller supplies via Environment and adds none of its
// own from node type -- a "script" node gets its caller-set vars and no auto-injected E2E_WORKERS.
func TestDockerExecutor_Execute_InjectsOnlyCallerEnv(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{"executor_type": "script"},
		Environment:     map[string]string{"CALLER_SET": "yes"},
	}
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)
	inspect, err := exec.client.ContainerInspect(context.Background(), c.ID)
	require.NoError(t, err)
	assert.Contains(t, inspect.Config.Env, "CALLER_SET=yes")
	for _, e := range inspect.Config.Env {
		assert.False(t, strings.HasPrefix(e, "E2E_WORKERS="), "executor must inject no E2E_WORKERS of its own")
	}
}

func TestDockerExecutor_Cleanup_IdempotentWhenContainerMissing(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	err := exec.Cleanup(context.Background(), uuid.New())
	assert.NoError(t, err, "cleanup of an execution with no container must be a no-op, not an error")
}

func TestDockerExecutor_Terminate_IdempotentWhenContainerMissing(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	err := exec.Terminate(context.Background(), uuid.New())
	assert.NoError(t, err, "terminate of an execution with no container must be a no-op, not an error")
}

func TestDockerExecutor_GetLogs_ReturnsContainerOutput(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sh", "-c", "echo hello-from-container; sleep 30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
	}
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	// Give the process a moment to emit its output before we ask for logs.
	require.Eventually(t, func() bool {
		logs, err := exec.GetLogs(context.Background(), params.NodeExecutionID, 100)
		return err == nil && strings.Contains(logs, "hello-from-container")
	}, 10*time.Second, 200*time.Millisecond)
}

func TestDockerExecutor_HealthCheck_Succeeds(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	assert.NoError(t, exec.HealthCheck(context.Background()))
}

func TestDockerExecutor_Execute_StagesRegistryAuthConfig(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		Credentials: executor.NodeCredentials{
			Registry: &executor.RegistryCredentials{
				Host:     "registry.example.com",
				Username: "regUser",
				Password: "regPass",
			},
		},
	}
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)
	inspect, err := exec.client.ContainerInspect(context.Background(), c.ID)
	require.NoError(t, err)

	assert.Contains(t, inspect.Config.Env, "DOCKER_CONFIG=/etc/regcred")

	var stagedHostPath string
	for _, m := range inspect.Mounts {
		if m.Destination == "/etc/regcred/config.json" {
			stagedHostPath = m.Source
			assert.False(t, m.RW)
		}
	}
	require.NotEmpty(t, stagedHostPath, "expected a bind mount at /etc/regcred/config.json")

	data, err := os.ReadFile(stagedHostPath)
	require.NoError(t, err)
	assert.Contains(t, string(data), "registry.example.com")
	assert.Contains(t, string(data), "regUser")
}

// TestDockerExecutor_StartDindSidecar_ImageOverride_UsesOverride guards the per-project custom
// dind image: a non-empty override must be the sidecar's image rather than d.dindImage. It calls
// startDindSidecar directly (not the full Execute -> waitForDindReady path) so the assertion does
// not depend on the override image actually running a Docker daemon.
func TestDockerExecutor_StartDindSidecar_ImageOverride_UsesOverride(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: t.TempDir(),
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)

	execIDShort := uuid.New().String()[:8]
	containerID, err := exec.startDindSidecar(context.Background(), execIDShort, "alpine:latest", map[string]string{labelAppKey: labelApp, labelExecID: execIDShort})
	require.NoError(t, err)
	defer func() {
		_ = exec.client.ContainerRemove(context.Background(), containerID, container.RemoveOptions{Force: true})
		_ = exec.client.VolumeRemove(context.Background(), dindVolumePrefix+execIDShort, true)
	}()

	inspect, err := exec.client.ContainerInspect(context.Background(), containerID)
	require.NoError(t, err)
	assert.Equal(t, "alpine:latest", inspect.Config.Image)
}

// TestDockerExecutor_StartDindSidecar_NoOverride_FallsBackToConfiguredDefault guards the
// empty-override default: an empty override must leave d.dindImage as the sidecar's image.
func TestDockerExecutor_StartDindSidecar_NoOverride_FallsBackToConfiguredDefault(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: t.TempDir(),
		// Configured to alpine so this test asserts the fallback plumbing, not a real dind pull.
		DindImage: "alpine:latest",
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)

	execIDShort := uuid.New().String()[:8]
	containerID, err := exec.startDindSidecar(context.Background(), execIDShort, "", map[string]string{labelAppKey: labelApp, labelExecID: execIDShort})
	require.NoError(t, err)
	defer func() {
		_ = exec.client.ContainerRemove(context.Background(), containerID, container.RemoveOptions{Force: true})
		_ = exec.client.VolumeRemove(context.Background(), dindVolumePrefix+execIDShort, true)
	}()

	inspect, err := exec.client.ContainerInspect(context.Background(), containerID)
	require.NoError(t, err)
	assert.Equal(t, "alpine:latest", inspect.Config.Image)
}

func TestDockerExecutor_Execute_WithDinD_SetsDockerHostEnv(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec, err := New(Config{
		Host:             "unix:///var/run/docker.sock",
		Network:          "bridge",
		StagingDir:       t.TempDir(),
		DindReadyTimeout: 60 * time.Second,
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		EnableDocker:    true,
	}

	result, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	assert.NotEmpty(t, result.PodName)

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)
	inspect, err := exec.client.ContainerInspect(context.Background(), c.ID)
	require.NoError(t, err)

	execIDShort := params.NodeExecutionID.String()[:8]
	wantDockerHost := "DOCKER_HOST=tcp://ck-dind-" + execIDShort + ":2375"
	assert.Contains(t, inspect.Config.Env, wantDockerHost)
	assert.Equal(t, "true", c.Labels[labelHasDind])

	// The DinD sidecar itself must be up and healthy.
	dindName := dindNamePrefix + execIDShort
	dindInspect, err := exec.client.ContainerInspect(context.Background(), dindName)
	require.NoError(t, err)
	assert.True(t, dindInspect.State.Running)

	// Cleanup must remove both the agent container and the DinD sidecar + its volume.
	require.NoError(t, exec.Cleanup(context.Background(), params.NodeExecutionID))

	_, err = exec.client.ContainerInspect(context.Background(), dindName)
	assert.Error(t, err, "DinD sidecar must be removed by Cleanup")

	_, err = exec.client.VolumeInspect(context.Background(), dindVolumePrefix+execIDShort)
	assert.Error(t, err, "DinD data volume must be removed by Cleanup")
}

// buildNonRootAgentImage builds an image shaped like the real agent's: /workspace owned by a
// uid-1000 user that the container runs as.
func buildNonRootAgentImage(t *testing.T, cli *dclient.Client) string {
	t.Helper()
	tag := "ck-test-nonroot-agent:" + uuid.New().String()[:8]
	dockerfile := "FROM alpine:latest\n" +
		"RUN adduser -D -u 1000 agent && mkdir -p /workspace && chown agent:agent /workspace\n" +
		"USER agent\n"

	var buf bytes.Buffer
	tw := tar.NewWriter(&buf)
	require.NoError(t, tw.WriteHeader(&tar.Header{Name: "Dockerfile", Mode: 0o644, Size: int64(len(dockerfile))}))
	_, err := tw.Write([]byte(dockerfile))
	require.NoError(t, err)
	require.NoError(t, tw.Close())

	resp, err := cli.ImageBuild(context.Background(), &buf, build.ImageBuildOptions{Tags: []string{tag}, Remove: true})
	require.NoError(t, err)
	out, err := io.ReadAll(resp.Body)
	resp.Body.Close()
	require.NoError(t, err)
	require.NotContains(t, string(out), `"error"`, "image build failed: %s", out)
	t.Cleanup(func() {
		_, _ = cli.ImageRemove(context.Background(), tag, image.RemoveOptions{Force: true})
	})
	return tag
}

// containerHasFile reports whether path exists inside the running container.
func containerHasFile(t *testing.T, cli *dclient.Client, containerName, path string) bool {
	t.Helper()
	ctx := context.Background()
	created, err := cli.ContainerExecCreate(ctx, containerName, container.ExecOptions{Cmd: []string{"test", "-f", path}})
	require.NoError(t, err)
	require.NoError(t, cli.ContainerExecStart(ctx, created.ID, container.ExecStartOptions{}))
	for {
		inspect, err := cli.ContainerExecInspect(ctx, created.ID)
		require.NoError(t, err)
		if !inspect.Running {
			return inspect.ExitCode == 0
		}
		time.Sleep(100 * time.Millisecond)
	}
}

// dockerd resolves bind-mount sources on its own filesystem, so the sidecar must see what the agent
// writes under /workspace — written by the non-root user the real agent image runs as.
func TestDockerExecutor_Execute_WithDinD_SidecarSeesNonRootAgentWorkspace(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec, err := New(Config{
		Host:             "unix:///var/run/docker.sock",
		Network:          "bridge",
		StagingDir:       t.TempDir(),
		DindReadyTimeout: 60 * time.Second,
	})
	require.NoError(t, err)
	agentImage := buildNonRootAgentImage(t, exec.client)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           agentImage,
		Command:         []string{"sh", "-c", "mkdir -p /workspace/repo && echo hi > /workspace/repo/probe && sleep 60"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		EnableDocker:    true,
	}
	_, err = exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	execIDShort := params.NodeExecutionID.String()[:8]
	dindName := dindNamePrefix + execIDShort
	assert.Eventually(t, func() bool {
		return containerHasFile(t, exec.client, dindName, "/workspace/repo/probe")
	}, 20*time.Second, 250*time.Millisecond, "the sidecar must see the file the agent wrote under /workspace")

	require.NoError(t, exec.Cleanup(context.Background(), params.NodeExecutionID))
	_, err = exec.client.VolumeInspect(context.Background(), workspaceVolumePrefix+execIDShort)
	assert.Error(t, err, "the shared workspace volume must be removed by Cleanup")
}

func TestDockerExecutor_Execute_WritesConfigJSONToStagingDir(t *testing.T) {
	skipUnlessBindMountWorks(t)

	stagingDir := t.TempDir()
	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: stagingDir,
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		Bounds:          testBounds,
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{"run_id": "abc-123"},
	}
	_, err = exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	// The staged config.json must live under the configured StagingDir (required for
	// Docker-out-of-Docker: the bind source must resolve on the host daemon, not just
	// inside this process's own filesystem view).
	entries, err := filepath.Glob(filepath.Join(stagingDir, "ck-config-*", "config.json"))
	require.NoError(t, err)
	require.Len(t, entries, 1)

	data, err := os.ReadFile(entries[0])
	require.NoError(t, err)
	assert.Contains(t, string(data), "abc-123")

	// The agent image runs as a non-root user; config.json is bind-mounted read-only into it,
	// so it must stay group/other-readable or the entrypoint's jq reads fail and the agent dies
	// before its callback (its private staging dir is what actually restricts host-side access).
	info, err := os.Stat(entries[0])
	require.NoError(t, err)
	assert.NotZero(t, info.Mode().Perm()&0o044, "config.json must be readable by the non-root agent user")
}

// --- Workload-lifetime bounds: labels, cleanup independent of the agent container, janitor ---

// waitForState polls until executionID's container reaches want ("running" or "exited"), or
// fails the test after timeout.
func waitForState(t *testing.T, exec *DockerExecutor, executionID uuid.UUID, want string, timeout time.Duration) *container.Summary {
	t.Helper()
	var last *container.Summary
	require.Eventually(t, func() bool {
		c, err := exec.findContainer(context.Background(), executionID)
		if err != nil || c == nil {
			return false
		}
		last = c
		return c.State == want
	}, timeout, 100*time.Millisecond, "container never reached state %q", want)
	return last
}

func TestDockerExecutor_Execute_LabelsEveryObject(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec, err := New(Config{
		Host:             "unix:///var/run/docker.sock",
		Network:          "bridge",
		StagingDir:       t.TempDir(),
		DindReadyTimeout: 60 * time.Second,
		Retention:        10 * time.Minute,
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		EnableDocker:    true,
		Bounds:          executor.Bounds{ActivityTimeout: 5 * time.Minute, HeartbeatTimeout: time.Minute},
	}
	before := time.Now()
	_, err = exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	execIDShort := params.NodeExecutionID.String()[:8]

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)

	wantDeadline := strconv.FormatInt(before.Add(5*time.Minute+executor.LogGrace).Unix(), 10)
	wantRetention := strconv.FormatInt(int64(10*time.Minute/time.Second), 10)

	assertBoundsLabels := func(t *testing.T, labels map[string]string) {
		t.Helper()
		assert.Equal(t, labelApp, labels[labelAppKey])
		assert.Equal(t, params.RunID.String(), labels[labelRunID])
		assert.Equal(t, params.NodeExecutionID.String(), labels[labelExecID])
		assert.NotEmpty(t, labels[labelTmpDir])
		// Allow a couple of seconds of slack either side of before's own snapshot.
		gotDeadline, err := strconv.ParseInt(labels[labelDeadline], 10, 64)
		require.NoError(t, err)
		wantDeadlineInt, _ := strconv.ParseInt(wantDeadline, 10, 64)
		assert.InDelta(t, wantDeadlineInt, gotDeadline, 5)
		assert.Equal(t, wantRetention, labels[labelRetention])
	}

	assertBoundsLabels(t, c.Labels)
	assert.Equal(t, "true", c.Labels[labelHasDind])

	dindName := dindNamePrefix + execIDShort
	dindInspect, err := exec.client.ContainerInspect(context.Background(), dindName)
	require.NoError(t, err)
	assertBoundsLabels(t, dindInspect.Config.Labels)
	assert.Equal(t, "true", dindInspect.Config.Labels[labelDind])

	dindVol, err := exec.client.VolumeInspect(context.Background(), dindVolumePrefix+execIDShort)
	require.NoError(t, err)
	assertBoundsLabels(t, dindVol.Labels)

	workspaceVol, err := exec.client.VolumeInspect(context.Background(), workspaceVolumePrefix+execIDShort)
	require.NoError(t, err)
	assertBoundsLabels(t, workspaceVol.Labels)
}

func TestDockerExecutor_Execute_RetentionFloorsAtHeartbeatPlusGrace(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: t.TempDir(),
		// Below the 15m heartbeat timeout + LogGrace floor.
		Retention: time.Minute,
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		Bounds:          executor.Bounds{ActivityTimeout: 5 * time.Minute, HeartbeatTimeout: 15 * time.Minute},
	}
	_, err = exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)
	assert.Equal(t, strconv.FormatInt(int64((15*time.Minute+executor.LogGrace)/time.Second), 10), c.Labels[labelRetention],
		"a configured retention below the heartbeat timeout plus grace must be stamped as that floor")
}

func TestDockerExecutor_Execute_RefusesZeroActivityTimeout(t *testing.T) {
	skipUnlessBindMountWorks(t)

	stagingDir := t.TempDir()
	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: stagingDir,
	})
	require.NoError(t, err)
	t.Cleanup(exec.Close)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
	}
	_, err = exec.Execute(context.Background(), params)
	require.Error(t, err)

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	assert.Nil(t, c, "a refused launch must create no container")

	entries, err := filepath.Glob(filepath.Join(stagingDir, "ck-config-*"))
	require.NoError(t, err)
	assert.Empty(t, entries, "a refused launch must create no staging dir")
}

func TestDockerExecutor_Cleanup_RemovesEverythingWhenAgentContainerAlreadyGone(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "30"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		EnableDocker:    true,
		Bounds:          testBounds,
	}
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	execIDShort := params.NodeExecutionID.String()[:8]

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)
	tmpDir := c.Labels[labelTmpDir]
	require.NotEmpty(t, tmpDir)

	// Remove only the agent container directly, as if a prior Cleanup call partially succeeded
	// or the container was reaped by something else -- Cleanup must not stop at a missing agent.
	require.NoError(t, exec.client.ContainerRemove(context.Background(), c.ID, container.RemoveOptions{Force: true}))

	require.NoError(t, exec.Cleanup(context.Background(), params.NodeExecutionID))

	_, err = exec.client.ContainerInspect(context.Background(), dindNamePrefix+execIDShort)
	assert.Error(t, err, "the sidecar must still be removed")
	_, err = exec.client.VolumeInspect(context.Background(), dindVolumePrefix+execIDShort)
	assert.Error(t, err, "the DinD data volume must still be removed")
	_, err = exec.client.VolumeInspect(context.Background(), workspaceVolumePrefix+execIDShort)
	assert.Error(t, err, "the workspace volume must still be removed")
	_, statErr := os.Stat(tmpDir)
	assert.True(t, os.IsNotExist(statErr), "the staging dir must still be removed")
}

func TestDockerExecutor_Sweep_RemovesExitedContainerPastRetention(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sh", "-c", "exit 0"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		EnableDocker:    true,
		Bounds:          testBounds,
	}
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	waitForState(t, exec, params.NodeExecutionID, container.StateExited, 15*time.Second)

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c)
	tmpDir := c.Labels[labelTmpDir]
	execIDShort := params.NodeExecutionID.String()[:8]

	// now is far enough past the container's actual exit for even a generous retention to have
	// elapsed -- sweep need not wait on a real clock to observe that.
	exec.sweep(context.Background(), time.Now().Add(time.Hour))

	c, err = exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	assert.Nil(t, c, "the exited agent container must be swept once past its retention")
	_, err = exec.client.VolumeInspect(context.Background(), dindVolumePrefix+execIDShort)
	assert.Error(t, err, "its DinD volume must be swept too")
	_, err = exec.client.VolumeInspect(context.Background(), workspaceVolumePrefix+execIDShort)
	assert.Error(t, err, "its workspace volume must be swept too")
	_, statErr := os.Stat(tmpDir)
	assert.True(t, os.IsNotExist(statErr), "its staging dir must be swept too")
}

func TestDockerExecutor_Sweep_StopsAndRemovesRunningContainerPastDeadline(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "300"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		Bounds:          testBounds,
	}
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)

	waitForState(t, exec, params.NodeExecutionID, container.StateRunning, 15*time.Second)

	// Sweeping with now still inside the deadline must leave a running container alone.
	exec.sweep(context.Background(), time.Now())
	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c, "a running container inside its deadline must be left alone")

	// now far past the deadline: the janitor must stop it, then remove it.
	exec.sweep(context.Background(), time.Now().Add(time.Hour))
	require.Eventually(t, func() bool {
		c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
		return err == nil && c == nil
	}, 35*time.Second, 500*time.Millisecond, "a running container past its deadline must be stopped then removed")
}

func TestDockerExecutor_Sweep_RemovesSidecarOfExitedAgentWithinRetention_KeepsAgent(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sh", "-c", "exit 0"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		EnableDocker:    true,
		// A generous retention, so "now" (actual current time) stays well inside it.
		Bounds: executor.Bounds{ActivityTimeout: 5 * time.Minute, HeartbeatTimeout: 5 * time.Minute},
	}
	_, err := exec.Execute(context.Background(), params)
	require.NoError(t, err)
	defer exec.Cleanup(context.Background(), params.NodeExecutionID)

	waitForState(t, exec, params.NodeExecutionID, container.StateExited, 15*time.Second)
	execIDShort := params.NodeExecutionID.String()[:8]

	exec.sweep(context.Background(), time.Now())

	c, err := exec.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	assert.NotNil(t, c, "the exited agent container must be kept while inside retention")

	_, err = exec.client.ContainerInspect(context.Background(), dindNamePrefix+execIDShort)
	assert.Error(t, err, "the sidecar of an exited agent must be removed even inside retention")
}

func TestDockerExecutor_Sweep_LeavesSidecarAloneDuringLaunchInProgress(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	execIDShort := uuid.New().String()[:8]
	deadline := time.Now().Add(time.Hour)
	labels := map[string]string{
		labelAppKey:    labelApp,
		labelRunID:     uuid.New().String(),
		labelExecID:    uuid.New().String(),
		labelTmpDir:    exec.stagingBase(),
		labelDeadline:  strconv.FormatInt(deadline.Unix(), 10),
		labelRetention: "60",
	}
	containerID, err := exec.startDindSidecar(context.Background(), execIDShort, "alpine:latest", labels)
	require.NoError(t, err)
	t.Cleanup(func() {
		_ = exec.client.ContainerRemove(context.Background(), containerID, container.RemoveOptions{Force: true})
		_ = exec.cleanupDindResources(context.Background(), execIDShort)
	})

	// now still inside the launch's own deadline: a sidecar created ahead of its (not yet
	// created) agent container must not be swept.
	exec.sweep(context.Background(), time.Now())

	_, err = exec.client.ContainerInspect(context.Background(), containerID)
	assert.NoError(t, err, "a sidecar from a launch still in progress must be left alone")
}

func TestDockerExecutor_Sweep_LeavesWorkloadOutsideOwnStagingBaseAlone(t *testing.T) {
	skipUnlessBindMountWorks(t)

	owner := newTestExecutor(t)
	other := newTestExecutor(t) // a different staging base

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sh", "-c", "exit 0"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		Bounds:          testBounds,
	}
	_, err := owner.Execute(context.Background(), params)
	require.NoError(t, err)
	defer owner.Cleanup(context.Background(), params.NodeExecutionID)

	waitForState(t, owner, params.NodeExecutionID, container.StateExited, 15*time.Second)

	// other's own staging base does not contain owner's workload's tmp-dir, so it must not act
	// on it even when "now" is far enough past for the rule to otherwise fire.
	other.sweep(context.Background(), time.Now().Add(time.Hour))

	c, err := owner.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	assert.NotNil(t, c, "a workload outside the sweeping executor's own staging base must be left alone")
}

func TestDockerExecutor_Sweep_NeverTouchesObjectsWithoutTheAgentLabel(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	name := "ck-sweep-unrelated-" + uuid.New().String()[:8]
	resp, err := exec.client.ContainerCreate(context.Background(),
		&container.Config{Image: "alpine:latest", Cmd: []string{"sleep", "30"}},
		&container.HostConfig{}, nil, nil, name)
	require.NoError(t, err)
	require.NoError(t, exec.client.ContainerStart(context.Background(), resp.ID, container.StartOptions{}))
	t.Cleanup(func() {
		_ = exec.client.ContainerRemove(context.Background(), resp.ID, container.RemoveOptions{Force: true})
	})

	exec.sweep(context.Background(), time.Now().Add(time.Hour))

	inspect, err := exec.client.ContainerInspect(context.Background(), resp.ID)
	require.NoError(t, err)
	assert.True(t, inspect.State.Running, "a container without the agent label must never be touched by the janitor")
}

func TestDockerExecutor_Sweep_AfterRestart_ActsOnThePriorExecutorsLabels(t *testing.T) {
	skipUnlessBindMountWorks(t)

	stagingDir := t.TempDir()
	execA, err := New(Config{Host: "unix:///var/run/docker.sock", Network: "bridge", StagingDir: stagingDir, Retention: 10 * time.Minute})
	require.NoError(t, err)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sh", "-c", "exit 0"},
		JobSecret:       "sec",
		ConfigJSON:      map[string]any{},
		Bounds:          testBounds,
	}
	_, err = execA.Execute(context.Background(), params)
	require.NoError(t, err)
	waitForState(t, execA, params.NodeExecutionID, container.StateExited, 15*time.Second)

	// Simulates a Worker restart: the first executor's process is gone, with no in-memory
	// state carried over -- a second executor over the same staging dir is all the next
	// process has.
	execA.Close()

	execB, err := New(Config{Host: "unix:///var/run/docker.sock", Network: "bridge", StagingDir: stagingDir, Retention: 10 * time.Minute})
	require.NoError(t, err)
	t.Cleanup(execB.Close)

	execB.sweep(context.Background(), time.Now().Add(time.Hour))

	c, err := execB.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	assert.Nil(t, c, "a second executor over the same staging dir must sweep the first executor's workload by its labels")
}

func TestDockerExecutor_RestartedWorker_StillDeliversAndLeavesRunningContainerAlone(t *testing.T) {
	skipUnlessBindMountWorks(t)

	stagingDir := t.TempDir()
	execA, err := New(Config{Host: "unix:///var/run/docker.sock", Network: "bridge", StagingDir: stagingDir, Retention: 10 * time.Minute})
	require.NoError(t, err)

	secret := "restart-test-secret"
	params := executor.ExecutionParams{
		RunID:           uuid.New(),
		NodeExecutionID: uuid.New(),
		NodeID:          uuid.New(),
		Image:           "alpine:latest",
		Command:         []string{"sleep", "60"},
		JobSecret:       secret,
		ConfigJSON:      map[string]any{},
		Bounds:          testBounds,
	}
	_, err = execA.Execute(context.Background(), params)
	require.NoError(t, err)
	waitForState(t, execA, params.NodeExecutionID, container.StateRunning, 15*time.Second)
	execA.Close()

	execB, err := New(Config{Host: "unix:///var/run/docker.sock", Network: "bridge", StagingDir: stagingDir, Retention: 10 * time.Minute})
	require.NoError(t, err)
	t.Cleanup(execB.Close)
	defer execB.Cleanup(context.Background(), params.NodeExecutionID)

	hash, err := execB.ResolveJobSecretHash(context.Background(), uuid.Nil, params.NodeExecutionID)
	require.NoError(t, err)
	assert.Equal(t, executor.HashSecret(secret), hash)

	execB.sweep(context.Background(), time.Now())

	c, err := execB.findContainer(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	require.NotNil(t, c, "a running container still inside its deadline must survive the restarted Worker's sweep")
	assert.Equal(t, container.StateRunning, c.State)
}
