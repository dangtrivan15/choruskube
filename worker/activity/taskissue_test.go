package activity

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/worker/credential"
	"github.com/dangtrivan15/choruskube/worker/github"
)

// stubTaskIssueWorkflowID makes activityInfo report workflowID. Never combine with t.Parallel:
// the seam is process-wide.
func stubTaskIssueWorkflowID(t *testing.T, workflowID string) {
	t.Helper()
	original := activityInfo
	activityInfo = func(context.Context) temporalactivity.Info {
		return temporalactivity.Info{WorkflowExecution: workflow.Execution{ID: workflowID}}
	}
	t.Cleanup(func() { activityInfo = original })
}

type taskIssueRequest struct{ method, path, auth, body string }

// taskIssueGitHub answers every request with status and records what it received.
type taskIssueGitHub struct {
	srv    *httptest.Server
	mu     sync.Mutex
	status int
	seen   []taskIssueRequest
}

func newTaskIssueGitHub(t *testing.T) *taskIssueGitHub {
	t.Helper()
	g := &taskIssueGitHub{status: http.StatusOK}
	g.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		raw, _ := io.ReadAll(r.Body)
		g.mu.Lock()
		g.seen = append(g.seen, taskIssueRequest{r.Method, r.URL.Path, r.Header.Get("Authorization"), string(raw)})
		status := g.status
		g.mu.Unlock()
		w.WriteHeader(status)
		_, _ = io.WriteString(w, `{"message":"body text that must not leak"}`)
	}))
	t.Cleanup(g.srv.Close)
	return g
}

func (g *taskIssueGitHub) requests() []taskIssueRequest {
	g.mu.Lock()
	defer g.mu.Unlock()
	return append([]taskIssueRequest(nil), g.seen...)
}

func (g *taskIssueGitHub) answer(status int) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.status = status
}

// localTaskIssueProvider stands in for the local provider: one token, whatever the run.
type localTaskIssueProvider struct{ calls int }

const localTaskIssueToken = "ghs_local_worker_token"

func (p *localTaskIssueProvider) GitHubToken(context.Context, uuid.UUID) (credential.Token, error) {
	p.calls++
	return credential.Token{Value: localTaskIssueToken}, nil
}

func (p *localTaskIssueProvider) AgentCredentials(context.Context, uuid.UUID, credential.Agent, bool) (credential.Agent, error) {
	return credential.Agent{}, nil
}

type taskIssueRig struct {
	acts   *Activities
	gh     *taskIssueGitHub
	prov   *fakeGateProvider
	params TaskIssueCloseParams
}

// newTaskIssueRig binds the activity context to params.TaskID's own close workflow.
func newTaskIssueRig(t *testing.T) *taskIssueRig {
	t.Helper()
	gh := newTaskIssueGitHub(t)
	prov := &fakeGateProvider{}
	acts := &Activities{}
	acts.SetGitHub(github.New(gh.srv.URL))
	acts.SetCredentials(prov)
	p := TaskIssueCloseParams{TaskID: uuid.New(), RunID: uuid.New(), OwnerRepo: "acme/app", IssueNumber: 42}
	stubTaskIssueWorkflowID(t, taskIssueCloseWorkflowIDPrefix+p.TaskID.String())
	return &taskIssueRig{acts: acts, gh: gh, prov: prov, params: p}
}

func requireNonRetryable(t *testing.T, err error, errType string) {
	t.Helper()
	var appErr *temporal.ApplicationError
	require.ErrorAs(t, err, &appErr)
	assert.True(t, appErr.NonRetryable(), "error must not be retried: %v", err)
	assert.Equal(t, errType, appErr.Type())
}

func requireRetryable(t *testing.T, err error) {
	t.Helper()
	require.Error(t, err)
	var appErr *temporal.ApplicationError
	if errors.As(err, &appErr) {
		assert.False(t, appErr.NonRetryable(), "error must be retried: %v", err)
	}
}

func TestCloseTaskIssue_MatchingWorkflowClosesTheIssueWithTheProvidersToken(t *testing.T) {
	r := newTaskIssueRig(t)

	res, err := r.acts.CloseTaskIssue(context.Background(), r.params)

	require.NoError(t, err)
	assert.Equal(t, TaskIssueCloseResult{Outcome: TaskIssueClosed, Status: 0}, res)
	reqs := r.gh.requests()
	require.Len(t, reqs, 1)
	assert.Equal(t, http.MethodPatch, reqs[0].method)
	assert.Equal(t, "/repos/acme/app/issues/42", reqs[0].path)
	assert.Equal(t, "Bearer "+gateToken, reqs[0].auth)
	assert.JSONEq(t, `{"state":"closed"}`, reqs[0].body)
}

