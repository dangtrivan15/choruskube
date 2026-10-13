package activity

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// branchCleanupFixture is a run plus an api-server double that records its requests.
type branchCleanupFixture struct {
	acts  *Activities
	runID uuid.UUID
	hits  *atomic.Int32
}

func newBranchCleanupFixture(t *testing.T, handler http.HandlerFunc) branchCleanupFixture {
	t.Helper()
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
		handler(w, r)
	}))
	t.Cleanup(server.Close)
	return branchCleanupFixture{
		acts:  NewActivities(apiclient.NewAuthenticatedClient(server.URL, "orchestrator-secret"), nil, nil, nil),
		runID: uuid.New(),
		hits:  &hits,
	}
}

func TestPlanBranchCleanup_GetsThePlanForTheBoundRun(t *testing.T) {
	repoID := uuid.New()
	var f branchCleanupFixture
	f = newBranchCleanupFixture(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/internal/runs/"+f.runID.String()+"/branch-cleanup-plan", r.URL.Path)
		fmt.Fprintf(w, `{"repos":[{"gitRepoId":"%s","ownerRepo":"acme/api","defaultBranch":"main"}]}`, repoID)
	})

	plan, err := f.acts.PlanBranchCleanup(withWorkflowRunID(t, f.runID), BranchCleanupPlanParams{RunID: f.runID})

	require.NoError(t, err)
	assert.Equal(t, BranchCleanupPlan{
		Repos: []BranchCleanupRepo{{GitRepoID: repoID, OwnerRepo: "acme/api", DefaultBranch: "main"}},
	}, plan)
}

func TestPlanBranchCleanup_RefusesAnotherRunsID(t *testing.T) {
	f := newBranchCleanupFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"repos":[]}`))
	})

	_, err := f.acts.PlanBranchCleanup(withWorkflowRunID(t, uuid.New()), BranchCleanupPlanParams{RunID: f.runID})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "but was scheduled by run")
	assert.Zero(t, f.hits.Load(), "a run's workflow must not plan cleanup for another run")
}

func TestPlanBranchCleanup_RejectionsAreNonRetryable(t *testing.T) {
	for _, status := range []int{http.StatusNotFound, http.StatusConflict} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			f := newBranchCleanupFixture(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
			})
			withWorkflowRunID(t, f.runID)
			var suite testsuite.WorkflowTestSuite
			env := suite.NewTestActivityEnvironment()
			env.RegisterActivity(f.acts)

			_, err := env.ExecuteActivity(f.acts.PlanBranchCleanup, BranchCleanupPlanParams{RunID: f.runID})

			require.Error(t, err)
			var appErr *temporal.ApplicationError
			require.True(t, errors.As(err, &appErr))
			assert.True(t, appErr.NonRetryable())
			assert.Equal(t, apiclient.BranchCleanupPlanRejectedType, appErr.Type())
		})
	}
}

func TestPlanBranchCleanup_ServerErrorsStayRetryable(t *testing.T) {
	f := newBranchCleanupFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	withWorkflowRunID(t, f.runID)
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	env.RegisterActivity(f.acts)

	_, err := env.ExecuteActivity(f.acts.PlanBranchCleanup, BranchCleanupPlanParams{RunID: f.runID})

	require.Error(t, err)
	var appErr *temporal.ApplicationError
	ok := errors.As(err, &appErr)
	if ok {
		assert.False(t, appErr.NonRetryable())
	}
}

// CleanupRunBranches is dispatched to the Worker's queue. The orchestrator still registers the
// stub so the workflow can name it, and must fail loudly if it ever receives the task.
func TestCleanupRunBranches_IsRegisteredButNeverRunsHere(t *testing.T) {
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	env.RegisterActivity(NewActivities(nil, nil, nil, nil))

	_, err := env.ExecuteActivity("CleanupRunBranches", BranchCleanupParams{RunID: uuid.New()})

	require.Error(t, err)
	assert.Contains(t, err.Error(), "dispatched to Worker task queue")
}

// --- Worker wire contract ---
//
// The Worker's own test asserts the identical literal JSON for its copies of these types.
// Temporal's JSON converter makes the tags the wire between the two processes.

func TestBranchCleanupTypes_KeepTheWorkersJSONKeys(t *testing.T) {
	repo := uuid.MustParse("11111111-1111-1111-1111-111111111111")
	run := uuid.MustParse("22222222-2222-2222-2222-222222222222")

	for name, tc := range map[string]struct {
		value any
		want  string
	}{
		"BranchCleanupRepo": {
			BranchCleanupRepo{GitRepoID: repo, OwnerRepo: "acme/api", DefaultBranch: "main"},
			`{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","defaultBranch":"main"}`,
		},
		"BranchCleanupParams": {
			BranchCleanupParams{RunID: run, Repos: []BranchCleanupRepo{{GitRepoID: repo, OwnerRepo: "acme/api", DefaultBranch: "main"}}},
			`{"runId":"22222222-2222-2222-2222-222222222222",
			  "repos":[{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","defaultBranch":"main"}]}`,
		},
		"BranchCleanupOutcome": {
			BranchCleanupOutcome{GitRepoID: repo, OwnerRepo: "acme/api", Outcome: BranchCleanupDeleted},
			`{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","outcome":"DELETED"}`,
		},
		"BranchCleanupResult": {
			BranchCleanupResult{Outcomes: []BranchCleanupOutcome{{GitRepoID: repo, OwnerRepo: "acme/api", Outcome: BranchCleanupKeptAhead}}},
			`{"outcomes":[{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","outcome":"KEPT_AHEAD"}]}`,
		},
	} {
		t.Run(name, func(t *testing.T) {
			got, err := json.Marshal(tc.value)
			require.NoError(t, err)
			assert.JSONEq(t, tc.want, string(got))
		})
	}
}
