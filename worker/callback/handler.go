// Package callback serves the HTTP endpoints agent pods call back to: result completion and
// heartbeat. It replaces the orchestrator's callback server (internal/callback) for pods the
// Worker itself launched — verification reads an in-memory hash cache the Worker populated at
// Execute() time instead of asking the api-server on every request.
package callback

import (
	"context"
	"crypto/subtle"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"sync"

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
// this with ResolveJobSecretHash; the handler only needs that one method.
type SecretHashResolver interface {
	ResolveJobSecretHash(ctx context.Context, executionID uuid.UUID) (string, error)
}

// executor.Executor must keep satisfying this narrower interface — Run() passes it directly as
// the resolver — so a signature drift there fails here at compile time, not at the Run() call site.
var _ SecretHashResolver = executor.Executor(nil)

// CompletionRequest is the agent-reported outcome of one node execution, as the callback handler
// hands it to an ActivityCompleter.
type CompletionRequest struct {
	NodeExecutionID uuid.UUID
	Status          string
	Result          string
	ErrorMessage    string
	ArtifactRefs    []string
	SessionID       string
}

// ActivityCompleter resolves the Temporal activity a node execution is blocked on. The concrete
// implementation (wiring CompleteActivityByID against a Temporal client) is supplied by the
// caller — this package only depends on the interface, so it can be unit tested with a mock.
type ActivityCompleter interface {
	Complete(ctx context.Context, req CompletionRequest) error
}

// Handler serves POST /api/v1/callback: an agent pod reporting that its node execution finished.
type Handler struct {
	cache     *HashCache
	resolver  SecretHashResolver
	completer ActivityCompleter
}

// NewHandler constructs a Handler. resolver may be nil — verification then relies on cache alone,
// which is sufficient in tests and whenever the cache is known to be warm.
func NewHandler(cache *HashCache, resolver SecretHashResolver, completer ActivityCompleter) *Handler {
	return &Handler{cache: cache, resolver: resolver, completer: completer}
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
		NodeExecutionID string   `json:"node_execution_id"`
		Status          string   `json:"status"`
		Result          string   `json:"result"`
		ErrorMessage    string   `json:"error_message"`
		ArtifactRefs    []string `json:"artifact_refs"`
		SessionID       string   `json:"session_id"`
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

	if !verifySecret(r.Context(), h.cache, h.resolver, execID, bearer) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	err = h.completer.Complete(r.Context(), CompletionRequest{
		NodeExecutionID: execID,
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

	w.WriteHeader(http.StatusOK)
}

// verifySecret checks bearer against the hash on record for execID, resolving and caching it
// first on a cache miss. Shared by Handler and HeartbeatHandler so both endpoints authenticate
// exactly the same way.
func verifySecret(ctx context.Context, cache *HashCache, resolver SecretHashResolver, execID uuid.UUID, bearer string) bool {
	expectedHash, ok := cache.Get(execID)
	if !ok && resolver != nil {
		resolved, err := resolver.ResolveJobSecretHash(ctx, execID)
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
