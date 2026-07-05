package callback

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

// ActivityCompleter completes Temporal activities externally
type ActivityCompleter interface {
	CompleteActivity(ctx context.Context, nodeExecID uuid.UUID, workflowID string, result, artifactRefs, errorMessage string) error
	FailActivity(ctx context.Context, nodeExecID uuid.UUID, workflowID string, reason error) error
}

// CallbackRequest is the JSON body sent by agent pods
type CallbackRequest struct {
	NodeExecutionID string          `json:"node_execution_id"`
	RunID           string          `json:"run_id"`
	Status          string          `json:"status"`
	Result          string          `json:"result"`
	ArtifactRefs    json.RawMessage `json:"artifact_refs"`
	ErrorMessage    *string         `json:"error_message"`
}

// Handler is the HTTP handler for agent pod callbacks
type Handler struct {
	client    *apiclient.Client
	completer ActivityCompleter
}

// NewHandler constructs a Handler with the given API client and activity completer
func NewHandler(client *apiclient.Client, completer ActivityCompleter) *Handler {
	return &Handler{client: client, completer: completer}
}

// ServeHTTP handles POST /api/v1/callback
func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	var req CallbackRequest
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

	// Get job secret hash from API server to validate callback
	secretHash, err := h.client.GetJobSecretHash(ctx, runID, execID)
	if err != nil {
		http.Error(w, "node execution not found", http.StatusNotFound)
		return
	}

	// Validate JOB_SECRET
	bearerToken := extractBearerToken(r)
	if bearerToken == "" || !validateSecret(bearerToken, secretHash) {
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}

	// Get current node execution status to check for stale callback
	nodeExec, err := h.client.GetNodeExecution(ctx, runID, execID)
	if err != nil {
		http.Error(w, "node execution not found", http.StatusNotFound)
		return
	}
	if nodeExec.Status == "completed" || nodeExec.Status == "failed" ||
		nodeExec.Status == "invalidated" || nodeExec.Status == "paused" {
		http.Error(w, "node execution already finalized", http.StatusConflict)
		return
	}

	workflowID := fmt.Sprintf("choruskube-run-%s", runID)

	// Reject completed callbacks with empty/blank results.
	// This catches AI/script nodes that report success but produced nothing.
	// Human gates don't use this callback handler (they use Temporal signals).
	if req.Status == "completed" && strings.TrimSpace(req.Result) == "" {
		errMsg := "completed with empty result"

		// Signal Temporal FIRST — Temporal is the source of truth for workflow advancement.
		// If this fails (activity already timed out), the workflow handles it via awaiting_retry.
		if err := h.completer.FailActivity(ctx, execID, workflowID,
			fmt.Errorf("node reported completed but result is empty")); err != nil {
			log.Printf("ERROR: failed to fail Temporal activity for %s: %v", execID, err)
		}

		// Best-effort DB update — preserves the failure state for visibility
		if err := h.client.UpdateNodeExecution(ctx, runID, execID, state.UpdateNodeExecutionParams{
			Status:       "failed",
			ErrorMessage: &errMsg,
		}); err != nil {
			log.Printf("ERROR: failed to update node execution %s: %v", execID, err)
		}

		h.client.WriteExecutionLog(ctx, runID, execID, "warn",
			"Callback rejected: node reported completed but result is empty — failing activity for retry")

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(map[string]string{"status": "rejected", "reason": "empty result"})
		return
	}

	// Signal Temporal FIRST — Temporal is the source of truth for workflow advancement.
	// If this fails (e.g. activity already timed out), the workflow will handle it via
	// its own timeout path. We still save the result to the DB below so the work
	// product is preserved for reference, even though the workflow won't advance
	// from this callback.
	if req.Status == "completed" {
		if err := h.completer.CompleteActivity(ctx, execID, workflowID,
			req.Result, string(req.ArtifactRefs), ptrOrEmpty(req.ErrorMessage)); err != nil {
			log.Printf("ERROR: failed to complete Temporal activity for %s: %v", execID, err)
		}
	} else {
		if err := h.completer.FailActivity(ctx, execID, workflowID, fmt.Errorf("agent failed: %s", ptrOrEmpty(req.ErrorMessage))); err != nil {
			log.Printf("ERROR: failed to fail Temporal activity for %s: %v", execID, err)
		}
	}

	// Update DB (best-effort) — preserves result/artifacts regardless of Temporal outcome.
	// The workflow's own completion handler will also update the DB when it processes
	// the activity result, so this write is supplementary.
	artifactStr := string(req.ArtifactRefs)
	if err := h.client.UpdateNodeExecution(ctx, runID, execID, state.UpdateNodeExecutionParams{
		Status:       req.Status,
		Result:       &req.Result,
		ArtifactRefs: &artifactStr,
		ErrorMessage: req.ErrorMessage,
	}); err != nil {
		log.Printf("ERROR: failed to update node execution %s: %v", execID, err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	h.client.WriteExecutionLog(ctx, runID, execID, "info",
		fmt.Sprintf("Callback received: status=%s result=%s", req.Status, req.Result))

	w.WriteHeader(http.StatusOK)
	json.NewEncoder(w).Encode(map[string]string{"status": "accepted"})
}

func extractBearerToken(r *http.Request) string {
	auth := r.Header.Get("Authorization")
	if strings.HasPrefix(auth, "Bearer ") {
		return auth[7:]
	}
	return ""
}

func validateSecret(rawSecret string, storedHash string) bool {
	if storedHash == "" {
		return false
	}
	hash := sha256.Sum256([]byte(rawSecret))
	computed := hex.EncodeToString(hash[:])
	return computed == storedHash
}

func ptrOrEmpty(s *string) string {
	if s != nil {
		return *s
	}
	return ""
}
