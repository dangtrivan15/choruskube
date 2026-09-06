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
//
// The teardown methods (Cleanup, Terminate, GetLogs, ResolveJobSecretHash) take only the
// executionID: an instance is bound to a single namespace at construction, so these address
// resources by their deterministic name within that one namespace, never via a cluster-wide LIST
// (and so never needing cluster-scoped RBAC). A multi-tenant deployment obtains a namespace-bound
// instance per org (KubernetesExecutor.WithNamespace); Docker has no namespaces at all.
type Executor interface {
	Execute(ctx context.Context, params ExecutionParams) (ExecutionResult, error)
	Cleanup(ctx context.Context, executionID uuid.UUID) error
	Terminate(ctx context.Context, executionID uuid.UUID) error
	GetLogs(ctx context.Context, executionID uuid.UUID, tailLines int) (string, error)
	ResolveJobSecretHash(ctx context.Context, executionID uuid.UUID) (string, error)
	HealthCheck(ctx context.Context) error
}

// CredentialConsumer is an optional capability an Executor implements when it makes its own
// calls to the API server. The Worker's credential is minted at registration and rotated by the
// renewal loop, so it does not exist when the Executor is constructed; the Worker hands over a
// getter that reads whatever is currently cached, and the Executor's own requests then carry the
// same live credential every other workload call does. A single-namespace Executor that resolves
// everything from static configuration makes no such calls and does not implement this -- the
// Worker skips it via a type assertion, so leaving it unimplemented is the correct default.
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
	Identity     ExecutionIdentity

	// RegistryMirror carries the registry-mirror/build-cache/dependency-proxy endpoints to
	// inject into a DinD-enabled workload, when this deployment provisions one. Nil means
	// none was resolved -- this package derives no such endpoint itself, since where it
	// lives (a per-org proxy, a shared mirror, nothing) is a deployment-specific choice made
	// upstream of ExecutionParams.
	RegistryMirror *RegistryMirror

	// AgentResources overrides the executor's default agent-container CPU/memory for this one
	// execution. Nil uses the deployment default (the K8s executor's Config). What a given node
	// should be sized at is a caller decision (resolved in prepare), not something this package
	// infers from node type -- it only applies what it is handed.
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

// RegistryMirror is the registry-mirror/build-cache/dependency-proxy endpoint set a DinD-enabled
// workload's init container and agent process route image pulls, build-cache traffic, and
// package-manager downloads through.
type RegistryMirror struct {
	Mirror       string // host:port a container runtime pulls images through
	BuildCache   string // host:port the build-cache push/pull path uses
	DepProxyBase string // base URL package-manager proxies (Go/npm) are rooted at
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
