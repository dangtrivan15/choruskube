package workload

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestPathsAreRunScopedUnderTheWorkerPrefix pins the request shape of the three workload/
// node-execution calls a Worker makes: each is scoped under /worker/runs/{runID}/node-executions/{execID}.
func TestPathsAreRunScopedUnderTheWorkerPrefix(t *testing.T) {
	runID, nodeExecID := uuid.New(), uuid.New()
	var paths []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.Method+" "+r.URL.Path)
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"image":"img"}`))
	}))
	defer srv.Close()

	c := NewClient(srv.URL, func() string { return "ckw_x" }, srv.Client())
	if _, err := c.PrepareWorkload(context.Background(), PrepareParams{RunID: runID, NodeExecID: nodeExecID}); err != nil {
		t.Fatalf("prepare: %v", err)
	}
	if err := c.CompleteWorkload(context.Background(), CompleteParams{RunID: runID, NodeExecID: nodeExecID}); err != nil {
		t.Fatalf("complete: %v", err)
	}
	if _, err := c.GetNodeExecution(context.Background(), runID, nodeExecID); err != nil {
		t.Fatalf("get node execution: %v", err)
	}

	base := "/worker/runs/" + runID.String() + "/node-executions/" + nodeExecID.String()
	want := []string{"POST " + base + "/workload/prepare", "POST " + base + "/workload/complete", "GET " + base}
	if len(paths) != len(want) {
		t.Fatalf("paths = %v, want %v", paths, want)
	}
	for i := range want {
		if paths[i] != want[i] {
			t.Fatalf("paths = %v, want %v", paths, want)
		}
	}
}

// The credential is rotated by the renewal loop while requests are in flight, so reading it once
// at construction would pin every later request to a credential the server has since replaced.
func TestEveryRequestReadsTheCredentialAfresh(t *testing.T) {
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = append(seen, r.Header.Get("Authorization"))
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{}`))
	}))
	defer srv.Close()

	current := "ckw_first"
	c := NewClient(srv.URL, func() string { return current }, srv.Client())
	_, _ = c.GetNodeExecution(context.Background(), uuid.New(), uuid.New())
	current = "ckw_second"
	_, _ = c.GetNodeExecution(context.Background(), uuid.New(), uuid.New())

	if len(seen) != 2 || seen[0] != "Bearer ckw_first" || seen[1] != "Bearer ckw_second" {
		t.Fatalf("authorization headers = %v", seen)
	}
}

func TestNewClientRejectsANilCredential(t *testing.T) {
	defer func() {
		if recover() == nil {
			t.Fatal("want a panic at construction, not one inside the first request")
		}
	}()
	NewClient("http://api", nil, nil)
}

func TestPrepareWorkload(t *testing.T) {
	resp := PrepareResponse{
		Image:              "ghcr.io/test/agent:latest",
		EnableDocker:       true,
		DindImage:          "registry.example/custom-dind:v2",
		AgentMemoryRequest: "1792Mi",
		DindMemoryRequest:  "256Mi",
		ClaudeOAuthToken:   "tok_test",
		Namespace:          "org-ns",
		ServiceAccount:     "choruskube-agent",
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Contains(t, r.URL.Path, "/workload/prepare")
		assert.Equal(t, "Bearer test-cred", r.Header.Get("Authorization"))
		_ = json.NewEncoder(w).Encode(resp)
	}))
	defer server.Close()

	client := NewClient(server.URL, func() string { return "test-cred" }, nil)
	runID := uuid.New()
	nodeExecID := uuid.New()
	nodeID := uuid.New()

	got, err := client.PrepareWorkload(context.Background(), PrepareParams{
		RunID:          runID,
		NodeExecID:     nodeExecID,
		TemplateNodeID: nodeID,
	})
	require.NoError(t, err)
	assert.Equal(t, "ghcr.io/test/agent:latest", got.Image)
	assert.True(t, got.EnableDocker)
	assert.Equal(t, "registry.example/custom-dind:v2", got.DindImage)
	assert.Equal(t, "1792Mi", got.AgentMemoryRequest)
	assert.Equal(t, "256Mi", got.DindMemoryRequest)
	assert.Equal(t, "org-ns", got.Namespace)
}

