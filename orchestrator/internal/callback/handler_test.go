package callback

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// mockActivityCompleter implements the Temporal activity completion interface
type mockActivityCompleter struct {
	completedExecID    uuid.UUID
	completedResult    string
	completedArtifacts string
	completedError     string
	failedExecID       uuid.UUID
	failedErr          error
	failCalled         bool

	// Configurable error returns for testing failure paths
	completeErr error
	failErr     error

	// Rate-limited completion
	rateLimitedCalled   bool
	rateLimitedExecID   uuid.UUID
	resumeAt            time.Time
	sessionID           string
	sessionArtifactPath string
	rateLimitedErr      error
}

func (m *mockActivityCompleter) CompleteActivity(ctx context.Context, nodeExecID uuid.UUID, workflowID string, result, artifactRefs, errorMessage string) error {
	m.completedExecID = nodeExecID
	m.completedResult = result
	m.completedArtifacts = artifactRefs
	m.completedError = errorMessage
	return m.completeErr
}

func (m *mockActivityCompleter) FailActivity(ctx context.Context, nodeExecID uuid.UUID, workflowID string, reason error) error {
	m.failedExecID = nodeExecID
	m.failedErr = reason
	m.failCalled = true
	return m.failErr
}

func (m *mockActivityCompleter) CompleteActivityRateLimited(ctx context.Context, nodeExecID uuid.UUID, workflowID string, resumeAt time.Time, sessionID, sessionArtifactPath string) error {
	m.rateLimitedCalled = true
	m.rateLimitedExecID = nodeExecID
	m.resumeAt = resumeAt
	m.sessionID = sessionID
	m.sessionArtifactPath = sessionArtifactPath
	return m.rateLimitedErr
}

func TestCallback_ValidRequest(t *testing.T) {
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	// Mock API server
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "completed",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "POST" && contains(r.URL.Path, "logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "completed",
		Result:          "completed",
		ArtifactRefs:    json.RawMessage(`{"output":"runs/abc/out/"}`),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, execID, completer.completedExecID)
}

func TestCallback_InvalidSecret(t *testing.T) {
	hash := sha256.Sum256([]byte("correct-secret"))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	handler := NewHandler(client, &mockActivityCompleter{})

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "completed",
		Result:          "completed",
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", "Bearer wrong-secret")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestCallback_AlreadyCompleted(t *testing.T) {
	jobSecret := "test-secret"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "completed",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	handler := NewHandler(client, &mockActivityCompleter{})

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "completed",
		Result:          "completed",
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusConflict, w.Code)
}

func contains(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(s) > 0 && containsStr(s, substr))
}

func containsStr(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}

