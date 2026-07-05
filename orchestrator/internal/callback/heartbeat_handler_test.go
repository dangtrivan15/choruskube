package callback

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

type mockHeartbeater struct {
	recordedExecID     uuid.UUID
	recordedWorkflowID string
	recordedErr        error
}

func (m *mockHeartbeater) RecordHeartbeat(ctx context.Context, nodeExecID uuid.UUID, workflowID string) error {
	m.recordedExecID = nodeExecID
	m.recordedWorkflowID = workflowID
	return m.recordedErr
}

func TestHeartbeat_ValidRequest(t *testing.T) {
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == "GET" && contains(r.URL.Path, "job-secret-hash"):
			json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	heartbeater := &mockHeartbeater{}
	handler := NewHeartbeatHandler(client, heartbeater)

	body := HeartbeatRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/heartbeat", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, execID, heartbeater.recordedExecID)
	assert.Equal(t, fmt.Sprintf("choruskube-run-%s", runID), heartbeater.recordedWorkflowID)
}

func TestHeartbeat_InvalidSecret(t *testing.T) {
	hash := sha256.Sum256([]byte("correct-secret"))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	handler := NewHeartbeatHandler(client, &mockHeartbeater{})

	body := HeartbeatRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/heartbeat", bytes.NewReader(bodyBytes))
	req.Header.Set("Authorization", "Bearer wrong-secret")
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusUnauthorized, w.Code)
}

func TestHeartbeat_TemporalError_Still200(t *testing.T) {
	jobSecret := "test-secret-value"
	hash := sha256.Sum256([]byte(jobSecret))
	hashStr := hex.EncodeToString(hash[:])

	execID := uuid.New()
	runID := uuid.New()

	apiServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]string{"hash": hashStr})
	}))
	defer apiServer.Close()

	client := apiclient.NewClient(apiServer.URL)
	heartbeater := &mockHeartbeater{
		recordedErr: fmt.Errorf("activity already completed"),
	}
	handler := NewHeartbeatHandler(client, heartbeater)

	body := HeartbeatRequest{
		NodeExecutionID: execID.String(),
		RunID:           runID.String(),
	}
	bodyBytes, _ := json.Marshal(body)

	req := httptest.NewRequest("POST", "/api/v1/heartbeat", bytes.NewReader(bodyBytes))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+jobSecret)
	w := httptest.NewRecorder()

	handler.ServeHTTP(w, req)
	assert.Equal(t, http.StatusOK, w.Code)
}
