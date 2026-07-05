package callback

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// Heartbeater proxies heartbeats to Temporal
type Heartbeater interface {
	RecordHeartbeat(ctx context.Context, nodeExecID uuid.UUID, workflowID string) error
}

// HeartbeatRequest is the JSON body sent by agent pods
type HeartbeatRequest struct {
	NodeExecutionID string `json:"node_execution_id"`
	RunID           string `json:"run_id"`
}

// HeartbeatHandler handles POST /api/v1/heartbeat
type HeartbeatHandler struct {
	client      *apiclient.Client
	heartbeater Heartbeater
}

// NewHeartbeatHandler constructs a HeartbeatHandler
func NewHeartbeatHandler(client *apiclient.Client, heartbeater Heartbeater) *HeartbeatHandler {
	return &HeartbeatHandler{client: client, heartbeater: heartbeater}
}

// ServeHTTP handles heartbeat requests from agent pods
func (h *HeartbeatHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req HeartbeatRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "invalid request body", http.StatusBadRequest)
		return
	}

	execID, err := uuid.Parse(req.NodeExecutionID)
	if err != nil {
		http.Error(w, "invalid node_execution_id", http.StatusBadRequest)
		return
	}

	runID, err := uuid.Parse(req.RunID)
	if err != nil {
		http.Error(w, "invalid run_id", http.StatusBadRequest)
		return
	}

	ctx := r.Context()

	// Validate JOB_SECRET against API server
	secretHash, err := h.client.GetJobSecretHash(ctx, runID, execID)
	if err != nil {
		http.Error(w, "node execution not found", http.StatusNotFound)
		return
	}

	bearerToken := extractBearerToken(r)
	if bearerToken == "" || !validateSecret(bearerToken, secretHash) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	// Proxy heartbeat to Temporal
	workflowID := fmt.Sprintf("choruskube-run-%s", runID)
	if err := h.heartbeater.RecordHeartbeat(ctx, execID, workflowID); err != nil {
		// Log but don't fail — heartbeat errors are transient and non-fatal.
		// The activity may have already completed (race with callback).
		log.Printf("WARN: heartbeat failed for %s: %v", execID, err)
	}

	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}
