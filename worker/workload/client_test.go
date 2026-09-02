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

	resp, err := NewClient(srv.URL, func() string { return "s" }, srv.Client()).CreateWorkload(context.Background(), CreateWorkloadParams{
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
	if gotPath != "/worker/runs/"+runID.String()+"/node-executions/"+nodeExecID.String()+"/workload" {
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
	runID, nodeExecID := uuid.New(), uuid.New()
	var gotAuth, gotPath, gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth, gotPath, gotQuery = r.Header.Get("Authorization"), r.URL.Path, r.URL.RawQuery
		// The API server wraps logs in a JSON envelope (WorkloadLogsResponse), not raw text —
		// see api-server/src/main/java/com/choruskube/core/dto/WorkloadLogsResponse.java.
		_, _ = w.Write([]byte(`{"logs":"line-1\nline-2\n"}`))
	}))
	defer srv.Close()

	logs, err := NewClient(srv.URL, func() string { return "ckw_worker" }, srv.Client()).
		GetWorkloadLogs(context.Background(), runID, nodeExecID, 50)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if logs != "line-1\nline-2\n" {
		t.Fatalf("unexpected logs: %q", logs)
	}
	if gotAuth != "Bearer ckw_worker" {
		t.Fatalf("want bearer auth, got %q", gotAuth)
	}
	if gotPath != "/worker/runs/"+runID.String()+"/node-executions/"+nodeExecID.String()+"/workload/logs" {
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

	c := NewClient(srv.URL, func() string { return "s" }, srv.Client())
	if err := c.CleanupWorkload(context.Background(), uuid.New(), uuid.New()); err == nil {
		t.Fatal("want an error for 500, got nil")
	}
}

func TestPathsAreRunScopedUnderTheWorkerPrefix(t *testing.T) {
	runID, nodeExecID := uuid.New(), uuid.New()
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"logs":"out"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, func() string { return "ckw_x" }, srv.Client())
	if err := c.CleanupWorkload(context.Background(), runID, nodeExecID); err != nil {
		t.Fatalf("cleanup: %v", err)
	}
	if _, err := c.GetWorkloadLogs(context.Background(), runID, nodeExecID, 10); err != nil {
		t.Fatalf("logs: %v", err)
	}

	base := "/worker/runs/" + runID.String() + "/node-executions/" + nodeExecID.String() + "/workload"
	want := []string{"DELETE " + base, "GET " + base + "/logs"}
	if len(paths) != 2 || paths[0] != want[0] || paths[1] != want[1] {
		t.Fatalf("paths = %v, want %v", paths, want)
	}
}

// The credential is rotated by the renewal loop while requests are in flight, so reading it once
// at construction would pin every later request to a credential the server has since replaced.
func TestEveryRequestReadsTheCredentialAfresh(t *testing.T) {
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Header.Get("Authorization"))
		w.WriteHeader(http.StatusNoContent)
	}))
	defer srv.Close()

	current := "ckw_first"
	c := NewClient(srv.URL, func() string { return current }, srv.Client())
	_ = c.CleanupWorkload(context.Background(), uuid.New(), uuid.New())
	current = "ckw_second"
	_ = c.CleanupWorkload(context.Background(), uuid.New(), uuid.New())

	if len(seen) != 2 || seen[0] != "Bearer ckw_first" || seen[1] != "Bearer ckw_second" {
		t.Fatalf("authorization headers = %v", seen)
	}
}