func TestCloseTaskIssue_AsksTheProviderForTheRunNotTheTask(t *testing.T) {
	r := newTaskIssueRig(t)

	_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

	require.NoError(t, err)
	assert.Equal(t, []uuid.UUID{r.params.RunID}, r.prov.calls)
}

func TestCloseTaskIssue_RefusesAWorkflowIDThatContradictsTheTask(t *testing.T) {
	for name, workflowID := range map[string]string{
		"another task":   taskIssueCloseWorkflowIDPrefix + uuid.NewString(),
		"a run workflow": "choruskube-run-" + uuid.NewString(),
		"garbage":        "not-a-workflow-id",
		"no task id":     taskIssueCloseWorkflowIDPrefix + "nope",
		"empty":          "",
	} {
		t.Run(name, func(t *testing.T) {
			r := newTaskIssueRig(t)
			stubTaskIssueWorkflowID(t, workflowID)

			_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

			requireNonRetryable(t, err, "TaskIssueCloseBinding")
			assert.Empty(t, r.gh.requests(), "a refused binding must never reach GitHub")
			assert.Empty(t, r.prov.calls, "a refused binding must never fetch a token")
		})
	}
}

func TestCloseTaskIssue_GitHubRefusalsAreRefusedNotRetried(t *testing.T) {
	for _, status := range []int{
		http.StatusNotFound, http.StatusForbidden, http.StatusGone, http.StatusUnprocessableEntity,
		http.StatusUnauthorized, http.StatusMovedPermanently,
	} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			r := newTaskIssueRig(t)
			r.gh.answer(status)

			res, err := r.acts.CloseTaskIssue(context.Background(), r.params)

			require.NoError(t, err)
			assert.Equal(t, TaskIssueCloseResult{Outcome: TaskIssueRefused, Status: status}, res)
		})
	}
}

func TestCloseTaskIssue_GitHubTransientStatusesAreRetried(t *testing.T) {
	for _, status := range []int{
		http.StatusInternalServerError, http.StatusBadGateway, http.StatusTooManyRequests, http.StatusRequestTimeout,
	} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			r := newTaskIssueRig(t)
			r.gh.answer(status)

			_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

			requireRetryable(t, err)
			var apiErr *github.APIError
			require.ErrorAs(t, err, &apiErr)
			assert.Equal(t, status, apiErr.Status)
			assert.NotContains(t, err.Error(), "must not leak")
			assert.NotContains(t, err.Error(), gateToken)
		})
	}
}

func TestCloseTaskIssue_TransportFailureIsRetried(t *testing.T) {
	r := newTaskIssueRig(t)
	r.gh.srv.Close()

	_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

	requireRetryable(t, err)
	var apiErr *github.APIError
	assert.False(t, errors.As(err, &apiErr))
}

func TestCloseTaskIssue_ProviderErrorIsRetriedWithoutCallingGitHub(t *testing.T) {
	r := newTaskIssueRig(t)
	r.prov.err = errors.New("token route unavailable")

	_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

	requireRetryable(t, err)
	assert.Contains(t, err.Error(), "obtain GitHub token for run "+r.params.RunID.String())
	assert.Empty(t, r.gh.requests())
}

func TestCloseTaskIssue_BlankTokenIsRetriedWithoutCallingGitHub(t *testing.T) {
	r := newTaskIssueRig(t)
	r.acts.SetCredentials(blankTokenProvider{})

	_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

	requireRetryable(t, err)
	assert.Empty(t, r.gh.requests())
}

func TestCloseTaskIssue_MissingCredentialsOrClientIsRetried(t *testing.T) {
	t.Run("credentials", func(t *testing.T) {
		r := newTaskIssueRig(t)
		r.acts.SetCredentials(nil)

		_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

		requireRetryable(t, err)
		assert.Empty(t, r.gh.requests())
	})
	t.Run("client", func(t *testing.T) {
		r := newTaskIssueRig(t)
		r.acts.SetGitHub(nil)

		_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

		requireRetryable(t, err)
		assert.Empty(t, r.prov.calls)
	})
}

