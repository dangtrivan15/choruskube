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
	handler := NewHeartbeatHandler(cache, nil, hb)

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

	handler := NewHeartbeatHandler(cache, nil, &mockHeartbeater{})

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer wrong-secret")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestHeartbeatHandler_CacheMiss_ResolvesFromExecutor(t *testing.T) {
	execID := uuid.New()
	secret := "resolv-secret"
	hash := executor.HashSecret(secret)

	cache := NewHashCache() // empty — no entry for execID

	resolverCalled := false
	mockExec := &mockExecutor{
		resolveJobSecretHashFn: func(ctx context.Context, namespace string, id uuid.UUID) (string, error) {
			resolverCalled = true
			assert.Equal(t, execID, id)
			// The heartbeat body carries no runID, so no namespace can be resolved for recovery;
			// the executor is still consulted, with an empty namespace, and here recovers the hash.
			assert.Empty(t, namespace)
			return hash, nil
		},
	}

	hb := &mockHeartbeater{}
	handler := NewHeartbeatHandler(cache, mockExec, hb)

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.True(t, resolverCalled)
	assert.Equal(t, execID, hb.recordedExecID)

	cached, ok := cache.Get(execID)
	assert.True(t, ok)
	assert.Equal(t, hash, cached)
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
	handler := NewHeartbeatHandler(cache, nil, hb)

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, execID, hb.recordedExecID)
}

func TestHeartbeatHandler_MethodNotAllowed(t *testing.T) {
	handler := NewHeartbeatHandler(NewHashCache(), nil, &mockHeartbeater{})

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
