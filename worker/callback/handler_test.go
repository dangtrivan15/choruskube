package callback

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"

	"github.com/dangtrivan15/choruskube/worker/executor"
)

func TestHandler_ValidSecret_CompletesActivity(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "test-secret-value"
	hash := executor.HashSecret(secret)

	cache := NewHashCache()
	cache.Put(execID, hash)

	var completed bool
	completer := &mockCompleter{
		completeFn: func(ctx context.Context, req CompletionRequest) error {
			completed = true
			return nil
		},
	}

	handler := NewHandler(cache, nil, completer, nil)

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            runID.String(),
		"status":            "completed",
		"result":            "done",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/callback", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.True(t, completed)
}

// TestHandler_ArtifactRefsObjectShape_Decodes pins the actual wire shape entrypoint.sh sends:
// artifact_refs is always a JSON object (e.g. {"output": "runs/.../out/"}, or {} for a node
// with no outputs), never an array. A CompletionRequest field typed to decode only an array
// would 400 every completed callback that carries artifacts.
func TestHandler_ArtifactRefsObjectShape_Decodes(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "test-secret-value"
	hash := executor.HashSecret(secret)

	cache := NewHashCache()
	cache.Put(execID, hash)

	var captured CompletionRequest
	completer := &mockCompleter{
		completeFn: func(ctx context.Context, req CompletionRequest) error {
			captured = req
			return nil
		},
	}
	handler := NewHandler(cache, nil, completer, nil)

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            runID.String(),
		"status":            "completed",
		"result":            "done",
		"artifact_refs":     map[string]string{"output": "runs/x/out/"},
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/callback", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code, "body=%s", w.Body.String())
	assert.JSONEq(t, `{"output":"runs/x/out/"}`, string(captured.ArtifactRefs))
}

func TestHandler_InvalidSecret_Returns401(t *testing.T) {
	execID := uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("correct-secret"))

	handler := NewHandler(cache, nil, &mockCompleter{}, nil)

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            uuid.New().String(),
		"status":            "completed",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/callback", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer wrong-secret")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestHandler_CacheMiss_ResolvesFromExecutor(t *testing.T) {
	execID := uuid.New()
	secret := "resolv-secret"
	hash := executor.HashSecret(secret)

	cache := NewHashCache() // empty — no entry for execID

	resolverCalled := false
	mockExec := &mockExecutor{
		resolveJobSecretHashFn: func(ctx context.Context, id uuid.UUID) (string, error) {
			resolverCalled = true
			assert.Equal(t, execID, id)
			return hash, nil
		},
	}

	handler := NewHandler(cache, mockExec, &mockCompleter{
		completeFn: func(ctx context.Context, req CompletionRequest) error { return nil },
	}, nil)

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            uuid.New().String(),
		"status":            "completed",
		"result":            "ok",
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/callback", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.True(t, resolverCalled)
	// Verify it was cached
	cached, ok := cache.Get(execID)
	assert.True(t, ok)
	assert.Equal(t, hash, cached)
}

type mockCompleter struct {
	completeFn func(context.Context, CompletionRequest) error
	failFn     func(context.Context, uuid.UUID, error) error
	completeCt int
	failCt     int
}

func (m *mockCompleter) Complete(ctx context.Context, req CompletionRequest) error {
	m.completeCt++
	if m.completeFn != nil {
		return m.completeFn(ctx, req)
	}
	return nil
}

func (m *mockCompleter) Fail(ctx context.Context, executionID uuid.UUID, reason error) error {
	m.failCt++
	if m.failFn != nil {
		return m.failFn(ctx, executionID, reason)
	}
	return nil
}

type mockStatusClient struct {
	getStatus        string
	getErr           error
	updateCalls      []updateCall
	logCalls         []logCall
}

type updateCall struct {
	runID, execID                                       uuid.UUID
	status, result, artifactRefs, podName, errorMessage string
}

