package apiclient

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/state"
)

func newTestServer(t *testing.T, handler http.HandlerFunc) (*httptest.Server, *Client) {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	client := NewClient(server.URL)
	return server, client
}

func TestCreateNodeExecution(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()
	templateNodeID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Contains(t, r.URL.Path, "/internal/runs/"+runID.String()+"/node-executions")
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))

		var body createNodeExecRequest
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, templateNodeID, body.TemplateNodeID)
		assert.Equal(t, 1, body.GraphVersion)

		w.WriteHeader(http.StatusCreated)
		json.NewEncoder(w).Encode(nodeExecResponse{
			ID:             execID,
			TemplateNodeID: templateNodeID,
			Status:         "pending",
			Iteration:      1,
			GraphVersion:   1,
			ArtifactRefs:   "{}",
		})
	})

	exec, err := client.CreateNodeExecution(context.Background(), runID, state.CreateNodeExecutionParams{
		WorkflowRunID:  runID,
		TemplateNodeID: templateNodeID,
		GraphVersion:   1,
		Iteration:      1,
	})
	require.NoError(t, err)
	assert.Equal(t, execID, exec.ID)
	assert.Equal(t, "pending", exec.Status)
}

func TestUpdateNodeExecution(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Contains(t, r.URL.Path, "/status")

		var body updateNodeExecRequest
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "running", body.Status)

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(nodeExecResponse{
			ID:           execID,
			Status:       "running",
			ArtifactRefs: "{}",
		})
	})

	err := client.UpdateNodeExecution(context.Background(), runID, execID, state.UpdateNodeExecutionParams{
		Status: "running",
	})
	require.NoError(t, err)
}

func TestUpdateWorkflowRunStatus(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Equal(t, "/internal/runs/"+runID.String()+"/status", r.URL.Path)

		var body map[string]string
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "running", body["status"])

		w.WriteHeader(http.StatusNoContent)
	})

	err := client.UpdateWorkflowRunStatus(context.Background(), runID, "running")
	require.NoError(t, err)
}

func TestGetGraphRuntime(t *testing.T) {
	runID := uuid.New()
	snapshot := `{"nodes":[],"edges":[]}`

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/internal/runs/"+runID.String()+"/graph-runtime", r.URL.Path)

		w.WriteHeader(http.StatusOK)
		w.Write([]byte(snapshot))
	})

	result, err := client.GetGraphRuntime(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, snapshot, result)
}

func TestGetBranchCleanupPlan_DecodesTheJavaShapedBody(t *testing.T) {
	runID := uuid.New()
	repoID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/internal/runs/"+runID.String()+"/branch-cleanup-plan", r.URL.Path)
		fmt.Fprintf(w, `{"repos":[{"gitRepoId":"%s","ownerRepo":"acme/api","defaultBranch":"main"}]}`, repoID)
	})

	plan, err := client.GetBranchCleanupPlan(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, BranchCleanupPlanResponse{
		Repos: []BranchCleanupPlanRepo{{GitRepoID: repoID, OwnerRepo: "acme/api", DefaultBranch: "main"}},
	}, plan)
}

// Mirrors TestGateFinalize_RejectionsAreNonRetryable for the branch-cleanup-plan endpoint, which
// classifies its own rejections independently (BranchCleanupPlanRejectedType, not
// GateFinalizeRejectedType) because it is not one of the gate-finalize routes.
func TestGetBranchCleanupPlan_RejectionsAreNonRetryable(t *testing.T) {
	for _, status := range []int{http.StatusBadRequest, http.StatusNotFound, http.StatusConflict} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				w.Write([]byte("rejected"))
			})

			_, err := client.GetBranchCleanupPlan(context.Background(), uuid.New())

			appErr, ok := err.(*temporal.ApplicationError)
			require.Truef(t, ok, "want the *ApplicationError itself, got %T: %v", err, err)
			assert.True(t, appErr.NonRetryable())
			assert.Equal(t, BranchCleanupPlanRejectedType, appErr.Type())

			var apiErr *APIError
			require.True(t, errors.As(err, &apiErr))
			assert.Equal(t, status, apiErr.StatusCode)
		})
	}
}

