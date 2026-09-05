// Package callback serves the HTTP endpoints agent pods call back to: result completion and
// heartbeat. It replaces the orchestrator's callback server (internal/callback) for pods the
// Worker itself launched — verification reads an in-memory hash cache the Worker populated at
// Execute() time instead of asking the api-server on every request.
package callback

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/worker/executor"
)

// HashCache holds the job-secret hash for each execution the Worker is currently serving,
// keyed by execution ID. The activity populates it via Put when it calls Execute(); the callback
// and heartbeat handlers read it via Get to verify a bearer token without a network call.
type HashCache struct {
	mu    sync.RWMutex
	store map[uuid.UUID]string
}

// NewHashCache returns an empty HashCache.
func NewHashCache() *HashCache {
	return &HashCache{store: make(map[uuid.UUID]string)}
}

// Put records the job-secret hash for executionID, overwriting any existing entry.
func (c *HashCache) Put(executionID uuid.UUID, hash string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.store[executionID] = hash
}

// Get returns the cached hash for executionID, and whether one was found.
func (c *HashCache) Get(executionID uuid.UUID) (string, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	h, ok := c.store[executionID]
	return h, ok
}

// Remove drops the cached entry for executionID.
func (c *HashCache) Remove(executionID uuid.UUID) {
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.store, executionID)
}

// SecretHashResolver recovers a job-secret hash the cache doesn't have — the case where the
// Worker restarted after Execute() ran and lost its in-memory cache. executor.Executor satisfies
// this with ResolveJobSecretHash; the handler only needs that one method. namespace scopes the
// resolver's lookup to the execution's own namespace (empty in a single-tenant deployment);
// the caller resolves it and passes it, since recovery here has no other path back to it.
type SecretHashResolver interface {
	ResolveJobSecretHash(ctx context.Context, namespace string, executionID uuid.UUID) (string, error)
}

// executor.Executor must keep satisfying this narrower interface — Run() passes it directly as
// the resolver — so a signature drift there fails here at compile time, not at the Run() call site.
var _ SecretHashResolver = executor.Executor(nil)

// maxParkDuration is the longest quota park the Worker will schedule, matching the agent's own
// limit. A resume_at beyond this degrades to the node's existing failure path rather than an
// unbounded wait.
const maxParkDuration = 6 * time.Hour

// CompletionRequest is the agent-reported outcome of one node execution, as the callback handler
// hands it to an ActivityCompleter.
type CompletionRequest struct {
	NodeExecutionID uuid.UUID
	RunID           uuid.UUID
	Status          string
	Result          string
	ErrorMessage    string
	// ArtifactRefs is passed through verbatim as raw JSON -- entrypoint.sh always sends an
	// object (e.g. {"output": "runs/.../out/"}, or {} when the node produced nothing), never
	// an array, so a completer must not assume either shape and decode it itself.
	ArtifactRefs        json.RawMessage
	SessionID           string
	ResumeAt            time.Time
	SessionArtifactPath string
}

// ActivityCompleter resolves the Temporal activity a node execution is blocked on. The concrete
// implementation (wiring CompleteActivityByID against a Temporal client) is supplied by the
// caller — this package only depends on the interface, so it can be unit tested with a mock.
type ActivityCompleter interface {
	Complete(ctx context.Context, req CompletionRequest) error
	Fail(ctx context.Context, executionID uuid.UUID, reason error) error
}

// StatusClient reads and writes node-execution state on the API server, for the finalized check
// and best-effort DB writes the handler performs around the Temporal completion. The interface is
// narrow: the handler needs exactly these three calls, and the concrete implementation resolves
// the right per-Fleet workload client from the execution ID the same way the activity completer
// does. A nil StatusClient is valid — the handler skips finalized check and DB writes.
type StatusClient interface {
	GetNodeExecution(ctx context.Context, runID, nodeExecID uuid.UUID) (NodeExecutionStatus, error)
	UpdateNodeExecution(ctx context.Context, runID, nodeExecID uuid.UUID, status, result, artifactRefs, podName, errorMessage string) error
	WriteExecutionLog(ctx context.Context, runID, nodeExecID uuid.UUID, level, message string)
}

