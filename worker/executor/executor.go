// Package executor defines the contract a Worker uses to run one node execution as
// a workload, independent of what runs it (Kubernetes Job, Docker container, ...).
package executor

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"github.com/google/uuid"
)

// Executor runs and manages the lifecycle of node execution workloads.
type Executor interface {
	Execute(ctx context.Context, params ExecutionParams) (ExecutionResult, error)
	Cleanup(ctx context.Context, executionID uuid.UUID) error
	Terminate(ctx context.Context, executionID uuid.UUID) error
	GetLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error)
	ResolveJobSecretHash(ctx context.Context, executionID uuid.UUID) (string, error)
	HealthCheck(ctx context.Context) error
}

// ExecutionParams describes one node execution to run.
type ExecutionParams struct {
	RunID           uuid.UUID
	NodeExecutionID uuid.UUID
	NodeID          uuid.UUID

	Image       string
	Command     []string
	Environment map[string]string

	JobSecret   string
	Credentials NodeCredentials

	ConfigJSON  map[string]any
	CallbackURL string

	EnableDocker bool
	Identity     ExecutionIdentity
}

// NodeCredentials are the credentials injected into a node execution's workload.
type NodeCredentials struct {
	GitHubTokenURL   string
	ClaudeOAuthToken string
	Registry         *RegistryCredentials
}

// RegistryCredentials authenticate a workload's image pull against a container registry.
type RegistryCredentials struct {
	Host     string
	Username string
	Password string
}

// ExecutionIdentity is the identity a workload runs under.
type ExecutionIdentity struct {
	Namespace      string
	ServiceAccount string
}

// ExecutionResult is what Execute returns once a workload has been started.
type ExecutionResult struct {
	PodName       string
	JobSecretHash string
}

// GenerateJobSecret returns a new random per-execution secret, hex-encoded, and its
// SHA-256 hash. The caller passes secret to the workload and persists only hash —
// the workload later authenticates by presenting secret, verified against hash.
func GenerateJobSecret() (secret string, hash string, err error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", "", fmt.Errorf("generate job secret: %w", err)
	}
	secret = hex.EncodeToString(b)
	return secret, HashSecret(secret), nil
}

// HashSecret returns the SHA-256 hex digest of secret.
func HashSecret(secret string) string {
	h := sha256.Sum256([]byte(secret))
	return hex.EncodeToString(h[:])
}