type logCall struct {
	runID, execID  uuid.UUID
	level, message string
}

func (m *mockStatusClient) GetNodeExecution(_ context.Context, runID, execID uuid.UUID) (NodeExecutionStatus, error) {
	return NodeExecutionStatus{Status: m.getStatus}, m.getErr
}

func (m *mockStatusClient) UpdateNodeExecution(_ context.Context, runID, execID uuid.UUID, status, result, artifactRefs, podName, errorMessage string) error {
	m.updateCalls = append(m.updateCalls, updateCall{runID, execID, status, result, artifactRefs, podName, errorMessage})
	return nil
}

func (m *mockStatusClient) WriteExecutionLog(_ context.Context, runID, execID uuid.UUID, level, message string) {
	m.logCalls = append(m.logCalls, logCall{runID, execID, level, message})
}

type mockExecutor struct {
	resolveJobSecretHashFn func(context.Context, uuid.UUID) (string, error)
}

func (m *mockExecutor) ResolveJobSecretHash(ctx context.Context, id uuid.UUID) (string, error) {
	return m.resolveJobSecretHashFn(ctx, id)
}

// --- Tests for the 5 new behaviors ---

// helper to build a valid request against a handler whose cache already holds the secret.
func callbackReq(t *testing.T, execID uuid.UUID, secret string, bodyMap map[string]any) *http.Request {
	t.Helper()
	b, _ := json.Marshal(bodyMap)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/callback", bytes.NewReader(b))
	req.Header.Set("Authorization", "Bearer "+secret)
	return req
}

func TestHandler_MissingRunID_Returns400(t *testing.T) {
	execID := uuid.New()
	secret := "s"
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	handler := NewHandler(cache, nil, &mockCompleter{}, nil)

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
		"status":            "completed",
		"result":            "ok",
		// no run_id
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/callback", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandler_FinalizedCheck_Returns409(t *testing.T) {
	for _, status := range []string{"completed", "failed", "invalidated", "paused"} {
		t.Run(status, func(t *testing.T) {
			execID := uuid.New()
			runID := uuid.New()
			secret := "s"
			cache := NewHashCache()
			cache.Put(execID, executor.HashSecret(secret))

			completer := &mockCompleter{}
			sc := &mockStatusClient{getStatus: status}
			handler := NewHandler(cache, nil, completer, sc)

			r := callbackReq(t, execID, secret, map[string]any{
				"node_execution_id": execID.String(),
				"run_id":            runID.String(),
				"status":            "completed",
				"result":            "done",
			})
			w := httptest.NewRecorder()
			handler.ServeHTTP(w, r)

			assert.Equal(t, http.StatusConflict, w.Code)
			assert.Equal(t, 0, completer.completeCt, "completer must NOT be called when finalized")
			assert.Equal(t, 0, completer.failCt, "fail must NOT be called when finalized")
		})
	}
}

func TestHandler_EmptyResult_FailsActivity(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "s"
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	completer := &mockCompleter{}
	sc := &mockStatusClient{getStatus: "running"}
	handler := NewHandler(cache, nil, completer, sc)

	r := callbackReq(t, execID, secret, map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            runID.String(),
		"status":            "completed",
		"result":            "   ", // blank
	})
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, 1, completer.failCt, "empty result must call Fail")
	assert.Equal(t, 0, completer.completeCt, "empty result must NOT call Complete")

	// DB must be updated to failed
	if assert.Len(t, sc.updateCalls, 1) {
		assert.Equal(t, "failed", sc.updateCalls[0].status)
		assert.Equal(t, "completed with empty result", sc.updateCalls[0].errorMessage)
	}

	var resp map[string]string
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Equal(t, "rejected", resp["status"])
}