func TestGetBranchCleanupPlan_ServerAndNetworkFailuresStayRetryable(t *testing.T) {
	for _, status := range []int{http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
			})

			_, err := client.GetBranchCleanupPlan(context.Background(), uuid.New())

			require.Error(t, err)
			var appErr *temporal.ApplicationError
			assert.False(t, errors.As(err, &appErr), "retryable errors must not be application errors")
			var apiErr *APIError
			require.True(t, errors.As(err, &apiErr))
			assert.Equal(t, status, apiErr.StatusCode)
		})
	}

	t.Run("network", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
		client := NewClient(server.URL)
		server.Close()

		_, err := client.GetBranchCleanupPlan(context.Background(), uuid.New())

		require.Error(t, err)
		var appErr *temporal.ApplicationError
		assert.False(t, errors.As(err, &appErr))
		var apiErr *APIError
		assert.False(t, errors.As(err, &apiErr))
	})
}

func TestMarkTaskIssueClosed_PostsTheIssueNumberWithTheOrchestratorSecret(t *testing.T) {
	taskID := uuid.New()
	var calls int
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/internal/tasks/"+taskID.String()+"/github-issue/closed", r.URL.Path)
		assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))
		raw, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, `{"issueNumber":42}`, string(raw))
		w.WriteHeader(http.StatusNoContent)
	}))
	t.Cleanup(server.Close)
	client := NewAuthenticatedClient(server.URL, "orchestrator-secret")

	require.NoError(t, client.MarkTaskIssueClosed(context.Background(), taskID, 42))
	assert.Equal(t, 1, calls)
}

func TestMarkTaskIssueClosed_RejectionsAreNonRetryable(t *testing.T) {
	for _, status := range []int{http.StatusBadRequest, http.StatusNotFound, http.StatusConflict} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				w.Write([]byte("rejected"))
			})

			err := client.MarkTaskIssueClosed(context.Background(), uuid.New(), 7)

			appErr, ok := err.(*temporal.ApplicationError)
			require.Truef(t, ok, "want the *ApplicationError itself, got %T: %v", err, err)
			assert.True(t, appErr.NonRetryable())
			assert.Equal(t, TaskIssueRejectedType, appErr.Type())

			var apiErr *APIError
			require.True(t, errors.As(err, &apiErr))
			assert.Equal(t, status, apiErr.StatusCode)
		})
	}
}

func TestMarkTaskIssueClosed_ServerAndNetworkFailuresStayRetryable(t *testing.T) {
	for _, status := range []int{http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
			})

			err := client.MarkTaskIssueClosed(context.Background(), uuid.New(), 7)

			require.Error(t, err)
			var appErr *temporal.ApplicationError
			assert.False(t, errors.As(err, &appErr), "retryable errors must not be application errors")
			var apiErr *APIError
			require.True(t, errors.As(err, &apiErr))
			assert.Equal(t, status, apiErr.StatusCode)
		})
	}

	t.Run("network", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
		client := NewClient(server.URL)
		server.Close()

		err := client.MarkTaskIssueClosed(context.Background(), uuid.New(), 7)

		require.Error(t, err)
		var appErr *temporal.ApplicationError
		assert.False(t, errors.As(err, &appErr))
		var apiErr *APIError
		assert.False(t, errors.As(err, &apiErr))
	})
}

func TestWriteExecutionLog(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Contains(t, r.URL.Path, "/logs")

		var body map[string]string
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, "info", body["level"])
		assert.Equal(t, "test message", body["message"])

		w.WriteHeader(http.StatusCreated)
	})

	err := client.WriteExecutionLog(context.Background(), runID, execID, "info", "test message")
	require.NoError(t, err)
}

func TestGetCompletedPredecessors(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()
	predNodeID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/predecessors")

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode([]predecessorArtifactsResponse{
			{TemplateNodeID: predNodeID, ArtifactRefs: `{"spec":"runs/123/out/spec.md"}`},
		})
	})

	preds, err := client.GetCompletedPredecessors(context.Background(), runID, execID)
	require.NoError(t, err)
	require.Len(t, preds, 1)
	assert.Equal(t, predNodeID, preds[0].TemplateNodeID)
}