// The api-server's PrepareWorkloadResponse record names these fields; a rename on either side
// would silently drop the per-project sizing, so pin the wire names.
func TestPrepareWorkload_DecodesMemoryRequestsByWireName(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"image":"a","agentMemoryRequest":"4Gi","dindMemoryRequest":"3Gi","memoryCeilingExempt":true}`))
	}))
	defer server.Close()

	got, err := NewClient(server.URL, func() string { return "c" }, nil).PrepareWorkload(context.Background(), PrepareParams{
		RunID: uuid.New(), NodeExecID: uuid.New(), TemplateNodeID: uuid.New(),
	})
	require.NoError(t, err)
	assert.Equal(t, "4Gi", got.AgentMemoryRequest)
	assert.Equal(t, "3Gi", got.DindMemoryRequest)
	assert.True(t, got.MemoryCeilingExempt)
}

// An API server that predates the exemption sends no such field; that must decode as capped, so a
// version skew can never lift the ceiling.
func TestPrepareWorkload_AbsentCeilingExemptionDecodesAsCapped(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"image":"a","agentMemoryRequest":"4Gi"}`))
	}))
	defer server.Close()

	got, err := NewClient(server.URL, func() string { return "c" }, nil).PrepareWorkload(context.Background(), PrepareParams{
		RunID: uuid.New(), NodeExecID: uuid.New(), TemplateNodeID: uuid.New(),
	})
	require.NoError(t, err)
	assert.False(t, got.MemoryCeilingExempt)
}

func TestCompleteWorkload(t *testing.T) {
	var received CompleteRequest
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Contains(t, r.URL.Path, "/workload/complete")
		_ = json.NewDecoder(r.Body).Decode(&received)
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	client := NewClient(server.URL, func() string { return "cred" }, nil)
	err := client.CompleteWorkload(context.Background(), CompleteParams{
		RunID:         uuid.New(),
		NodeExecID:    uuid.New(),
		PodName:       "agent-abc123",
		JobSecretHash: "deadbeef",
	})
	require.NoError(t, err)
	assert.Equal(t, "agent-abc123", received.PodName)
	assert.Equal(t, "deadbeef", received.JobSecretHash)
}

func TestGetNodeExecution(t *testing.T) {
	runID, nodeExecID := uuid.New(), uuid.New()
	wantID := uuid.New()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/worker/runs/"+runID.String()+"/node-executions/"+nodeExecID.String(), r.URL.Path)
		assert.Equal(t, "Bearer tok", r.Header.Get("Authorization"))
		_ = json.NewEncoder(w).Encode(NodeExecution{ID: wantID, Status: "running"})
	}))
	defer srv.Close()

	got, err := NewClient(srv.URL, func() string { return "tok" }, srv.Client()).
		GetNodeExecution(context.Background(), runID, nodeExecID)
	require.NoError(t, err)
	assert.Equal(t, wantID, got.ID)
	assert.Equal(t, "running", got.Status)
}

func TestUpdateNodeExecution(t *testing.T) {
	runID, nodeExecID := uuid.New(), uuid.New()
	var received UpdateStatusRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Equal(t, "/worker/runs/"+runID.String()+"/node-executions/"+nodeExecID.String()+"/status", r.URL.Path)
		_ = json.NewDecoder(r.Body).Decode(&received)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	err := NewClient(srv.URL, func() string { return "tok" }, srv.Client()).
		UpdateNodeExecution(context.Background(), runID, nodeExecID, UpdateStatusRequest{
			Status: "completed",
			Result: "ok",
		})
	require.NoError(t, err)
	assert.Equal(t, "completed", received.Status)
	assert.Equal(t, "ok", received.Result)
}

func TestWriteExecutionLog(t *testing.T) {
	runID, nodeExecID := uuid.New(), uuid.New()
	var received WriteLogRequest
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/worker/runs/"+runID.String()+"/node-executions/"+nodeExecID.String()+"/logs", r.URL.Path)
		_ = json.NewDecoder(r.Body).Decode(&received)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	NewClient(srv.URL, func() string { return "tok" }, srv.Client()).
		WriteExecutionLog(context.Background(), runID, nodeExecID, "info", "hello world")
	assert.Equal(t, "info", received.Level)
	assert.Equal(t, "hello world", received.Message)
}