func TestCallback_CompletedEmptyResult_FailsActivity(t *testing.T) {
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "failed",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "POST" && contains(r.URL.Path, "logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "completed",
		Result:          "",
		ArtifactRefs:    json.RawMessage(`{}`),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
	// Should have called FailActivity, NOT CompleteActivity
	assert.Equal(t, execID, completer.failedExecID)
	assert.Equal(t, uuid.Nil, completer.completedExecID)
	require.NotNil(t, completer.failedErr)
	assert.Contains(t, completer.failedErr.Error(), "empty")
}

func TestCallback_CompletedWhitespaceResult_FailsActivity(t *testing.T) {
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "failed",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "POST" && contains(r.URL.Path, "logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "completed",
		Result:          "   \n\t  ",
		ArtifactRefs:    json.RawMessage(`{}`),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, execID, completer.failedExecID)
	assert.Equal(t, uuid.Nil, completer.completedExecID)
}

func TestCallback_FailedEmptyResult_Accepted(t *testing.T) {
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "failed",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "POST" && contains(r.URL.Path, "logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	errMsg := "Claude produced no result after 3 attempts"
	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "failed",
		Result:          "",
		ArtifactRefs:    json.RawMessage(`{}`),
		ErrorMessage:    &errMsg,
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
	// FailActivity should be called (because status is "failed"), not via validation
	assert.Equal(t, execID, completer.failedExecID)
}

func TestCallback_TemporalTimeoutRace_StillUpdatesDB(t *testing.T) {
	// Simulates the timeout race: agent completes, but Temporal has already
	// timed out the activity. CompleteActivity fails, but the DB should
	// still be updated with the result (best-effort preservation).
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	var dbUpdateReceived bool
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			dbUpdateReceived = true
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "completed",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "POST" && contains(r.URL.Path, "logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{
		completeErr: fmt.Errorf("cannot find pending activity with ActivityID %s", execID),
	}
	handler := NewHandler(client, completer)

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "completed",
		Result:          "The implementation is complete",
		ArtifactRefs:    json.RawMessage(`{"output":"runs/abc/out/"}`),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	// Temporal failed, but handler should still return 200 and update DB
	assert.Equal(t, http.StatusOK, w.Code)
	assert.True(t, dbUpdateReceived, "DB should still be updated even when Temporal rejects")
	assert.Equal(t, execID, completer.completedExecID, "CompleteActivity should have been attempted")
}

func TestCallback_RejectedForPausedExecution(t *testing.T) {
	// A callback arriving for a node already in "paused" state must be rejected with
	// 409 Conflict and must not call SignalWorkflow or UpdateNodeExecution.
	jobSecret := "test-secret"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	var dbUpdateCalled bool
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			// Node is already paused
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "paused",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			dbUpdateCalled = true
			w.WriteHeader(http.StatusOK)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "completed",
		Result:          "some result",
		ArtifactRefs:    json.RawMessage(`{}`),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusConflict, w.Code)
	// Must not have attempted to complete or fail the Temporal activity
	assert.Equal(t, uuid.Nil, completer.completedExecID, "CompleteActivity must not be called for a paused execution")
	assert.Equal(t, uuid.Nil, completer.failedExecID, "FailActivity must not be called for a paused execution")
	// Must not have updated the DB
	assert.False(t, dbUpdateCalled, "DB status update must not be called for a paused execution")
}

// nodeExecStatusUpdate captures the fields of a single PUT .../status call so
// tests can inspect exactly what was written.
type nodeExecStatusUpdate struct {
	Status  string  `json:"status"`
	PodName *string `json:"podName"`
}

func TestRateLimitedCallbackLeavesStatusUntouched(t *testing.T) {
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	// The real API server's updateNodeExecutionStatus applies status
	// unconditionally via NodeExecutionStatus.valueOf(req.status()) — every
	// other field on the DTO is null-guarded, but status is not, so an
	// unrecognized value (like the raw callback status "rate_limited") 500s in
	// production. Mimicking that here means a regression that lets execution
	// fall through to the pre-existing unconditional status write surfaces as
	// a real failure (a non-200 response and/or a second recorded PUT), not as
	// a silently-passing assertion.
	validStatuses := map[string]bool{"running": true, "completed": true, "failed": true, "invalidated": true, "paused": true}

	var putCalls []nodeExecStatusUpdate
	var logMessages []string
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			var payload nodeExecStatusUpdate
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &payload)
			putCalls = append(putCalls, payload)
			if !validStatuses[payload.Status] {
				w.WriteHeader(http.StatusInternalServerError)
				return
			}
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": payload.Status,
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "POST" && contains(r.URL.Path, "logs"):
			var payload map[string]string
			raw, _ := io.ReadAll(r.Body)
			_ = json.Unmarshal(raw, &payload)
			logMessages = append(logMessages, payload["message"])
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	// Relative, not a fixed calendar instant: the handler now rejects a
	// resume_at outside (now, now+6h], so a hard-coded date would decay into a
	// rejected request the moment the wall clock passed it.
	resumeAt := time.Now().UTC().Add(42 * time.Minute).Truncate(time.Second)
	sessionID := "63527525-b042-4779-9bd1-f28c203abb62"
	sessionArtifactPath := "runs/r/e/session/63527525.jsonl"
	body := CallbackRequest{
		NodeExecutionID:     execID.String(),
		RunID:               runID.String(),
		Status:              "rate_limited",
		Result:              "quota exhausted",
		ResumeAt:            &resumeAt,
		SessionID:           &sessionID,
		SessionArtifactPath: &sessionArtifactPath,
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	require.Equal(t, http.StatusOK, rec.Code)

	// The activity completes; it must not fail, which would park the run for a human.
	require.True(t, completer.rateLimitedCalled, "expected CompleteActivityRateLimited to be called")
	require.False(t, completer.failCalled, "rate_limited must not fail the activity")
	assert.Equal(t, execID, completer.rateLimitedExecID)
	assert.Equal(t, sessionID, completer.sessionID)
	assert.Equal(t, sessionArtifactPath, completer.sessionArtifactPath)
	assert.True(t, resumeAt.Equal(completer.resumeAt), "resumeAt must be passed through unchanged, got %v", completer.resumeAt)

	// Exactly one status PUT. A second one would mean execution fell through
	// to the pre-existing unconditional write below the rate_limited branch —
	// the exact regression this test exists to catch.
	require.Len(t, putCalls, 1, "rate_limited must return before the unconditional status write")

	// The status write is suppressed: rate_limited is not a node_execution_status
	// value, so the one write that does happen must re-assert "running" (the
	// status the row already has) rather than the raw callback status — the node
	// must remain running so Autopilot keeps holding its slot.
	assert.Equal(t, "running", putCalls[0].Status, "status must be re-asserted as running, never written as rate_limited")

	// The pod is gone for the whole sleep; the Run info panel must not keep
	// pointing at it.
	require.NotNil(t, putCalls[0].PodName, "pod_name must be included in the update")
	assert.Empty(t, *putCalls[0].PodName, "pod_name must be cleared while parked")

	// The park's user-facing line. The absolute time alone is ambiguous: the
	// reset is a wall-clock time with no date, so a rollover park reads as
	// "00:15 UTC" whether that is tonight or tomorrow. The relative half is what
	// resolves it, and is asserted here for that reason.
	require.Len(t, logMessages, 1, "the park must write exactly one execution log line")
	assert.Contains(t, logMessages[0], resumeAt.Format("15:04 UTC"),
		"the park line must name the absolute reset time")
	assert.Contains(t, logMessages[0], "(in ~42m)",
		"the park line must name the wait relative to now")
	assert.Contains(t, logMessages[0], "No action needed.")
}

func TestFormatParkWait(t *testing.T) {
	// Duration.String() renders these as "42m0s", "2h0m0s" and "2h42m0s", which
	// is not what the park line should read like.
	assert.Equal(t, "42m", formatParkWait(42*time.Minute))
	assert.Equal(t, "2h", formatParkWait(2*time.Hour))
	assert.Equal(t, "2h42m", formatParkWait(2*time.Hour+42*time.Minute))
	assert.Equal(t, "6h", formatParkWait(maxParkDuration))
	// Rounds to the nearest minute rather than truncating, so a wait of just
	// under a minute still reads as a wait.
	assert.Equal(t, "1m", formatParkWait(50*time.Second))
	assert.Equal(t, "<1m", formatParkWait(10*time.Second))
}

func TestRateLimitedCallbackWithOutOfBoundsResumeAt_Rejected(t *testing.T) {
	// quota-lib.sh refuses to park beyond QUOTA_MAX_PARK_SECONDS, but the wait
	// itself happens in the workflow, which sleeps for resumeAt with no clamp and
	// no rejection of a past instant. The bound therefore has to be re-checked at
	// this boundary — the one that schedules the sleep — so a parse bug or an
	// upstream change to the quota message degrades to the node's existing
	// failure path rather than to an unbounded wait.
	cases := []struct {
		name     string
		resumeAt time.Time
	}{
		{"already past", time.Now().UTC().Add(-time.Minute)},
		{"exactly now", time.Now().UTC().Add(-time.Millisecond)},
		{"beyond the 6h bound", time.Now().UTC().Add(maxParkDuration + 5*time.Minute)},
		{"absurdly far out", time.Now().UTC().AddDate(1, 0, 0)},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			jobSecret := "test-secret-value"
			hash := sha256.Sum256([]byte(jobSecret))
			hashStr := hex.EncodeToString(hash[:])

			execID := uuid.New()
			runID := uuid.New()

			var putCalled bool
			apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				switch {
				case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
					json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
				case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
					json.NewEncoder(w).Encode(map[string]interface{}{
						"id": execID, "templateNodeId": uuid.New(), "status": "running",
						"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
					})
				case r.Method == "PUT" && contains(r.URL.Path, "status"):
					putCalled = true
					w.WriteHeader(http.StatusOK)
				default:
					w.WriteHeader(http.StatusNotFound)
				}
			}))
			defer apiServer.Close()

			client := apiclient.NewClient(apiServer.URL)
			completer := &mockActivityCompleter{}
			handler := NewHandler(client, completer)

			resumeAt := tc.resumeAt
			body := CallbackRequest{
				NodeExecutionID: execID.String(),
				RunID:           runID.String(),
				Status:          "rate_limited",
				Result:          "quota exhausted",
				ResumeAt:        &resumeAt,
			}
			bodyBytes, _ := json.Marshal(body)

			req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
			req.Header.Set("Content-Type", "application/json")
			req.Header.Set("Authorization", "Bearer "+jobSecret)
			rec := httptest.NewRecorder()

			handler.ServeHTTP(rec, req)

			assert.Equal(t, http.StatusBadRequest, rec.Code)
			assert.False(t, completer.rateLimitedCalled, "no park may be scheduled for an out-of-bounds resume_at")
			assert.False(t, completer.failCalled, "activity must not be failed either — same as every other malformed-request early return in this handler")
			assert.False(t, putCalled, "no DB write for a rejected callback")
		})
	}
}