func TestGetJobSecretHash(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/job-secret-hash")

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(jobSecretHashResponse{Hash: "abc123"})
	})

	hash, err := client.GetJobSecretHash(context.Background(), runID, execID)
	require.NoError(t, err)
	assert.Equal(t, "abc123", hash)
}

func TestGetRunNamespace(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/internal/runs/"+runID.String()+"/placement")

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(RunNamespace{Namespace: "tenant-ns"})
	})

	result, err := client.GetRunNamespace(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, "tenant-ns", result.Namespace)
}

func TestListNamespaces(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/internal/placements", r.URL.Path)

		w.WriteHeader(http.StatusOK)
		json.NewEncoder(w).Encode(struct {
			Namespaces []string `json:"namespaces"`
		}{Namespaces: []string{"choruskube", "tenant-ns"}})
	})

	result, err := client.ListNamespaces(context.Background())
	require.NoError(t, err)
	assert.Equal(t, []string{"choruskube", "tenant-ns"}, result)
}

func TestAuthenticatedClient_SendsBearerToken(t *testing.T) {
	runID := uuid.New()
	snapshot := `{"nodes":[],"edges":[]}`

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Verify the Authorization header is set
		assert.Equal(t, "Bearer my-secret-token", r.Header.Get("Authorization"))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(snapshot))
	})
	// Override with authenticated client pointing at same server
	client.bearerToken = "my-secret-token"

	result, err := client.GetGraphRuntime(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, snapshot, result)
}

func TestUnauthenticatedClient_NoBearerToken(t *testing.T) {
	runID := uuid.New()
	snapshot := `{"nodes":[],"edges":[]}`

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Verify no Authorization header is set
		assert.Empty(t, r.Header.Get("Authorization"))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(snapshot))
	})

	result, err := client.GetGraphRuntime(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, snapshot, result)
}

func TestAPIError(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte("not found"))
	})

	_, err := client.GetGraphRuntime(context.Background(), runID)
	require.Error(t, err)
	assert.Contains(t, err.Error(), "api error 404")

	// Verify the error is a typed *APIError extractable via errors.As
	var apiErr *APIError
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, http.StatusNotFound, apiErr.StatusCode)
	assert.Equal(t, "not found", apiErr.Body)
}

func TestAPIError_429_ReturnsTypedError(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		w.Write([]byte(`{"error":"quota_exceeded","quotaType":"monthly_runs","current":500,"limit":500}`))
	})

	_, err := client.GetGraphRuntime(context.Background(), runID)
	require.Error(t, err)

	var apiErr *APIError
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, http.StatusTooManyRequests, apiErr.StatusCode)
	assert.Contains(t, apiErr.Body, "quota_exceeded")
}

// TestGetReviewHistory_IncludesFeedbackFields is the regression test for the bug where
// the orchestrator's review-history decode struct silently dropped the reviewer's
// feedback text (and status/nodeLabel) before it ever reached the {review_history}
// prompt variable. It also covers null-safety (an AI-authored entry with no human
// feedback yet) and ordering (multiple loop iterations must accumulate in sequence).
func TestGetReviewHistory_IncludesFeedbackFields(t *testing.T) {
	runID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/internal/runs/"+runID.String()+"/review-history")
		assert.Equal(t, "proposal-review", r.URL.Query().Get("loopGroup"))

		w.WriteHeader(http.StatusOK)
		fmt.Fprint(w, `[
			{
				"id": "`+uuid.New().String()+`",
				"loopGroup": "proposal-review",
				"iteration": 1,
				"reviewerType": "ai",
				"decision": "",
				"result": null,
				"status": null,
				"artifactRefs": "{}",
				"nodeLabel": null,
				"timestamp": "2026-01-01T00:00:00Z"
			},
			{
				"id": "`+uuid.New().String()+`",
				"loopGroup": "proposal-review",
				"iteration": 1,
				"reviewerType": "human",
				"decision": "rejected",
				"result": "Please avoid duplicating the CSV import epic",
				"status": "completed",
				"artifactRefs": "{}",
				"nodeLabel": "roadmap_human_gate",
				"timestamp": "2026-01-01T00:05:00Z"
			}
		]`)
	})

	reviews, err := client.GetReviewHistory(context.Background(), runID, "proposal-review")
	require.NoError(t, err)
	require.Len(t, reviews, 2)

	// Entry 1: AI-authored, no human feedback yet — must decode nullable fields
	// to "" without error, and must not be reordered relative to entry 2.
	assert.Equal(t, "ai", reviews[0].ReviewerType)
	assert.Empty(t, reviews[0].Result)
	assert.Empty(t, reviews[0].Status)
	assert.Empty(t, reviews[0].NodeLabel)

	// Entry 2: human feedback — this is the seam that previously dropped the data.
	assert.Equal(t, "human", reviews[1].ReviewerType)
	assert.Equal(t, "Please avoid duplicating the CSV import epic", reviews[1].Result)
	assert.Equal(t, "completed", reviews[1].Status)
	assert.Equal(t, "roadmap_human_gate", reviews[1].NodeLabel)
}