func TestHandler_RateLimited_CompletesWithPark(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "s"
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	var captured CompletionRequest
	completer := &mockCompleter{
		completeFn: func(_ context.Context, req CompletionRequest) error {
			captured = req
			return nil
		},
	}
	sc := &mockStatusClient{getStatus: "running"}
	handler := NewHandler(cache, nil, completer, sc)

	resumeAt := time.Now().Add(30 * time.Minute).UTC()
	r := callbackReq(t, execID, secret, map[string]any{
		"node_execution_id":      execID.String(),
		"run_id":                 runID.String(),
		"status":                 "rate_limited",
		"resume_at":              resumeAt.Format(time.RFC3339),
		"session_id":             "sess-123",
		"session_artifact_path":  "/transcript/path",
	})
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, 1, completer.completeCt)
	assert.Equal(t, "rate_limited", captured.Status)
	assert.Equal(t, "sess-123", captured.SessionID)
	assert.Equal(t, "/transcript/path", captured.SessionArtifactPath)
	assert.WithinDuration(t, resumeAt, captured.ResumeAt, time.Second)

	// pod_name must be cleared via update with "running" status
	if assert.Len(t, sc.updateCalls, 1) {
		assert.Equal(t, "running", sc.updateCalls[0].status)
	}

	var resp map[string]string
	_ = json.Unmarshal(w.Body.Bytes(), &resp)
	assert.Equal(t, "parked", resp["status"])
}

func TestHandler_RateLimited_MissingResumeAt_Returns400(t *testing.T) {
	execID := uuid.New()
	secret := "s"
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	handler := NewHandler(cache, nil, &mockCompleter{}, &mockStatusClient{getStatus: "running"})

	r := callbackReq(t, execID, secret, map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            uuid.New().String(),
		"status":            "rate_limited",
		// no resume_at
	})
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)

	assert.Equal(t, http.StatusBadRequest, w.Code)
}

func TestHandler_DBWritesAfterNormalCompletion(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "s"
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	completer := &mockCompleter{
		completeFn: func(_ context.Context, _ CompletionRequest) error { return nil },
	}
	sc := &mockStatusClient{getStatus: "running"}
	handler := NewHandler(cache, nil, completer, sc)

	r := callbackReq(t, execID, secret, map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            runID.String(),
		"status":            "completed",
		"result":            "some result",
		"artifact_refs":     map[string]string{"output": "runs/x/out/"},
		"error_message":     "",
	})
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)

	assert.Equal(t, http.StatusOK, w.Code)

	if assert.Len(t, sc.updateCalls, 1) {
		u := sc.updateCalls[0]
		assert.Equal(t, runID, u.runID)
		assert.Equal(t, execID, u.execID)
		assert.Equal(t, "completed", u.status)
		assert.Equal(t, "some result", u.result)
		assert.Contains(t, u.artifactRefs, "runs/x/out/")
	}
	assert.Len(t, sc.logCalls, 1, "an execution log must be written")
}

func TestHandler_FinalizedCheckSkippedWhenStatusClientNil(t *testing.T) {
	execID := uuid.New()
	secret := "s"
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	completer := &mockCompleter{
		completeFn: func(_ context.Context, _ CompletionRequest) error { return nil },
	}
	// No StatusClient — finalized check and DB writes must be skipped.
	handler := NewHandler(cache, nil, completer, nil)

	r := callbackReq(t, execID, secret, map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            uuid.New().String(),
		"status":            "completed",
		"result":            "ok",
	})
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, 1, completer.completeCt)
}

func TestHandler_FinalizedCheckError_ContinuesWithoutRejection(t *testing.T) {
	execID := uuid.New()
	secret := "s"
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	completer := &mockCompleter{
		completeFn: func(_ context.Context, _ CompletionRequest) error { return nil },
	}
	sc := &mockStatusClient{getErr: errors.New("api down")}
	handler := NewHandler(cache, nil, completer, sc)

	r := callbackReq(t, execID, secret, map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            uuid.New().String(),
		"status":            "completed",
		"result":            "ok",
	})
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, 1, completer.completeCt, "completion must proceed when the finalized check fails")
}
