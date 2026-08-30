package workload

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
)

func TestCreateWorkloadSendsMethodPathAndBody(t *testing.T) {
	runID, nodeExecID, templateNodeID := uuid.New(), uuid.New(), uuid.New()
	var gotMethod, gotPath string
	var gotBody struct {
		TemplateNodeID uuid.UUID              `json:"templateNodeId"`
		ConfigJSON     map[string]interface{} `json:"configJson"`
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotPath = r.Method, r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"executionHandle": "agent-abc123",
			"jobSecretHash":   "hash123",
		})
	}))
	defer srv.Close()

	resp, err := NewClient(srv.URL, "s", srv.Client()).CreateWorkload(context.Background(), CreateWorkloadParams{
		RunID:          runID,
		NodeExecID:     nodeExecID,
		TemplateNodeID: templateNodeID,
		ConfigJSON:     map[string]interface{}{"prompt": "do the thing"},
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotMethod != http.MethodPost {
		t.Fatalf("method = %q, want POST", gotMethod)
	}
	if gotPath != "/internal/workloads/"+runID.String()+"/"+nodeExecID.String() {
		t.Fatalf("unexpected path: %q", gotPath)
	}
	if gotBody.TemplateNodeID != templateNodeID || gotBody.ConfigJSON["prompt"] != "do the thing" {
		t.Fatalf("unexpected request body: %+v", gotBody)
	}
	if resp.ExecutionHandle != "agent-abc123" || resp.JobSecretHash != "hash123" {
		t.Fatalf("unexpected response: %+v", resp)
	}
}

func TestGetWorkloadLogsSendsAuthAndTail(t *testing.T) {
	id := uuid.New()
	var gotAuth, gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotPath, gotQuery = r.Header.Get("Authorization"), r.URL.Path, r.URL.RawQuery
		// The API server wraps logs in a JSON envelope (WorkloadLogsResponse), not raw text —
		// see api-server/src/main/java/com/choruskube/core/dto/WorkloadLogsResponse.java.
		_, _ = w.Write([]byte(`{"logs":"line-1\nline-2\n"}`))
	}))
	defer srv.Close()

	logs, err := NewClient(srv.URL, "orch-secret", srv.Client()).GetWorkloadLogs(context.Background(), id, 50)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if logs != "line-1\nline-2\n" {
		t.Fatalf("unexpected logs: %q", logs)
	}
	if gotAuth != "Bearer orch-secret" {
		t.Fatalf("want bearer auth, got %q", gotAuth)
	}
	if gotPath != "/internal/workloads/"+id.String()+"/logs" {
		t.Fatalf("unexpected path: %q", gotPath)
	}
	if gotQuery != "tailLines=50" {
		t.Fatalf("unexpected query: %q", gotQuery)
	}
}

func TestCleanupWorkloadPropagatesFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	if err := NewClient(srv.URL, "s", srv.Client()).CleanupWorkload(context.Background(), uuid.New()); err == nil {
		t.Fatal("want an error for 500, got nil")
	}
}