func TestGetInputArtifacts(t *testing.T) {
	runID := uuid.New()
	execID := uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Contains(t, r.URL.Path, "/input-artifacts")

		// Field names must match the Java InputArtifactManifest record exactly — this is the
		// contract between the api-server resolver and the agent's download loop.
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"artifacts": {
				"spec_review/spec_and_plan.md": "system/runs/r/e/out/spec_and_plan.md",
				"approve_spec_and_plan/human_guidance.md": "system/runs/r/gate-attachments/g/human_guidance.md"
			},
			"required": ["spec_review/spec_and_plan.md"]
		}`))
	})

	manifest, err := client.GetInputArtifacts(context.Background(), runID, execID)
	require.NoError(t, err)
	assert.Equal(t, "system/runs/r/e/out/spec_and_plan.md", manifest.Artifacts["spec_review/spec_and_plan.md"])
	assert.Equal(t,
		"system/runs/r/gate-attachments/g/human_guidance.md",
		manifest.Artifacts["approve_spec_and_plan/human_guidance.md"])
	assert.Equal(t, []string{"spec_review/spec_and_plan.md"}, manifest.Required)
}

func TestGetInputArtifactsEmptyManifest(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"artifacts":{},"required":[]}`))
	})

	manifest, err := client.GetInputArtifacts(context.Background(), uuid.New(), uuid.New())
	require.NoError(t, err)
	assert.Empty(t, manifest.Artifacts)
	assert.Empty(t, manifest.Required)
}

func TestUpdateNodeExecution_SendsFinalizeModeOnlyWhenSet(t *testing.T) {
	async := "async"
	cases := []struct {
		name string
		mode *string
		want map[string]any
	}{
		{"set", &async, map[string]any{"status": "awaiting_human", "finalizeMode": "async"}},
		{"nil", nil, map[string]any{"status": "awaiting_human"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				var got map[string]any
				require.NoError(t, json.NewDecoder(r.Body).Decode(&got))
				assert.Equal(t, tc.want, got)
				w.WriteHeader(http.StatusOK)
			})
			err := client.UpdateNodeExecution(context.Background(), uuid.New(), uuid.New(), state.UpdateNodeExecutionParams{
				Status:       "awaiting_human",
				FinalizeMode: tc.mode,
			})
			require.NoError(t, err)
		})
	}
}

// --- Gate finalize ---

func gateFinalizePath(runID, execID uuid.UUID, rest string) string {
	return fmt.Sprintf("/internal/runs/%s/node-executions/%s/gate-finalize%s", runID, execID, rest)
}

func TestClaimHeldApproval(t *testing.T) {
	runID, execID, approvalID := uuid.New(), uuid.New(), uuid.New()

	cases := []struct {
		name string
		body string
		want string
	}{
		{"a held approval", fmt.Sprintf(`{"approvalId":"%s"}`, approvalID), approvalID.String()},
		{"none held", `{"approvalId":null}`, ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, http.MethodPost, r.Method)
				assert.Equal(t, gateFinalizePath(runID, execID, "/held"), r.URL.Path)
				assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
				w.WriteHeader(http.StatusOK)
				w.Write([]byte(tc.body))
			}))
			defer server.Close()
			client := NewAuthenticatedClient(server.URL, "orchestrator-secret")

			got, err := client.ClaimHeldApproval(context.Background(), runID, execID)
			require.NoError(t, err)
			assert.Equal(t, tc.want, got)
		})
	}
}

