package docker

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/docker/docker/api/types/container"
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

func newTestExecutor(t *testing.T) *DockerExecutor {
	t.Helper()
	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: t.TempDir(),
	})
	require.NoError(t, err)
	return exec
}

func TestDockerExecutor_Execute_CreatesContainer(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
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

	hash, err := exec.ResolveJobSecretHash(context.Background(), params.NodeExecutionID)
	require.NoError(t, err)
	assert.Equal(t, executor.HashSecret(secret), hash)
}

func TestDockerExecutor_ResolveJobSecretHash_MissingContainerReturnsError(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	_, err := exec.ResolveJobSecretHash(context.Background(), uuid.New())
	assert.Error(t, err)
}

// The executor injects only the env the caller supplies via Environment and adds none of its
// own from node type -- a "script" node gets its caller-set vars and no auto-injected E2E_WORKERS.
func TestDockerExecutor_Execute_InjectsOnlyCallerEnv(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec := newTestExecutor(t)
	params := executor.ExecutionParams{
		RunID:           uuid.New(),
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

func TestDockerExecutor_Execute_WithDinD_SetsDockerHostEnv(t *testing.T) {
	skipUnlessBindMountWorks(t)

	exec, err := New(Config{
		Host:             "unix:///var/run/docker.sock",
		Network:          "bridge",
		StagingDir:       t.TempDir(),
		DindReadyTimeout: 60 * time.Second,
	})
	require.NoError(t, err)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
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

func TestDockerExecutor_Execute_WritesConfigJSONToStagingDir(t *testing.T) {
	skipUnlessBindMountWorks(t)

	stagingDir := t.TempDir()
	exec, err := New(Config{
		Host:       "unix:///var/run/docker.sock",
		Network:    "bridge",
		StagingDir: stagingDir,
	})
	require.NoError(t, err)

	params := executor.ExecutionParams{
		RunID:           uuid.New(),
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
