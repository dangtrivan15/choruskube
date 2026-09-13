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

// Executor runs and manages the lifecycle of node execution workloads. Seam rationale:
// docs/decisions/2026-09-06---02-worker-placement-and-executor-seams.md.
//
// Cleanup, Terminate, and GetLogs take only the executionID: an instance is namespace-bound at
// construction, so they address resources by deterministic name, never a cluster-wide LIST.
//
// ResolveJobSecretHash additionally takes the runID because a Worker calls it from its HTTP
// callback server, where no Temporal activity context exists to derive the run; a multi-tenant
// overlay needs the runID to resolve the namespace, and a single-namespace instance ignores it.
type Executor interface {
	Execute(ctx context.Context, params ExecutionParams) (ExecutionResult, error)
	Cleanup(ctx context.Context, executionID uuid.UUID) error
	Terminate(ctx context.Context, executionID uuid.UUID) error
	GetLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error)
	ResolveJobSecretHash(ctx context.Context, runID, executionID uuid.UUID) (string, error)
	HealthCheck(ctx context.Context) error
}

// CredentialConsumer is an optional capability an Executor implements when it makes its own calls
// to the API server. The Worker's credential is minted at registration and rotated, so it does not
// exist at construction; the getter reads whatever is currently cached. An Executor that makes no
// such calls leaves this unimplemented, and the Worker skips it via a type assertion.
type CredentialConsumer interface {
	SetAPIServerCredential(get func() string)
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
	// DindImage overrides the dind sidecar image for this launch (a per-project custom
	// image). Empty means use the executor's configured default.
	DindImage string
	Identity  ExecutionIdentity

	// AgentResources overrides the executor's default agent-container CPU/memory for this one
	// execution. Nil uses the deployment default (the K8s executor's Config).
	AgentResources *AgentResources
}

// AgentResources sets the agent container's CPU/memory requests and limits. Values are
// Kubernetes quantity strings (e.g. "200m", "1Gi"); an empty field falls back to the
// executor's corresponding Config default. The Docker executor ignores this.
type AgentResources struct {
	CPURequest    string
	MemoryRequest string
	CPULimit      string
	MemoryLimit   string
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

// ExecutionIdentity is the identity a workload runs under. The namespace a workload launches
// into is a property of the executor instance (bound at construction), not of a single call, so
// it is not carried here.
type ExecutionIdentity struct {
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