func TestPlanGateFinalize_MapsComparesAndPullRequests(t *testing.T) {
	runID, execID, approvalID := uuid.New(), uuid.New(), uuid.New()
	repoID, rowID := uuid.New(), uuid.New()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, gateFinalizePath(runID, execID, "/"+approvalID.String()+"/plan"), r.URL.Path)
		assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
		w.WriteHeader(http.StatusOK)
		fmt.Fprintf(w, `{
			"outcome": "proceed",
			"reason": null,
			"compares": [{"gitRepoId": "%s", "ownerRepo": "acme/api", "branch": "main", "baseSha": "abc123"}],
			"prs": [{"rowId": "%s", "ownerRepo": "acme/api", "number": 42, "label": "api", "prUrl": "https://github.com/acme/api/pull/42"}],
			"mergeMethod": "squash"
		}`, repoID, rowID)
	}))
	defer server.Close()
	client := NewAuthenticatedClient(server.URL, "orchestrator-secret")

	plan, err := client.PlanGateFinalize(context.Background(), runID, execID, approvalID)
	require.NoError(t, err)
	assert.Equal(t, GatePlanResponse{
		Outcome:     "proceed",
		Compares:    []GateCompareItem{{GitRepoID: repoID, OwnerRepo: "acme/api", Branch: "main", BaseSha: "abc123"}},
		PRs:         []GatePRItem{{RowID: rowID, OwnerRepo: "acme/api", Number: 42, Label: "api", PRURL: "https://github.com/acme/api/pull/42"}},
		MergeMethod: "squash",
	}, plan)
}

func TestPlanGateFinalize_HoldCarriesNoWork(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"outcome":"hold","reason":"main moved","compares":[],"prs":[],"mergeMethod":null}`))
	})

	plan, err := client.PlanGateFinalize(context.Background(), uuid.New(), uuid.New(), uuid.New())
	require.NoError(t, err)
	assert.Equal(t, "hold", plan.Outcome)
	assert.Equal(t, "main moved", plan.Reason)
	assert.Empty(t, plan.Compares)
	assert.Empty(t, plan.PRs)
	assert.Equal(t, "", plan.MergeMethod)
}

func TestHoldGateApproval(t *testing.T) {
	runID, execID, approvalID, repoID := uuid.New(), uuid.New(), uuid.New(), uuid.New()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, gateFinalizePath(runID, execID, "/"+approvalID.String()+"/hold"), r.URL.Path)
		assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
		var got map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&got))
		assert.Equal(t, map[string]any{
			"compares": []any{map[string]any{"gitRepoId": repoID.String(), "aheadBy": float64(3), "baseGone": false}},
		}, got)
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"outcome":"held","result":"main moved; re-planning","attachmentRefs":"{\"guidance\":\"k\"}","reason":null}`))
	}))
	defer server.Close()
	client := NewAuthenticatedClient(server.URL, "orchestrator-secret")

	res, err := client.HoldGateApproval(context.Background(), runID, execID, approvalID,
		[]GateCompareResultItem{{GitRepoID: repoID, AheadBy: 3}})
	require.NoError(t, err)
	assert.Equal(t, GateStepResponse{
		Outcome:        "held",
		Result:         "main moved; re-planning",
		AttachmentRefs: `{"guidance":"k"}`,
	}, res)
}

