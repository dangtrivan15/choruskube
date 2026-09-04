package callback

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"

	"github.com/dangtrivan15/choruskube/worker/executor"
)

func TestHandler_ValidSecret_CompletesActivity(t *testing.T) {
	execID := uuid.New()
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

	handler := NewHandler(cache, nil, completer)

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
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

func TestHandler_InvalidSecret_Returns401(t *testing.T) {
	execID := uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("correct-secret"))

	handler := NewHandler(cache, nil, &mockCompleter{})

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
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
	})

	body, _ := json.Marshal(map[string]any{
		"node_execution_id": execID.String(),
		"status":            "completed",
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
}

func (m *mockCompleter) Complete(ctx context.Context, req CompletionRequest) error {
	if m.completeFn != nil {
		return m.completeFn(ctx, req)
	}
	return nil
}

type mockExecutor struct {
	resolveJobSecretHashFn func(context.Context, uuid.UUID) (string, error)
}

func (m *mockExecutor) ResolveJobSecretHash(ctx context.Context, id uuid.UUID) (string, error) {
	return m.resolveJobSecretHashFn(ctx, id)
}