func TestCloseTaskIssue_CancelledContextReturnsItsError(t *testing.T) {
	r := newTaskIssueRig(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := r.acts.CloseTaskIssue(ctx, r.params)

	assert.ErrorIs(t, err, context.Canceled)
	assert.Equal(t, context.Canceled, err)
}

func TestCloseTaskIssue_InvalidParamsAreNotRetried(t *testing.T) {
	for name, mutate := range map[string]func(p *TaskIssueCloseParams){
		"nil run id":       func(p *TaskIssueCloseParams) { p.RunID = uuid.Nil },
		"blank owner/repo": func(p *TaskIssueCloseParams) { p.OwnerRepo = "  " },
		"no owner":         func(p *TaskIssueCloseParams) { p.OwnerRepo = "app" },
		"too many slashes": func(p *TaskIssueCloseParams) { p.OwnerRepo = "acme/app/x" },
		"dot segment":      func(p *TaskIssueCloseParams) { p.OwnerRepo = "acme/.." },
		"issue 0":          func(p *TaskIssueCloseParams) { p.IssueNumber = 0 },
		"negative issue":   func(p *TaskIssueCloseParams) { p.IssueNumber = -3 },
	} {
		t.Run(name, func(t *testing.T) {
			r := newTaskIssueRig(t)
			p := r.params
			mutate(&p)

			_, err := r.acts.CloseTaskIssue(context.Background(), p)

			requireNonRetryable(t, err, "TaskIssueCloseParams")
			assert.Empty(t, r.gh.requests())
			assert.Empty(t, r.prov.calls)
		})
	}
}

func TestCloseTaskIssue_LocalProviderIgnoringTheRunStillCloses(t *testing.T) {
	r := newTaskIssueRig(t)
	local := &localTaskIssueProvider{}
	r.acts.SetCredentials(local)

	res, err := r.acts.CloseTaskIssue(context.Background(), r.params)

	require.NoError(t, err)
	assert.Equal(t, TaskIssueClosed, res.Outcome)
	assert.Equal(t, 1, local.calls)
	reqs := r.gh.requests()
	require.Len(t, reqs, 1)
	assert.Equal(t, "Bearer "+localTaskIssueToken, reqs[0].auth)
}

func TestCloseTaskIssue_LogsNeverCarryTheToken(t *testing.T) {
	for _, status := range []int{http.StatusOK, http.StatusNotFound} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			r := newTaskIssueRig(t)
			logs := captureLogs(t)
			r.gh.answer(status)

			_, err := r.acts.CloseTaskIssue(context.Background(), r.params)

			require.NoError(t, err)
			out := logs.String()
			assert.Contains(t, out, "task issue close")
			assert.Contains(t, out, r.params.TaskID.String())
			assert.NotContains(t, out, gateToken)
			assert.NotContains(t, out, "must not leak")
		})
	}
}

// The orchestrator's mirror of these types must marshal to the same bytes.
func TestTaskIssueClose_WireShapes(t *testing.T) {
	taskID := uuid.MustParse("11111111-1111-1111-1111-111111111111")
	runID := uuid.MustParse("22222222-2222-2222-2222-222222222222")

	params, err := json.Marshal(TaskIssueCloseParams{TaskID: taskID, RunID: runID, OwnerRepo: "o/r", IssueNumber: 42})
	require.NoError(t, err)
	assert.Equal(t,
		`{"taskId":"11111111-1111-1111-1111-111111111111","runId":"22222222-2222-2222-2222-222222222222","ownerRepo":"o/r","issueNumber":42}`,
		string(params))

	closed, err := json.Marshal(TaskIssueCloseResult{Outcome: TaskIssueClosed, Status: 0})
	require.NoError(t, err)
	assert.Equal(t, `{"outcome":"CLOSED","status":0}`, string(closed))

	refused, err := json.Marshal(TaskIssueCloseResult{Outcome: TaskIssueRefused, Status: 404})
	require.NoError(t, err)
	assert.Equal(t, `{"outcome":"REFUSED","status":404}`, string(refused))
}

func TestTaskIssueClose_WorkflowIDPrefixIsPinned(t *testing.T) {
	assert.Equal(t, "choruskube-task-issue-close-", taskIssueCloseWorkflowIDPrefix)
	assert.False(t, strings.HasPrefix(taskIssueCloseWorkflowIDPrefix, workflowIDPrefix),
		"a task issue close id must never parse as a run's")
}

func TestCloseTaskIssue_IsRegisteredByName(t *testing.T) {
	r := newTaskIssueRig(t)

	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	Register(env, r.acts)

	val, err := env.ExecuteActivity("CloseTaskIssue", r.params)
	require.NoError(t, err)
	var res TaskIssueCloseResult
	require.NoError(t, val.Get(&res))
	assert.Equal(t, TaskIssueCloseResult{Outcome: TaskIssueClosed, Status: 0}, res)
}
