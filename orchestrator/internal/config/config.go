package config

import (
	"fmt"
	"os"
	"strconv"
)

type Config struct {
	APIServerURL       string
	OrchestratorSecret string // Shared secret for orchestrator → API server auth
	Temporal           TemporalConfig
	Callback           CallbackConfig
	ObjectStore        ObjectStoreConfig
}

type TemporalConfig struct {
	Address   string
	Namespace string
	TaskQueue string
}

type CallbackConfig struct {
	Port int
	URL  string // Full internal URL for callback (injected into config.json)
}

type ObjectStoreConfig struct {
	Endpoint  string
	Bucket    string
	AccessKey string
	SecretKey string
}

func Load() *Config {
	return &Config{
		APIServerURL:       envOrDefault("API_SERVER_URL", "http://localhost:8080"),
		OrchestratorSecret: envOrDefault("ORCHESTRATOR_SECRET", ""),
		Temporal: TemporalConfig{
			Address:   envOrDefault("TEMPORAL_ADDRESS", "localhost:7233"),
			Namespace: envOrDefault("TEMPORAL_NAMESPACE", "choruskube"),
			TaskQueue: envOrDefault("TEMPORAL_TASK_QUEUE", "choruskube"),
		},
		Callback: CallbackConfig{
			Port: envOrDefaultInt("CALLBACK_PORT", 9090),
			URL:  requireEnv("CALLBACK_URL"),
		},
		ObjectStore: ObjectStoreConfig{
			Endpoint:  envOrDefault("OBJECT_STORE_ENDPOINT", "http://localhost:9000"),
			Bucket:    envOrDefault("OBJECT_STORE_BUCKET", "choruskube"),
			AccessKey: envOrDefault("OBJECT_STORE_ACCESS_KEY", ""),
			SecretKey: envOrDefault("OBJECT_STORE_SECRET_KEY", ""),
		},
	}
}

func requireEnv(key string) string {
	v := os.Getenv(key)
	if v == "" {
		panic(fmt.Sprintf("required environment variable %s is not set", key))
	}
	return v
}

func envOrDefault(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

func envOrDefaultInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if i, err := strconv.Atoi(v); err == nil {
			return i
		}
	}
	return fallback
}