func TestRateLimitedCallbackAtTheParkBound_Accepted(t *testing.T) {
	// The bound is inclusive at the top: quota-lib.sh will hand over a reset
	// landing at exactly +21600s, and rejecting it here would fail the very park
	// the library considers legal. Paired with the out-of-bounds cases above so
	// neither a too-tight nor a too-loose bound passes both.
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "POST" && contains(r.URL.Path, "logs"):
			w.WriteHeader(http.StatusCreated)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	// A few seconds of slack absorbs the time the request itself takes; the
	// point of the case is that a park at the very top of the range is accepted,
	// not that it is accepted to the nanosecond.
	resumeAt := time.Now().UTC().Add(maxParkDuration - 5*time.Second)
	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "rate_limited",
		Result:          "quota exhausted",
		ResumeAt:        &resumeAt,
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusOK, rec.Code)
	assert.True(t, completer.rateLimitedCalled, "a park at the bound must still be scheduled")
}

func TestRateLimitedCallbackWithoutResumeAt_Rejected(t *testing.T) {
	// A rate_limited callback with no resume_at is a client error: parking for
	// an unknown duration is not safe, so it must be rejected outright rather
	// than accepted and silently mishandled.
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	var putCalled bool
	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		case r.Method == "GET" && contains(r.URL.Path, "node-executions/"+execID.String()):
			json.NewEncoder(w).Encode(map[string]interface{}{
				"id": execID, "templateNodeId": uuid.New(), "status": "running",
				"iteration": 1, "graphVersion": 1, "artifactRefs": "{}",
			})
		case r.Method == "PUT" && contains(r.URL.Path, "status"):
			putCalled = true
			w.WriteHeader(http.StatusOK)
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	completer := &mockActivityCompleter{}
	handler := NewHandler(client, completer)

	body := CallbackRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
		Status:          "rate_limited",
		Result:          "quota exhausted",
		// ResumeAt intentionally omitted.
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/callback", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	rec := httptest.NewRecorder()

	handler.ServeHTTP(rec, req)

	assert.Equal(t, http.StatusBadRequest, rec.Code)
	assert.False(t, completer.rateLimitedCalled, "activity must not be completed for a rejected callback")
	assert.False(t, completer.failCalled, "activity must not be failed either — same as every other malformed-request early return in this handler")
	assert.False(t, putCalled, "no DB write for a rejected callback")
}