func TestGitHubTokenSendsAuthAndReturnsTheToken(t *testing.T) {
	runID := uuid.New()
	var gotMethod, gotAuth, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotAuth, gotPath = r.Method, r.Header.Get("Authorization"), r.URL.Path
		_, _ = w.Write([]byte(`{"token":"ghs_abc"}`))
	}))
	defer srv.Close()

	tok, err := NewClient(srv.URL, func() string { return "ckw_worker" }, srv.Client()).GitHubToken(context.Background(), runID)

	require.NoError(t, err)
	assert.Equal(t, "ghs_abc", tok)
	assert.Equal(t, http.MethodGet, gotMethod)
	assert.Equal(t, "Bearer ckw_worker", gotAuth)
	assert.Equal(t, "/worker/runs/"+runID.String()+"/github-token", gotPath)
}

func TestGitHubTokenSurfacesAPIFailures(t *testing.T) {
	for _, status := range []int{http.StatusForbidden, http.StatusServiceUnavailable} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(status)
			_, _ = w.Write([]byte(`{"error":"resolver failed"}`))
		}))

		_, err := NewClient(srv.URL, func() string { return "s" }, srv.Client()).GitHubToken(context.Background(), uuid.New())
		srv.Close()

		var apiErr *APIError
		require.ErrorAs(t, err, &apiErr, "status %d", status)
		assert.Equal(t, status, apiErr.StatusCode)
	}
}

func TestGitHubTokenRejectsAMissingToken(t *testing.T) {
	for _, body := range []string{`{}`, `{"token":""}`, `not json`} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(body))
		}))

		tok, err := NewClient(srv.URL, func() string { return "s" }, srv.Client()).GitHubToken(context.Background(), uuid.New())
		srv.Close()

		require.Error(t, err, body)
		assert.Empty(t, tok)
	}
}

func TestGitHubTokenScopeSendsAuthAndDecodesTheResponse(t *testing.T) {
	runID := uuid.New()
	var gotMethod, gotAuth, gotPath string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod, gotAuth, gotPath = r.Method, r.Header.Get("Authorization"), r.URL.Path
		_, _ = w.Write([]byte(`{"repositories":["acme/api","acme/web"],"permissions":{"contents":"write"}}`))
	}))
	defer srv.Close()

	repos, perms, err := NewClient(srv.URL, func() string { return "ckw_worker" }, srv.Client()).
		GitHubTokenScope(context.Background(), runID)

	require.NoError(t, err)
	assert.Equal(t, []string{"acme/api", "acme/web"}, repos)
	assert.Equal(t, map[string]string{"contents": "write"}, perms)
	assert.Equal(t, http.MethodGet, gotMethod)
	assert.Equal(t, "Bearer ckw_worker", gotAuth)
	assert.Equal(t, "/worker/runs/"+runID.String()+"/github-token-scope", gotPath)
}

func TestGitHubTokenScopeAllowsAnEmptyScope(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte(`{"repositories":[],"permissions":{}}`))
	}))
	defer srv.Close()

	repos, perms, err := NewClient(srv.URL, func() string { return "s" }, srv.Client()).
		GitHubTokenScope(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Empty(t, repos)
	assert.Empty(t, perms)
}

func TestGitHubTokenScopeSurfacesAPIFailures(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write([]byte(`{"error":"run not found"}`))
	}))
	defer srv.Close()

	_, _, err := NewClient(srv.URL, func() string { return "s" }, srv.Client()).
		GitHubTokenScope(context.Background(), uuid.New())

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, http.StatusNotFound, apiErr.StatusCode)
}

func TestGitHubTokenScopeRejectsNonJSONWithoutQuotingTheBody(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("not json, and SENSITIVE-BODY-CONTENT"))
	}))
	defer srv.Close()

	_, _, err := NewClient(srv.URL, func() string { return "s" }, srv.Client()).
		GitHubTokenScope(context.Background(), uuid.New())

	require.Error(t, err)
	assert.NotContains(t, err.Error(), "SENSITIVE-BODY-CONTENT")
}
