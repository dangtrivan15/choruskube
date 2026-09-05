package callback

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"

	"github.com/dangtrivan15/choruskube/worker/executor"
)

func TestHeartbeatHandler_ValidSecret_RecordsHeartbeat(t *testing.T) {
	execID := uuid.New()
	secret := "test-secret-value"
	hash := executor.HashSecret(secret)

	cache := NewHashCache()
	cache.Put(execID, hash)

	hb := &mockHeartbeater{}
	handler := NewHeartbeatHandler(cache, nil, hb, nil)

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, execID, hb.recordedExecID)
}

func TestHeartbeatHandler_InvalidSecret_Returns401(t *testing.T) {
	execID := uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("correct-secret"))

	handler := NewHeartbeatHandler(cache, nil, &mockHeartbeater{}, nil)

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer wrong-secret")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

// A heartbeat carrying run_id (which the agent always sends) recovers the hash after a Worker
// restart the same way the completion callback does: resolve the execution's namespace via
// GetNodeExecution(runID, execID), then a namespaced ResolveJobSecretHash -- never a cluster-wide
// search.
func TestHeartbeatHandler_CacheMiss_WithRunID_RecoversViaNamespace(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "resolv-secret"
	hash := executor.HashSecret(secret)

	cache := NewHashCache() // empty — no entry for execID

	var gotNamespace string
	mockExec := &mockExecutor{
		resolveJobSecretHashFn: func(ctx context.Context, namespace string, id uuid.UUID) (string, error) {
			gotNamespace = namespace
			assert.Equal(t, execID, id)
			return hash, nil
		},
	}

	hb := &mockHeartbeater{}
	handler := NewHeartbeatHandler(cache, mockExec, hb, &mockStatusClient{getStatus: "running", getNamespace: "org-ns"})

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
		"run_id":            runID.String(),
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "org-ns", gotNamespace, "heartbeat must resolve the namespace from run_id")
	assert.Equal(t, execID, hb.recordedExecID)

	cached, ok := cache.Get(execID)
	assert.True(t, ok)
	assert.Equal(t, hash, cached)
}

// An older agent that omits run_id cannot have its namespace resolved, so a cache-miss recovery
// read gets "" and the executor's namespaced lookup fails closed to 401 -- never a cluster-wide
// fallback. mockExec here stands in for that real-executor behavior by rejecting an empty namespace.
func TestHeartbeatHandler_CacheMiss_NoRunID_FailsClosed(t *testing.T) {
	execID := uuid.New()
	secret := "resolv-secret"

	cache := NewHashCache() // empty — no entry for execID

	var gotNamespace string
	resolverCalled := false
	mockExec := &mockExecutor{
		resolveJobSecretHashFn: func(ctx context.Context, namespace string, id uuid.UUID) (string, error) {
			resolverCalled = true
			gotNamespace = namespace
			if namespace == "" {
				return "", errors.New("an empty namespace may not be set")
			}
			return executor.HashSecret(secret), nil
		},
	}

	handler := NewHeartbeatHandler(cache, mockExec, &mockHeartbeater{}, &mockStatusClient{getNamespace: "org-ns"})

	// Body carries no run_id, as an older agent would send.
	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
	assert.True(t, resolverCalled)
	assert.Empty(t, gotNamespace, "with no run_id the recovery read must get an empty namespace")
	_, cached := cache.Get(execID)
	assert.False(t, cached, "a failed recovery must not populate the cache")
}

// A heartbeat can legitimately race the callback (the activity may already be complete by the
// time it arrives), so a RecordHeartbeat error must not surface to the agent as a failure.
func TestHeartbeatHandler_HeartbeaterError_StillReturns200(t *testing.T) {
	execID := uuid.New()
	secret := "test-secret-value"
	hash := executor.HashSecret(secret)

	cache := NewHashCache()
	cache.Put(execID, hash)

	hb := &mockHeartbeater{err: errors.New("activity already completed")}
	handler := NewHeartbeatHandler(cache, nil, hb, nil)

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, execID, hb.recordedExecID)
}

func TestHeartbeatHandler_MethodNotAllowed(t *testing.T) {
	handler := NewHeartbeatHandler(NewHashCache(), nil, &mockHeartbeater{}, nil)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/heartbeat", nil)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusMethodNotAllowed, w.Code)
}

type mockHeartbeater struct {
	recordedExecID uuid.UUID
	err            error
}

func (m *mockHeartbeater) RecordHeartbeat(ctx context.Context, executionID uuid.UUID) error {
	m.recordedExecID = executionID
	return m.err
}
