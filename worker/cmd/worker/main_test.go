package main

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// envMap builds a getenv func from a plain map, for tests that don't want to mutate the real
// process environment via t.Setenv.
func envMap(vars map[string]string) func(string) string {
	return func(key string) string { return vars[key] }
}

func TestBuildExecutor_DefaultsToDocker(t *testing.T) {
	env := map[string]string{} // EXECUTOR_TYPE unset
	typ, err := executorTypeOf(env["EXECUTOR_TYPE"])
	require.NoError(t, err)
	assert.Equal(t, "docker", typ)
}

func TestBuildExecutor_RejectsUnknownType(t *testing.T) {
	_, err := executorTypeOf("swarm")
	require.Error(t, err) // must fail loudly, not silently fall back
}

func TestBuildExecutor_K8sSelected(t *testing.T) {
	typ, err := executorTypeOf("k8s")
	require.NoError(t, err)
	assert.Equal(t, "k8s", typ)
}

func TestCredentialSourceOf(t *testing.T) {
	for raw, want := range map[string]string{"control-plane": "control-plane", "local": "local"} {
		got, err := credentialSourceOf(raw)
		require.NoError(t, err, "%q", raw)
		assert.Equal(t, want, got)
	}
}

func TestCredentialSourceOf_RequiresAValue(t *testing.T) {
	_, err := credentialSourceOf("")
	require.Error(t, err, "an unset CREDENTIAL_SOURCE must fail startup, not silently become control-plane")
}

func TestCredentialSourceOf_RejectsAnythingElse(t *testing.T) {
	for _, raw := range []string{"vault", "Local", "control_plane", " local"} {
		_, err := credentialSourceOf(raw)
		require.Error(t, err, "a typo must fail startup, not silently fall back to the control plane: %q", raw)
	}
}

func TestK8sConfigFromEnv_RetentionFromEnv(t *testing.T) {
	cfg, err := k8sConfigFromEnv(envMap(map[string]string{"WORKLOAD_RETENTION": "5m"}))
	require.NoError(t, err)
	assert.Equal(t, 5*time.Minute, cfg.Retention)
}

func TestK8sConfigFromEnv_RetentionDefaultsToOneHour(t *testing.T) {
	cfg, err := k8sConfigFromEnv(envMap(nil))
	require.NoError(t, err)
	assert.Equal(t, time.Hour, cfg.Retention)
}

func TestK8sConfigFromEnv_InvalidRetentionIsAnError(t *testing.T) {
	_, err := k8sConfigFromEnv(envMap(map[string]string{"WORKLOAD_RETENTION": "soon"}))
	assert.Error(t, err)
}

func TestK8sConfigFromEnv_RejectsMemoryLimit(t *testing.T) {
	_, err := k8sConfigFromEnv(envMap(map[string]string{"K8S_AGENT_MEMORY_LIMIT": "4Gi"}))
	require.Error(t, err, "K8S_AGENT_MEMORY_LIMIT is no longer read and must fail startup, not be silently ignored")
}

func TestDockerConfigFromEnv_RetentionFromEnv(t *testing.T) {
	cfg, err := dockerConfigFromEnv(envMap(map[string]string{"WORKLOAD_RETENTION": "5m"}))
	require.NoError(t, err)
	assert.Equal(t, 5*time.Minute, cfg.Retention)
}

func TestDockerConfigFromEnv_RetentionDefaultsToOneHour(t *testing.T) {
	cfg, err := dockerConfigFromEnv(envMap(nil))
	require.NoError(t, err)
	assert.Equal(t, time.Hour, cfg.Retention)
}

func TestDockerConfigFromEnv_InvalidRetentionIsAnError(t *testing.T) {
	_, err := dockerConfigFromEnv(envMap(map[string]string{"WORKLOAD_RETENTION": "soon"}))
	assert.Error(t, err)
}

func TestDockerConfigFromEnv_StagingDirDefaultsToStablePath(t *testing.T) {
	cfg, err := dockerConfigFromEnv(envMap(nil))
	require.NoError(t, err)
	assert.Equal(t, "/tmp/choruskube-agent-staging", cfg.StagingDir)
}
