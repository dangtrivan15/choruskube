// Package executor defines the contract a Worker uses to run one node execution as
// a workload, independent of what runs it (Kubernetes Job, Docker container, ...).
package executor

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"time"

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
	// DindMemoryRequest overrides the dind sidecar's memory request (a Kubernetes quantity) for
	// this one execution. Empty keeps the template's request. The Docker executor ignores this.
	DindMemoryRequest string
	// MemoryCeilingExempt lets this execution's memory requests exceed the deployment's default
	// requests. False caps them there, so on a shared data plane one project cannot reserve a
	// whole node.
	MemoryCeilingExempt bool

	// Bounds carries the launching activity's own timeouts, which the executor stamps onto the
	// workload so it can reclaim the workload without the workflow's involvement.
	Bounds Bounds
}

// LogGrace is added to a node activity's own timeout to get a workload's deadline: the
// workflow's failure path still needs time to read a timed-out node's logs (three 30s attempts)
// before the workload that produced them can be reclaimed.
const LogGrace = 2 * time.Minute

// Bounds is the pair of timeouts a launching activity passes to an executor so it can bound a
// workload's lifetime on its own, with no workflow involvement: ActivityTimeout is the node
// activity's own StartToCloseTimeout, and HeartbeatTimeout is its HeartbeatTimeout.
type Bounds struct {
	ActivityTimeout  time.Duration
	HeartbeatTimeout time.Duration
}

// Deadline is how long a workload may run before the executor stops it: the activity's own
// timeout, plus enough grace for the workflow's failure path to read its logs first.
func (b Bounds) Deadline() time.Duration {
	return b.ActivityTimeout + LogGrace
}

// Retention is how long a finished workload is kept before the executor removes it: the
// configured setting, floored so it is never shorter than the node's own heartbeat timeout plus
// LogGrace — an agent that dies without reporting is only noticed when that timeout fires, and
// the failure path reads its logs then.
func (b Bounds) Retention(setting time.Duration) time.Duration {
	floor := b.HeartbeatTimeout + LogGrace
	if setting < floor {
		return floor
	}
	return setting
}

// RetentionEnv is the Worker environment variable naming how long a finished workload is kept
// before its executor removes it.
const RetentionEnv = "WORKLOAD_RETENTION"

// ParseRetention parses raw (typically os.Getenv(RetentionEnv)) as a Go duration: "" defaults to
// one hour, matching today's Job TTL. Any other unparseable, zero or negative value is an error,
// so a Worker misconfigured with one fails at startup rather than running with no retention.
func ParseRetention(raw string) (time.Duration, error) {
	if raw == "" {
		return time.Hour, nil
	}
	d, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("parse %s %q: %w", RetentionEnv, raw, err)
	}
	if d <= 0 {
		return 0, fmt.Errorf("%s must be positive, got %q", RetentionEnv, raw)
	}
	return d, nil
}

// AgentResources sets the agent container's CPU request and limit and its memory request; the
// K8s executor derives the memory limit from the memory request. Values are Kubernetes quantity
// strings (e.g. "200m", "1Gi"); an empty field falls back to the executor's corresponding Config
// default. The Docker executor ignores this.
type AgentResources struct {
	CPURequest    string
	MemoryRequest string
	CPULimit      string
}

// NodeCredentials are the credentials injected into a node execution's workload.
type NodeCredentials struct {
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
