package config

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

// --- envOrDefault ---

func TestEnvOrDefault_ReturnsEnvValue(t *testing.T) {
	t.Setenv("TEST_ENV_KEY", "custom-value")

	result := envOrDefault("TEST_ENV_KEY", "fallback")

	assert.Equal(t, "custom-value", result)
}

func TestEnvOrDefault_ReturnsFallbackWhenUnset(t *testing.T) {
	result := envOrDefault("UNSET_ENV_KEY_12345", "fallback")

	assert.Equal(t, "fallback", result)
}

func TestEnvOrDefault_ReturnsFallbackWhenEmpty(t *testing.T) {
	t.Setenv("TEST_EMPTY_KEY", "")

	result := envOrDefault("TEST_EMPTY_KEY", "fallback")

	assert.Equal(t, "fallback", result)
}

// --- envOrDefaultInt ---

func TestEnvOrDefaultInt_ReturnsEnvValue(t *testing.T) {
	t.Setenv("TEST_INT_KEY", "8080")

	result := envOrDefaultInt("TEST_INT_KEY", 3000)

	assert.Equal(t, 8080, result)
}

func TestEnvOrDefaultInt_ReturnsFallbackWhenUnset(t *testing.T) {
	result := envOrDefaultInt("UNSET_INT_KEY_12345", 3000)

	assert.Equal(t, 3000, result)
}

func TestEnvOrDefaultInt_ReturnsFallbackForNonNumeric(t *testing.T) {
	t.Setenv("TEST_BAD_INT", "not-a-number")

	result := envOrDefaultInt("TEST_BAD_INT", 3000)

	assert.Equal(t, 3000, result)
}

// --- Load ---

func TestLoad_ReturnsDefaults(t *testing.T) {
	for _, key := range []string{
		"API_SERVER_URL", "ORCHESTRATOR_SECRET",
		"TEMPORAL_ADDRESS", "TEMPORAL_NAMESPACE", "TEMPORAL_TASK_QUEUE",
		"HEALTH_PORT",
		"OBJECT_STORE_ENDPOINT", "OBJECT_STORE_BUCKET", "OBJECT_STORE_ACCESS_KEY", "OBJECT_STORE_SECRET_KEY",
	} {
		t.Setenv(key, "")
	}

	cfg := Load()

	assert.Equal(t, "http://localhost:8080", cfg.APIServerURL)
	assert.Equal(t, "", cfg.OrchestratorSecret)
	assert.Equal(t, "localhost:7233", cfg.Temporal.Address)
	assert.Equal(t, "choruskube", cfg.Temporal.Namespace)
	assert.Equal(t, "choruskube", cfg.Temporal.TaskQueue)
	assert.Equal(t, 8080, cfg.HealthPort)
	assert.Equal(t, "http://localhost:9000", cfg.ObjectStore.Endpoint)
	assert.Equal(t, "choruskube", cfg.ObjectStore.Bucket)
	assert.Equal(t, "", cfg.ObjectStore.AccessKey)
	assert.Equal(t, "", cfg.ObjectStore.SecretKey)
}

func TestLoad_RespectsEnvOverrides(t *testing.T) {
	t.Setenv("API_SERVER_URL", "http://custom:9999")
	t.Setenv("ORCHESTRATOR_SECRET", "my-secret-token")
	t.Setenv("TEMPORAL_ADDRESS", "temporal.example.com:7233")
	t.Setenv("HEALTH_PORT", "7070")

	cfg := Load()

	assert.Equal(t, "http://custom:9999", cfg.APIServerURL)
	assert.Equal(t, "my-secret-token", cfg.OrchestratorSecret)
	assert.Equal(t, "temporal.example.com:7233", cfg.Temporal.Address)
	assert.Equal(t, 7070, cfg.HealthPort)
}
