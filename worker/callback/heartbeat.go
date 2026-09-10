package callback

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"

	"github.com/google/uuid"
)

// Heartbeater records that a running node execution is still alive, keeping its Temporal
// activity from timing out. runID accompanies executionID so a Worker that did not launch the
// execution (a restart emptied its cache) can rebuild the activity's by-id addressing and relay
// the ping anyway; the launching Worker ignores it and uses the addressing it cached.
type Heartbeater interface {
	RecordHeartbeat(ctx context.Context, runID, executionID uuid.UUID) error
}

// HeartbeatHandler serves POST /api/v1/heartbeat: an agent pod's periodic liveness ping.
type HeartbeatHandler struct {
	cache       *HashCache
	resolver    SecretHashResolver
	heartbeater Heartbeater
}

// NewHeartbeatHandler constructs a HeartbeatHandler. cache and resolver are shared with the
// completion Handler so both endpoints authenticate against the same hash; on a cache-miss
// recovery (a restarted Worker) the resolver, bound to the execution's namespace, recovers the
// hash the same way. resolver may be nil, which disables that recovery (cache-only verification).
func NewHeartbeatHandler(cache *HashCache, resolver SecretHashResolver, hb Heartbeater) *HeartbeatHandler {
	return &HeartbeatHandler{cache: cache, resolver: resolver, heartbeater: hb}
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

	// run_id reaches a multi-tenant resolver that recovers the hash from the run's namespace after a
	// Worker restart empties the cache (this HTTP path has no activity context to derive it from).
	// Best-effort, not required: a liveness ping must never be rejected for a missing run_id — that
	// would add a new way to starve heartbeats, the very failure this endpoint guards against. It is
	// unused on a cache hit; on a cache miss a Nil runID simply fails recovery closed to 401.
	runID, _ := uuid.Parse(body.RunID)

	ctx := r.Context()

	if !verifySecret(ctx, h.cache, h.resolver, runID, execID, bearer) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	// Non-fatal: a heartbeat can race the callback (the activity may already be
	// complete), which is an expected outcome, not a failure the agent's heartbeat
	// loop should see as one.
	if err := h.heartbeater.RecordHeartbeat(ctx, runID, execID); err != nil {
		slog.Warn("heartbeat failed", "execution_id", execID, "error", err)
	}

	w.WriteHeader(http.StatusOK)
}