func TestHoldGateApproval_NoComparesSendsAnEmptyList(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, `{"compares":[]}`, string(body))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"outcome":"refused","reason":"round cap"}`))
	})

	res, err := client.HoldGateApproval(context.Background(), uuid.New(), uuid.New(), uuid.New(), nil)
	require.NoError(t, err)
	assert.Equal(t, GateStepResponse{Outcome: "refused", Reason: "round cap"}, res)
}

func TestCompleteGateFinalize(t *testing.T) {
	runID, execID, approvalID := uuid.New(), uuid.New(), uuid.New()
	merged, skipped := uuid.New(), uuid.New()
	at := time.Date(2026, 10, 11, 9, 30, 0, 0, time.UTC)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, gateFinalizePath(runID, execID, "/"+approvalID.String()+"/complete"), r.URL.Path)
		assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(`{"merges":[
			{"rowId":"%s","kind":"MERGED","mergedAt":"2026-10-11T09:30:00Z","mergeSha":"deadbeef"},
			{"rowId":"%s","kind":"SKIPPED_CLOSED"}
		], "issues":[]}`, merged, skipped), string(body))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"outcome":"completed","result":"approved and merged","attachmentRefs":"{}","reason":null}`))
	}))
	defer server.Close()
	client := NewAuthenticatedClient(server.URL, "orchestrator-secret")

	res, err := client.CompleteGateFinalize(context.Background(), runID, execID, approvalID, []GatePRResultItem{
		{RowID: merged, Kind: "MERGED", MergedAt: &at, MergeSha: "deadbeef"},
		{RowID: skipped, Kind: "SKIPPED_CLOSED"},
	}, nil)
	require.NoError(t, err)
	assert.Equal(t, GateStepResponse{Outcome: "completed", Result: "approved and merged", AttachmentRefs: "{}"}, res)
}

// TestCompleteGateFinalize_SendsIssueResults: the split path's per-task results are sent through
// unchanged, and an empty Issues still encodes as [], never null.
func TestCompleteGateFinalize_SendsIssueResults(t *testing.T) {
	runID, execID, approvalID, taskID := uuid.New(), uuid.New(), uuid.New(), uuid.New()

	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(
			`{"merges":[],"issues":[{"taskId":"%s","number":1,"url":"https://github.com/acme/api/issues/1"}]}`, taskID),
			string(body))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"outcome":"completed","result":"done","attachmentRefs":"{}","reason":null}`))
	})

	_, err := client.CompleteGateFinalize(context.Background(), runID, execID, approvalID, nil,
		[]GateIssueResultItem{{TaskID: taskID, Number: 1, URL: "https://github.com/acme/api/issues/1"}})
	require.NoError(t, err)
}

func TestMaterializeGateApproval_SendsTheMergesAndDecodesTheIssues(t *testing.T) {
	runID, execID, approvalID, rowID, taskID, repoID := uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New(), uuid.New()
	since := time.Date(2026, 10, 14, 9, 0, 0, 0, time.UTC)

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, gateFinalizePath(runID, execID, "/"+approvalID.String()+"/materialize"), r.URL.Path)
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(`{"merges":[{"rowId":"%s","kind":"MERGED"}]}`, rowID), string(body))
		fmt.Fprintf(w, `{
			"since": "2026-10-14T09:00:00Z",
			"issues": [{"taskId":"%s","gitRepoId":"%s","ownerRepo":"acme/api","title":"t","body":"b"}]
		}`, taskID, repoID)
	}))
	defer server.Close()
	client := NewAuthenticatedClient(server.URL, "orchestrator-secret")

	res, err := client.MaterializeGateApproval(context.Background(), runID, execID, approvalID,
		[]GatePRResultItem{{RowID: rowID, Kind: "MERGED"}})
	require.NoError(t, err)
	assert.Equal(t, GateMaterializeResponse{
		Since:  since,
		Issues: []GateIssueItem{{TaskID: taskID, GitRepoID: repoID, OwnerRepo: "acme/api", Title: "t", Body: "b"}},
	}, res)
}

func TestMaterializeGateApproval_NoMergesSendsAnEmptyList(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, `{"merges":[]}`, string(body))
		w.Write([]byte(`{"since":"2026-10-14T09:00:00Z","issues":[]}`))
	})

	res, err := client.MaterializeGateApproval(context.Background(), uuid.New(), uuid.New(), uuid.New(), nil)
	require.NoError(t, err)
	assert.Empty(t, res.Issues)
}

// TestPlanGateFinalize_WritesRoadmapDecodesFalseWhenAbsent: an API that predates the split write
// step omits the field, and the zero value is what keeps the workflow on one-step complete.
func TestPlanGateFinalize_WritesRoadmapDecodesFalseWhenAbsent(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"outcome":"proceed","reason":null,"compares":[],"prs":[],"mergeMethod":null}`))
	})

	plan, err := client.PlanGateFinalize(context.Background(), uuid.New(), uuid.New(), uuid.New())
	require.NoError(t, err)
	assert.False(t, plan.WritesRoadmap)
}

