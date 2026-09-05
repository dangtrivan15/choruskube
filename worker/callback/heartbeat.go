package callback

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/google/uuid"
)

// Heartbeater records that a running node execution is still alive, keeping its Temporal
// activity from timing out.
type Heartbeater interface {
	RecordHeartbeat(ctx context.Context, executionID uuid.UUID) error
}

// HeartbeatHandler serves POST /api/v1/heartbeat: an agent pod's periodic liveness ping.
type HeartbeatHandler struct {
	cache       *HashCache
	resolver    SecretHashResolver
	heartbeater Heartbeater
	status      StatusClient // nil, or a body without run_id, skips namespace recovery
}

// NewHeartbeatHandler constructs a HeartbeatHandler. cache and resolver are shared with the
// completion Handler so both endpoints authenticate against the same hash. status is shared with
// it too, so a cache-miss recovery (a restarted Worker) resolves the execution's namespace the
// same way -- may be nil, which disables that recovery (cache-only verification).
func NewHeartbeatHandler(cache *HashCache, resolver SecretHashResolver, hb Heartbeater, status StatusClient) *HeartbeatHandler {
	return &HeartbeatHandler{cache: cache, resolver: resolver, heartbeater: hb, status: status}
}

func (h *HeartbeatHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
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
		NodeExecutionID string `json:"node_execution_id"`
		RunID           string `json:"run_id"`
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

	ctx := r.Context()

	// namespaceFn mirrors the completion Handler's: it is consulted only on a cache miss (a Worker
	// that restarted after launch) to resolve the execution's namespace for the executor's
	// namespaced hash-recovery read. An older agent that omits run_id (or a nil status client)
	// yields "" and the namespaced read then fails closed to 401 rather than searching cluster-wide.
	namespaceFn := func() (string, error) {
		if h.status == nil || body.RunID == "" {
			return "", nil
		}
		runID, err := uuid.Parse(body.RunID)
		if err != nil {
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

	// Non-fatal: a heartbeat can race the callback (the activity may already be
	// complete), which is an expected outcome, not a failure the agent's heartbeat
	// loop should see as one.
	if err := h.heartbeater.RecordHeartbeat(ctx, execID); err != nil {
		slog.Warn("heartbeat failed", "execution_id", execID, "error", err)
	}

	w.WriteHeader(http.StatusOK)
}