// NodeExecutionStatus is the subset of a node execution the handler reads from the API server:
// Status decides whether the callback is stale, and Namespace scopes a cache-miss hash recovery
// to the execution's own namespace (empty in a single-tenant deployment).
type NodeExecutionStatus struct {
	Status    string
	Namespace string
}

// Handler serves POST /api/v1/callback: an agent pod reporting that its node execution finished.
type Handler struct {
	cache     *HashCache
	resolver  SecretHashResolver
	completer ActivityCompleter
	status    StatusClient // nil skips finalized check and DB writes
}

// NewHandler constructs a Handler. resolver may be nil — verification then relies on cache alone,
// which is sufficient in tests and whenever the cache is known to be warm. status may be nil —
// finalized check and DB writes are skipped when it is.
func NewHandler(cache *HashCache, resolver SecretHashResolver, completer ActivityCompleter, status StatusClient) *Handler {
	return &Handler{cache: cache, resolver: resolver, completer: completer, status: status}
}

// formatParkWait renders a park duration the way an operator reads it: whole minutes, no
// seconds component.
func formatParkWait(d time.Duration) string {
	d = d.Round(time.Minute)
	if d < time.Minute {
		return "<1m"
	}
	h := int(d / time.Hour)
	m := int((d % time.Hour) / time.Minute)
	switch {
	case h == 0:
		return fmt.Sprintf("%dm", m)
	case m == 0:
		return fmt.Sprintf("%dh", h)
	default:
		return fmt.Sprintf("%dh%dm", h, m)
	}
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	bearer := extractBearer(r)
	if bearer == "" {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	var body struct {
		NodeExecutionID     string          `json:"node_execution_id"`
		RunID               string          `json:"run_id"`
		Status              string          `json:"status"`
		Result              string          `json:"result"`
		ErrorMessage        string          `json:"error_message"`
		ArtifactRefs        json.RawMessage `json:"artifact_refs"`
		SessionID           string          `json:"session_id"`
		ResumeAt            *time.Time      `json:"resume_at"`
		SessionArtifactPath string          `json:"session_artifact_path"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	execID, err := uuid.Parse(body.NodeExecutionID)
	if err != nil {
		http.Error(w, "invalid execution id", http.StatusBadRequest)
		return
	}

	runID, err := uuid.Parse(body.RunID)
	if err != nil {
		http.Error(w, "invalid run_id", http.StatusBadRequest)
		return
	}

	ctx := r.Context()

	// namespaceFn is consulted only on a cache miss (a Worker that restarted after launch), so
	// the common warm-cache path makes no extra call before authenticating. The callback body
	// carries runID, which with the status client resolves the execution's namespace for the
	// executor's namespaced hash-recovery read.
	namespaceFn := func() (string, error) {
		if h.status == nil {
			return "", nil
		}
		ne, err := h.status.GetNodeExecution(ctx, runID, execID)
		if err != nil {
			return "", err
		}
		return ne.Namespace, nil
	}
	if !verifySecret(ctx, h.cache, h.resolver, execID, bearer, namespaceFn) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	// --- Behavior 1: finalized check ---
	if h.status != nil {
		nodeExec, err := h.status.GetNodeExecution(ctx, runID, execID)
		if err != nil {
			slog.Error("failed to get node execution for finalized check", "execution_id", execID, "error", err)
			// Continue without the check rather than rejecting a valid callback.
		} else {
			switch nodeExec.Status {
			case "completed", "failed", "invalidated", "paused":
				http.Error(w, "node execution already finalized", http.StatusConflict)
				return
			}
		}
	}

	// --- Behavior 2: empty-result rejection ---
	if body.Status == "completed" && strings.TrimSpace(body.Result) == "" {
		if err := h.completer.Fail(ctx, execID,
			fmt.Errorf("node reported completed but result is empty")); err != nil {
			slog.Error("failed to fail activity for empty result", "execution_id", execID, "error", err)
		}
		if h.status != nil {
			errMsg := "completed with empty result"
			if err := h.status.UpdateNodeExecution(ctx, runID, execID,
				"failed", "", "", "", errMsg); err != nil {
				slog.Error("failed to update node execution for empty result", "execution_id", execID, "error", err)
			}
			h.status.WriteExecutionLog(ctx, runID, execID, "warn",
				"Callback rejected: node reported completed but result is empty — failing activity for retry")
		}
		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "rejected", "reason": "empty result"})
		return
	}

	// --- Behavior 3: rate-limited ---
	if body.Status == "rate_limited" {
		if body.ResumeAt == nil {
			http.Error(w, "rate_limited requires resume_at", http.StatusBadRequest)
			return
		}
		wait := time.Until(*body.ResumeAt)
		if wait > maxParkDuration {
			http.Error(w, "rate_limited requires resume_at within "+maxParkDuration.String(),
				http.StatusBadRequest)
			return
		}

		if err := h.completer.Complete(ctx, CompletionRequest{
			NodeExecutionID:     execID,
			RunID:               runID,
			Status:              "rate_limited",
			ResumeAt:            *body.ResumeAt,
			SessionID:           body.SessionID,
			SessionArtifactPath: body.SessionArtifactPath,
		}); err != nil {
			slog.Error("failed to complete rate-limited activity", "execution_id", execID, "error", err)
		}

		// Clear the pod reference so the UI does not point at a deleted pod.
		if h.status != nil {
			if err := h.status.UpdateNodeExecution(ctx, runID, execID,
				"running", "", "", "", ""); err != nil {
				slog.Error("failed to clear pod_name for rate-limited", "execution_id", execID, "error", err)
			}
			h.status.WriteExecutionLog(ctx, runID, execID, "info",
				fmt.Sprintf("Claude quota exhausted. Resuming automatically at %s (in ~%s). No action needed.",
					body.ResumeAt.UTC().Format("15:04 UTC"), formatParkWait(wait)))
		}

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "parked"})
		return
	}

	// --- Normal completion / failure ---
	err = h.completer.Complete(ctx, CompletionRequest{
		NodeExecutionID: execID,
		RunID:           runID,
		Status:          body.Status,
		Result:          body.Result,
		ErrorMessage:    body.ErrorMessage,
		ArtifactRefs:    body.ArtifactRefs,
		SessionID:       body.SessionID,
	})
	if err != nil {
		slog.Error("failed to complete activity", "execution_id", execID, "error", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	// --- Behavior 4: best-effort DB writes ---
	if h.status != nil {
		if err := h.status.UpdateNodeExecution(ctx, runID, execID,
			body.Status, body.Result, string(body.ArtifactRefs), "", body.ErrorMessage); err != nil {
			slog.Error("failed to update node execution after completion", "execution_id", execID, "error", err)
		}
		h.status.WriteExecutionLog(ctx, runID, execID, "info",
			fmt.Sprintf("Callback received: status=%s result=%s", body.Status, body.Result))
	}

	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "accepted"})
}

// verifySecret checks bearer against the hash on record for execID, resolving and caching it
// first on a cache miss. Shared by Handler and HeartbeatHandler so both endpoints authenticate
// exactly the same way. namespaceFn is invoked only on a cache miss to resolve the execution's
// namespace for the recovery read; a caller that cannot resolve one (the heartbeat endpoint,
// whose body carries no runID) returns "" and the namespaced read then fails closed to 401.
func verifySecret(ctx context.Context, cache *HashCache, resolver SecretHashResolver, execID uuid.UUID, bearer string, namespaceFn func() (string, error)) bool {
	expectedHash, ok := cache.Get(execID)
	if !ok && resolver != nil {
		namespace, err := namespaceFn()
		if err != nil {
			slog.Warn("failed to resolve namespace for job secret hash", "execution_id", execID, "error", err)
			return false
		}
		resolved, err := resolver.ResolveJobSecretHash(ctx, namespace, execID)
		if err != nil {
			slog.Warn("failed to resolve job secret hash", "execution_id", execID, "error", err)
			return false
		}
		expectedHash = resolved
		cache.Put(execID, resolved)
		ok = true
	}
	if !ok {
		return false
	}

	// Constant-time: a length/byte-position leak here would let a caller who knows an
	// execution ID brute-force its secret one byte at a time via response timing.
	presentedHash := executor.HashSecret(bearer)
	return subtle.ConstantTimeCompare([]byte(presentedHash), []byte(expectedHash)) == 1
}

func extractBearer(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	if !strings.HasPrefix(auth, "Bearer ") {
		return ""
	}
	return strings.TrimPrefix(auth, "Bearer ")
}
