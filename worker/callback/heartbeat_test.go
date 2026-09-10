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

// The body's run_id must reach the Heartbeater: a Worker that inherited this execution across a
// restart rebuilds the activity's workflow id from it to relay the ping. Dropping it here strands
// every inherited node at its heartbeat timeout.
func TestHeartbeatHandler_PassesRunIDToHeartbeater(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "test-secret-value"

	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret(secret))

	hb := &mockHeartbeater{}
	handler := NewHeartbeatHandler(cache, nil, hb)

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String(), "run_id": runID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, runID, hb.recordedRunID, "handler must thread the body's run_id to RecordHeartbeat")
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

// On a cache miss (a Worker that restarted after launch) the resolver recovers the hash the same
// way the completion callback does, and the recovered value is cached for next time. A multi-tenant
// resolver derives the execution's namespace from the run, so the heartbeat's run_id must reach it
// -- this HTTP path has no Temporal activity context to read the run from.
func TestHeartbeatHandler_CacheMiss_RecoversViaResolver(t *testing.T) {
	execID := uuid.New()
	runID := uuid.New()
	secret := "resolv-secret"
	hash := executor.HashSecret(secret)

	cache := NewHashCache() // empty — no entry for execID

	resolverCalled := false
	var gotRunID uuid.UUID
	mockExec := &mockExecutor{
		resolveJobSecretHashFn: func(ctx context.Context, rid, id uuid.UUID) (string, error) {
			resolverCalled = true
			gotRunID = rid
			assert.Equal(t, execID, id)
			return hash, nil
		},
	}

	hb := &mockHeartbeater{}
	handler := NewHeartbeatHandler(cache, mockExec, hb)

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String(), "run_id": runID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.True(t, resolverCalled)
	assert.Equal(t, runID, gotRunID, "the heartbeat's run_id must reach the resolver so it can resolve the namespace")
	assert.Equal(t, execID, hb.recordedExecID)

	cached, ok := cache.Get(execID)
	assert.True(t, ok)
	assert.Equal(t, hash, cached)
}

// A resolver that cannot recover the hash (e.g. the job-secret Secret is gone) fails closed to
// 401 rather than admitting the request, and must not populate the cache with a bad value.
func TestHeartbeatHandler_CacheMiss_ResolverError_FailsClosed(t *testing.T) {
	execID := uuid.New()
	secret := "resolv-secret"

	cache := NewHashCache() // empty — no entry for execID

	resolverCalled := false
	mockExec := &mockExecutor{
		resolveJobSecretHashFn: func(ctx context.Context, rid, id uuid.UUID) (string, error) {
			resolverCalled = true
			return "", errors.New("no job-secret found")
		},
	}

	handler := NewHeartbeatHandler(cache, mockExec, &mockHeartbeater{})

	body, _ := json.Marshal(map[string]any{"node_execution_id": execID.String()})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/heartbeat", bytes.NewReader(body))
	req.Header.Set("Authorization", "Bearer "+secret)
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, req)

	assert.Equal(t, http.StatusUnauthorized, w.Code)
	assert.True(t, resolverCalled)
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
	recordedRunID  uuid.UUID
	err            error
}

func (m *mockHeartbeater) RecordHeartbeat(ctx context.Context, runID, executionID uuid.UUID) error {
	m.recordedExecID = executionID
	m.recordedRunID = runID
	return m.err
}