func TestPlanGateFinalize_DecodesWritesRoadmap(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"outcome":"proceed","reason":null,"compares":[],"prs":[],"mergeMethod":null,"writesRoadmap":true}`))
	})

	plan, err := client.PlanGateFinalize(context.Background(), uuid.New(), uuid.New(), uuid.New())
	require.NoError(t, err)
	assert.True(t, plan.WritesRoadmap)
}

func TestRefuseGateApproval(t *testing.T) {
	runID, execID, approvalID, rowID := uuid.New(), uuid.New(), uuid.New(), uuid.New()

	cases := []struct {
		name   string
		merges []GatePRResultItem
		want   string
	}{
		{"with the merges already made", []GatePRResultItem{{RowID: rowID, Kind: "ALREADY_MERGED"}},
			fmt.Sprintf(`{"reason":"checks failed","merges":[{"rowId":"%s","kind":"ALREADY_MERGED"}]}`, rowID)},
		{"with none", nil, `{"reason":"checks failed"}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, http.MethodPost, r.Method)
				assert.Equal(t, gateFinalizePath(runID, execID, "/"+approvalID.String()+"/refuse"), r.URL.Path)
				assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
				body, err := io.ReadAll(r.Body)
				require.NoError(t, err)
				assert.JSONEq(t, tc.want, string(body))
				w.WriteHeader(http.StatusNoContent)
			}))
			defer server.Close()
			client := NewAuthenticatedClient(server.URL, "orchestrator-secret")

			require.NoError(t, client.RefuseGateApproval(context.Background(), runID, execID, approvalID, "checks failed", tc.merges))
		})
	}
}

// gateFinalizeCalls runs every gate-finalize client method against client, so one table can pin
// the error classes of all five.
func gateFinalizeCalls(ctx context.Context, client *Client) map[string]func() error {
	run, exec, approval := uuid.New(), uuid.New(), uuid.New()
	return map[string]func() error{
		"held": func() error { _, err := client.ClaimHeldApproval(ctx, run, exec); return err },
		"plan": func() error { _, err := client.PlanGateFinalize(ctx, run, exec, approval); return err },
		"hold": func() error { _, err := client.HoldGateApproval(ctx, run, exec, approval, nil); return err },
		"complete": func() error {
			_, err := client.CompleteGateFinalize(ctx, run, exec, approval, nil, nil)
			return err
		},
		"materialize": func() error {
			_, err := client.MaterializeGateApproval(ctx, run, exec, approval, nil)
			return err
		},
		"refuse": func() error { return client.RefuseGateApproval(ctx, run, exec, approval, "no", nil) },
	}
}

// The Temporal SDK classifies an activity error by its concrete type, not by errors.As, so a
// rejection must come back as the *ApplicationError itself: wrapped, it would be retried.
func TestGateFinalize_RejectionsAreNonRetryable(t *testing.T) {
	for _, status := range []int{http.StatusBadRequest, http.StatusNotFound, http.StatusConflict} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				w.Write([]byte("rejected"))
			})
			for name, call := range gateFinalizeCalls(context.Background(), client) {
				t.Run(name, func(t *testing.T) {
					err := call()
					appErr, ok := err.(*temporal.ApplicationError)
					require.Truef(t, ok, "want the *ApplicationError itself, got %T: %v", err, err)
					assert.True(t, appErr.NonRetryable())
					assert.Equal(t, GateFinalizeRejectedType, appErr.Type())
					assert.Contains(t, appErr.Error(), fmt.Sprint(status))
					assert.Contains(t, appErr.Error(), "rejected")

					var apiErr *APIError
					require.True(t, errors.As(err, &apiErr), "the cause keeps the status for callers")
					assert.Equal(t, status, apiErr.StatusCode)
				})
			}
		})
	}
}

func TestGateFinalize_ServerAndNetworkFailuresStayRetryable(t *testing.T) {
	for _, status := range []int{http.StatusInternalServerError, http.StatusBadGateway, http.StatusServiceUnavailable} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
			})
			for name, call := range gateFinalizeCalls(context.Background(), client) {
				t.Run(name, func(t *testing.T) {
					err := call()
					require.Error(t, err)
					var appErr *temporal.ApplicationError
					assert.False(t, errors.As(err, &appErr), "retryable errors must not be application errors")
					var apiErr *APIError
					require.True(t, errors.As(err, &apiErr))
					assert.Equal(t, status, apiErr.StatusCode)
				})
			}
		})
	}

	t.Run("network", func(t *testing.T) {
		server := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}))
		client := NewClient(server.URL)
		server.Close()
		for name, call := range gateFinalizeCalls(context.Background(), client) {
			t.Run(name, func(t *testing.T) {
				err := call()
				require.Error(t, err)
				var appErr *temporal.ApplicationError
				assert.False(t, errors.As(err, &appErr))
				var apiErr *APIError
				assert.False(t, errors.As(err, &apiErr))
			})
		}
	})
}

// Only the gate-finalize endpoints carry the ruling; any other call keeps its retry class.
func TestGateFinalize_OtherEndpointsKeepTheirRetryClass(t *testing.T) {
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
	})
	_, err := client.GetGraphRuntime(context.Background(), uuid.New())
	require.Error(t, err)
	var appErr *temporal.ApplicationError
	assert.False(t, errors.As(err, &appErr))
}

// A gate step can legitimately outlast the client's default timeout (a large roadmap write), so
// the activity's own deadline must be the only bound on it. The default client's timeout is
// shrunk here so the test need not wait the real 30 seconds.
func TestGateFinalize_IsNotCutShortByTheDefaultClientTimeout(t *testing.T) {
	const handlerTime = 300 * time.Millisecond
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-time.After(handlerTime):
		case <-r.Context().Done():
			return
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{}`))
	})
	client.httpClient.Timeout = handlerTime / 6

	for name, call := range gateFinalizeCalls(context.Background(), client) {
		t.Run(name, func(t *testing.T) {
			require.NoError(t, call())
		})
	}

	t.Run("every other call keeps the default timeout", func(t *testing.T) {
		_, err := client.GetGraphRuntime(context.Background(), uuid.New())
		require.Error(t, err)
		var netErr interface{ Timeout() bool }
		require.True(t, errors.As(err, &netErr) && netErr.Timeout(), "want the client timeout, got %v", err)
	})
}

func TestGateFinalize_TheContextStillBoundsTheCall(t *testing.T) {
	release := make(chan struct{})
	_, client := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		// The server only notices a client that hung up once the request body is read, so a
		// handler that never reads it would outlive the call and block server.Close.
		_, _ = io.Copy(io.Discard, r.Body)
		select {
		case <-r.Context().Done():
		case <-release:
		}
	})
	t.Cleanup(func() { close(release) })

	t.Run("deadline", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), 100*time.Millisecond)
		defer cancel()
		for name, call := range gateFinalizeCalls(ctx, client) {
			t.Run(name, func(t *testing.T) {
				start := time.Now()
				err := call()
				require.ErrorIs(t, err, context.DeadlineExceeded)
				assert.Less(t, time.Since(start), 5*time.Second)
				var appErr *temporal.ApplicationError
				assert.False(t, errors.As(err, &appErr), "an expired deadline is retryable")
			})
		}
	})

	t.Run("cancelled", func(t *testing.T) {
		ctx, cancel := context.WithCancel(context.Background())
		cancel()
		for name, call := range gateFinalizeCalls(ctx, client) {
			t.Run(name, func(t *testing.T) {
				require.ErrorIs(t, call(), context.Canceled)
			})
		}
	})
}
