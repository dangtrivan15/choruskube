package activity

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/converter"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// gateFixture is a run's gate execution plus an api-server double that records its requests.
type gateFixture struct {
	acts     *Activities
	runID    uuid.UUID
	execID   uuid.UUID
	approval uuid.UUID
	hits     *atomic.Int32
}

func newGateFixture(t *testing.T, handler http.HandlerFunc) gateFixture {
	t.Helper()
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		assert.Equal(t, "Bearer orchestrator-secret", r.Header.Get("Authorization"))
		handler(w, r)
	}))
	t.Cleanup(server.Close)
	return gateFixture{
		acts:     NewActivities(apiclient.NewAuthenticatedClient(server.URL, "orchestrator-secret"), nil, nil, nil),
		runID:    uuid.New(),
		execID:   uuid.New(),
		approval: uuid.New(),
		hits:     &hits,
	}
}

func (f gateFixture) path(rest string) string {
	return fmt.Sprintf("/internal/runs/%s/node-executions/%s/gate-finalize%s", f.runID, f.execID, rest)
}

func (f gateFixture) approvalRef() GateApprovalRef {
	return GateApprovalRef{RunID: f.runID, NodeExecutionID: f.execID, ApprovalID: f.approval.String()}
}

func TestClaimHeldApproval_ReturnsTheApprovalIDOrNone(t *testing.T) {
	approval := uuid.New()
	for name, tc := range map[string]struct{ body, want string }{
		"held": {fmt.Sprintf(`{"approvalId":"%s"}`, approval), approval.String()},
		"none": {`{"approvalId":null}`, ""},
	} {
		t.Run(name, func(t *testing.T) {
			f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
				assert.Equal(t, http.MethodPost, r.Method)
				w.Write([]byte(tc.body))
			})
			got, err := f.acts.ClaimHeldApproval(withWorkflowRunID(t, f.runID), GateRef{RunID: f.runID, NodeExecutionID: f.execID})
			require.NoError(t, err)
			assert.Equal(t, tc.want, got)
		})
	}
}

func TestPlanGateFinalize_MapsThePlanIntoTheWorkersTypes(t *testing.T) {
	repoID, rowID := uuid.New(), uuid.New()
	var f gateFixture
	f = newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, f.path("/"+f.approval.String()+"/plan"), r.URL.Path)
		fmt.Fprintf(w, `{
			"outcome": "proceed", "reason": null,
			"compares": [{"gitRepoId": "%s", "ownerRepo": "acme/api", "branch": "main", "baseSha": "abc123"}],
			"prs": [{"rowId": "%s", "ownerRepo": "acme/api", "number": 42, "label": "api", "prUrl": "https://github.com/acme/api/pull/42"}],
			"mergeMethod": "squash"
		}`, repoID, rowID)
	})

	plan, err := f.acts.PlanGateFinalize(withWorkflowRunID(t, f.runID), f.approvalRef())
	require.NoError(t, err)
	assert.Equal(t, GatePlan{
		Outcome:     GatePlanProceed,
		Compares:    []GateCompare{{GitRepoID: repoID, OwnerRepo: "acme/api", Branch: "main", BaseSha: "abc123"}},
		PRs:         []GatePR{{RowID: rowID, OwnerRepo: "acme/api", Number: 42, Label: "api", PRURL: "https://github.com/acme/api/pull/42"}},
		MergeMethod: "squash",
	}, plan)
}

func TestPlanGateFinalize_HoldAndRefusedCarryNoWork(t *testing.T) {
	f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"outcome":"refused","reason":"nothing to merge","compares":[],"prs":[],"mergeMethod":null}`))
	})

	plan, err := f.acts.PlanGateFinalize(withWorkflowRunID(t, f.runID), f.approvalRef())
	require.NoError(t, err)
	assert.Equal(t, GatePlan{
		Outcome:  GatePlanRefused,
		Reason:   "nothing to merge",
		Compares: []GateCompare{},
		PRs:      []GatePR{},
	}, plan)
}

func TestHoldGateApproval_SendsTheComparesAndMapsTheResult(t *testing.T) {
	repoID := uuid.New()
	var f gateFixture
	f = newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, f.path("/"+f.approval.String()+"/hold"), r.URL.Path)
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(`{"compares":[{"gitRepoId":"%s","aheadBy":2,"baseGone":true}]}`, repoID), string(body))
		w.Write([]byte(`{"outcome":"held","result":"main moved","attachmentRefs":"{\"g\":\"k\"}","reason":null}`))
	})

	res, err := f.acts.HoldGateApproval(withWorkflowRunID(t, f.runID), GateHoldParams{
		GateApprovalRef: f.approvalRef(),
		Compares:        []GateCompareResult{{GitRepoID: repoID, AheadBy: 2, BaseGone: true}},
	})
	require.NoError(t, err)
	assert.Equal(t, GateStepResult{Outcome: GateStepHeld, Result: "main moved", AttachmentRefs: `{"g":"k"}`}, res)
}

func TestCompleteGateFinalize_SendsTheMergesAndMapsTheResult(t *testing.T) {
	rowID := uuid.New()
	at := time.Date(2026, 10, 11, 9, 30, 0, 0, time.UTC)
	var f gateFixture
	f = newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, f.path("/"+f.approval.String()+"/complete"), r.URL.Path)
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(
			`{"merges":[{"rowId":"%s","kind":"MERGED","mergedAt":"2026-10-11T09:30:00Z","mergeSha":"deadbeef"}],"issues":[]}`, rowID), string(body))
		w.Write([]byte(`{"outcome":"completed","result":"merged","attachmentRefs":"{}","reason":null}`))
	})

	res, err := f.acts.CompleteGateFinalize(withWorkflowRunID(t, f.runID), GateCompleteParams{
		GateApprovalRef: f.approvalRef(),
		Merges:          []GatePRResult{{RowID: rowID, Kind: "MERGED", MergedAt: &at, MergeSha: "deadbeef"}},
	})
	require.NoError(t, err)
	assert.Equal(t, GateStepResult{Outcome: GateStepCompleted, Result: "merged", AttachmentRefs: "{}"}, res)
}

func TestCompleteGateFinalize_SendsIssueResults(t *testing.T) {
	taskID := uuid.New()
	var f gateFixture
	f = newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(`{"merges":[],"issues":[{"taskId":"%s","failure":"the issue step kept failing"}]}`, taskID), string(body))
		w.Write([]byte(`{"outcome":"completed","result":"done","attachmentRefs":"{}","reason":null}`))
	})

	_, err := f.acts.CompleteGateFinalize(withWorkflowRunID(t, f.runID), GateCompleteParams{
		GateApprovalRef: f.approvalRef(),
		Issues:          []GateIssueResult{{TaskID: taskID, Failure: "the issue step kept failing"}},
	})
	require.NoError(t, err)
}

// MaterializeGateApproval posts to /materialize and decodes the claim time and issue requests.
func TestMaterializeGateApproval_PostsAndDecodesTheResponse(t *testing.T) {
	repoID, rowID, taskID := uuid.New(), uuid.New(), uuid.New()
	since := time.Date(2026, 10, 14, 9, 0, 0, 0, time.UTC)
	var f gateFixture
	f = newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, f.path("/"+f.approval.String()+"/materialize"), r.URL.Path)
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(`{"merges":[{"rowId":"%s","kind":"MERGED"}]}`, rowID), string(body))
		fmt.Fprintf(w, `{"since":"2026-10-14T09:00:00Z","issues":[
			{"taskId":"%s","gitRepoId":"%s","ownerRepo":"acme/api","title":"t","body":"b"}
		]}`, taskID, repoID)
	})

	m, err := f.acts.MaterializeGateApproval(withWorkflowRunID(t, f.runID), GateMaterializeParams{
		GateApprovalRef: f.approvalRef(),
		Merges:          []GatePRResult{{RowID: rowID, Kind: "MERGED"}},
	})
	require.NoError(t, err)
	assert.Equal(t, GateMaterialized{
		Since:  since,
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: repoID, OwnerRepo: "acme/api", Title: "t", Body: "b"}},
	}, m)
}

// MaterializeGateApproval's own call to the API is never retried once it has left the activity: a
// 409 answers non-retryable, pinned separately from the shared table above since it never shares
// a request body with another gate-finalize step.
func TestMaterializeGateApproval_ConflictIsNonRetryable(t *testing.T) {
	f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
	})
	_, err := f.acts.MaterializeGateApproval(withWorkflowRunID(t, f.runID), GateMaterializeParams{GateApprovalRef: f.approvalRef()})
	appErr, ok := err.(*temporal.ApplicationError)
	require.Truef(t, ok, "want the *ApplicationError itself, got %T: %v", err, err)
	assert.True(t, appErr.NonRetryable())
}

func TestMaterializeGateApproval_ServerErrorIsRetryable(t *testing.T) {
	f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})
	_, err := f.acts.MaterializeGateApproval(withWorkflowRunID(t, f.runID), GateMaterializeParams{GateApprovalRef: f.approvalRef()})
	require.Error(t, err)
	var appErr *temporal.ApplicationError
	assert.False(t, errors.As(err, &appErr), "retryable errors must not be application errors")
}

func TestRefuseGateApproval_SendsTheReasonAndTheMergesAlreadyMade(t *testing.T) {
	rowID := uuid.New()
	var f gateFixture
	f = newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, f.path("/"+f.approval.String()+"/refuse"), r.URL.Path)
		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, fmt.Sprintf(`{"reason":"checks failed","merges":[{"rowId":"%s","kind":"ALREADY_MERGED"}]}`, rowID), string(body))
		w.WriteHeader(http.StatusNoContent)
	})

	err := f.acts.RefuseGateApproval(withWorkflowRunID(t, f.runID), GateRefuseParams{
		GateApprovalRef: f.approvalRef(),
		Reason:          "checks failed",
		Merges:          []GatePRResult{{RowID: rowID, Kind: "ALREADY_MERGED"}},
	})
	require.NoError(t, err)
}

// gateActivityCalls runs each gate-finalize activity for f's execution with approval as the
// approval id the workflow names.
func gateActivityCalls(f gateFixture, ctxRun uuid.UUID, approval string) map[string]func(t *testing.T) error {
	ref := GateApprovalRef{RunID: f.runID, NodeExecutionID: f.execID, ApprovalID: approval}
	return map[string]func(t *testing.T) error{
		"ClaimHeldApproval": func(t *testing.T) error {
			_, err := f.acts.ClaimHeldApproval(withWorkflowRunID(t, ctxRun), GateRef{RunID: f.runID, NodeExecutionID: f.execID})
			return err
		},
		"PlanGateFinalize": func(t *testing.T) error {
			_, err := f.acts.PlanGateFinalize(withWorkflowRunID(t, ctxRun), ref)
			return err
		},
		"HoldGateApproval": func(t *testing.T) error {
			_, err := f.acts.HoldGateApproval(withWorkflowRunID(t, ctxRun), GateHoldParams{GateApprovalRef: ref})
			return err
		},
		"CompleteGateFinalize": func(t *testing.T) error {
			_, err := f.acts.CompleteGateFinalize(withWorkflowRunID(t, ctxRun), GateCompleteParams{GateApprovalRef: ref})
			return err
		},
		"MaterializeGateApproval": func(t *testing.T) error {
			_, err := f.acts.MaterializeGateApproval(withWorkflowRunID(t, ctxRun), GateMaterializeParams{GateApprovalRef: ref})
			return err
		},
		"RefuseGateApproval": func(t *testing.T) error {
			return f.acts.RefuseGateApproval(withWorkflowRunID(t, ctxRun), GateRefuseParams{GateApprovalRef: ref, Reason: "no"})
		},
	}
}

func TestGateFinalizeActivities_RefuseAForeignRunBeforeCallingTheAPI(t *testing.T) {
	f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{}`))
	})
	for name, call := range gateActivityCalls(f, uuid.New(), f.approval.String()) {
		t.Run(name, func(t *testing.T) {
			err := call(t)
			require.Error(t, err)
			assert.Contains(t, err.Error(), "but was scheduled by run")
		})
	}
	assert.Zero(t, f.hits.Load(), "a run's workflow must not reach another run's gate")
}

func TestGateFinalizeActivities_RefuseAnApprovalIDThatIsNotAUUID(t *testing.T) {
	f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{}`))
	})
	for name, call := range gateActivityCalls(f, f.runID, "../../other-run") {
		if name == "ClaimHeldApproval" {
			continue // takes no approval id
		}
		t.Run(name, func(t *testing.T) {
			err := call(t)
			appErr, ok := err.(*temporal.ApplicationError)
			require.Truef(t, ok, "want the *ApplicationError itself, got %T: %v", err, err)
			assert.True(t, appErr.NonRetryable())
		})
	}
	assert.Zero(t, f.hits.Load(), "an id that is not a UUID must never be put into a URL")
}

// The SDK sorts an activity's error by concrete type once it leaves the activity, so the
// classification is only real if it survives that crossing.
func TestGateFinalizeActivities_ErrorClassSurvivesTheActivityBoundary(t *testing.T) {
	for _, tc := range []struct {
		status       int
		nonRetryable bool
	}{
		{http.StatusBadRequest, true},
		{http.StatusNotFound, true},
		{http.StatusConflict, true},
		{http.StatusInternalServerError, false},
		{http.StatusServiceUnavailable, false},
	} {
		t.Run(fmt.Sprint(tc.status), func(t *testing.T) {
			f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
			})
			withWorkflowRunID(t, f.runID)
			var suite testsuite.WorkflowTestSuite
			env := suite.NewTestActivityEnvironment()
			env.RegisterActivity(f.acts)

			cases := map[string]func() error{
				"PlanGateFinalize": func() error {
					_, err := env.ExecuteActivity(f.acts.PlanGateFinalize, f.approvalRef())
					return err
				},
				"CompleteGateFinalize": func() error {
					_, err := env.ExecuteActivity(f.acts.CompleteGateFinalize, GateCompleteParams{GateApprovalRef: f.approvalRef()})
					return err
				},
				"MaterializeGateApproval": func() error {
					_, err := env.ExecuteActivity(f.acts.MaterializeGateApproval, GateMaterializeParams{GateApprovalRef: f.approvalRef()})
					return err
				},
			}
			for name, call := range cases {
				t.Run(name, func(t *testing.T) {
					err := call()
					require.Error(t, err)
					var appErr *temporal.ApplicationError
					require.True(t, errors.As(err, &appErr))
					assert.Equal(t, tc.nonRetryable, appErr.NonRetryable())
				})
			}
		})
	}
}

func TestUpdateNodeExecutionStatus_PassesTheFinalizeMode(t *testing.T) {
	async := "async"
	for name, tc := range map[string]struct {
		mode *string
		want map[string]any
	}{
		"set": {&async, map[string]any{"status": "awaiting_human", "finalizeMode": "async"}},
		"nil": {nil, map[string]any{"status": "awaiting_human"}},
	} {
		t.Run(name, func(t *testing.T) {
			f := newGateFixture(t, func(w http.ResponseWriter, r *http.Request) {
				var got map[string]any
				require.NoError(t, json.NewDecoder(r.Body).Decode(&got))
				assert.Equal(t, tc.want, got)
			})
			err := f.acts.UpdateNodeExecutionStatus(withWorkflowRunID(t, f.runID), UpdateNodeExecStatusParams{
				RunID: f.runID, NodeExecutionID: f.execID, Status: "awaiting_human", FinalizeMode: tc.mode,
			})
			require.NoError(t, err)
		})
	}
}

// RunGateGitHubSteps is dispatched to the Worker's queue. The orchestrator still registers the
// stub so the workflow can name it, and must fail loudly if it ever receives the task.
func TestRunGateGitHubSteps_IsRegisteredButNeverRunsHere(t *testing.T) {
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	env.RegisterActivity(NewActivities(nil, nil, nil, nil))

	_, err := env.ExecuteActivity("RunGateGitHubSteps", GateGitHubParams{RunID: uuid.New()})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "dispatched to Worker task queue")
}

// FileGateIssues is dispatched to the Worker's queue, exactly like RunGateGitHubSteps.
func TestFileGateIssues_IsRegisteredButNeverRunsHere(t *testing.T) {
	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	env.RegisterActivity(NewActivities(nil, nil, nil, nil))

	_, err := env.ExecuteActivity("FileGateIssues", GateIssueParams{RunID: uuid.New()})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "dispatched to Worker task queue")
}

// --- Worker wire contract ---
//
// The Gate* types below are copies of the Worker's. Temporal's JSON converter makes their tags
// the wire between the two processes, so each is pinned to the exact keys the Worker emits.

func TestWorkerMirroredTypes_KeepTheWorkersJSONKeys(t *testing.T) {
	repo := uuid.MustParse("11111111-1111-1111-1111-111111111111")
	row := uuid.MustParse("22222222-2222-2222-2222-222222222222")
	run := uuid.MustParse("33333333-3333-3333-3333-333333333333")
	gate := uuid.MustParse("44444444-4444-4444-4444-444444444444")
	at := time.Date(2026, 10, 11, 9, 30, 0, 0, time.UTC)

	task := uuid.MustParse("55555555-5555-5555-5555-555555555555")
	compare := GateCompare{GitRepoID: repo, OwnerRepo: "acme/api", Branch: "main", BaseSha: "abc"}
	pr := GatePR{RowID: row, OwnerRepo: "acme/api", Number: 7, Label: "api", PRURL: "https://example.test/pull/7"}
	compareResult := GateCompareResult{GitRepoID: repo, AheadBy: 2, BaseGone: true}
	merged := GatePRResult{RowID: row, Kind: "MERGED", MergedAt: &at, MergeSha: "deadbeef"}
	issue := GateIssue{TaskID: task, GitRepoID: repo, OwnerRepo: "acme/api", Title: "t", Body: "b"}

	for name, tc := range map[string]struct {
		value any
		want  string
	}{
		"GateCompare": {compare,
			`{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","branch":"main","baseSha":"abc"}`},
		"GatePR": {pr,
			`{"rowId":"22222222-2222-2222-2222-222222222222","ownerRepo":"acme/api","number":7,"label":"api","prUrl":"https://example.test/pull/7"}`},
		"GateGitHubParams": {GateGitHubParams{RunID: run, NodeExecutionID: gate, Compares: []GateCompare{compare}, PRs: []GatePR{pr}, MergeMethod: "squash"},
			`{"runId":"33333333-3333-3333-3333-333333333333",
			  "nodeExecutionId":"44444444-4444-4444-4444-444444444444",
			  "compares":[{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","branch":"main","baseSha":"abc"}],
			  "prs":[{"rowId":"22222222-2222-2222-2222-222222222222","ownerRepo":"acme/api","number":7,"label":"api","prUrl":"https://example.test/pull/7"}],
			  "mergeMethod":"squash"}`},
		"GateCompareResult": {compareResult,
			`{"gitRepoId":"11111111-1111-1111-1111-111111111111","aheadBy":2,"baseGone":true}`},
		"GatePRResult": {merged,
			`{"rowId":"22222222-2222-2222-2222-222222222222","kind":"MERGED","mergedAt":"2026-10-11T09:30:00Z","mergeSha":"deadbeef"}`},
		"GatePRResult without a merge time or sha": {GatePRResult{RowID: row, Kind: "SKIPPED_CLOSED"},
			`{"rowId":"22222222-2222-2222-2222-222222222222","kind":"SKIPPED_CLOSED"}`},
		"GateGitHubResult": {GateGitHubResult{Compares: []GateCompareResult{compareResult}, Stale: true, Merges: []GatePRResult{merged}, Refused: "needs a person"},
			`{"compares":[{"gitRepoId":"11111111-1111-1111-1111-111111111111","aheadBy":2,"baseGone":true}],
			  "stale":true,
			  "merges":[{"rowId":"22222222-2222-2222-2222-222222222222","kind":"MERGED","mergedAt":"2026-10-11T09:30:00Z","mergeSha":"deadbeef"}],
			  "refused":"needs a person"}`},
		"GateIssue": {issue,
			`{"taskId":"55555555-5555-5555-5555-555555555555","gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","title":"t","body":"b"}`},
		"GateIssueResult": {GateIssueResult{TaskID: task, Number: 7, URL: "https://example.test/issues/7"},
			`{"taskId":"55555555-5555-5555-5555-555555555555","number":7,"url":"https://example.test/issues/7"}`},
		"GateIssueResult failure only": {GateIssueResult{TaskID: task, Failure: "the issue step kept failing"},
			`{"taskId":"55555555-5555-5555-5555-555555555555","failure":"the issue step kept failing"}`},
		"GateIssueParams": {GateIssueParams{RunID: run, NodeExecutionID: gate, Since: at, Issues: []GateIssue{issue}},
			`{"runId":"33333333-3333-3333-3333-333333333333",
			  "nodeExecutionId":"44444444-4444-4444-4444-444444444444",
			  "since":"2026-10-11T09:30:00Z",
			  "issues":[{"taskId":"55555555-5555-5555-5555-555555555555","gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","title":"t","body":"b"}]}`},
		"GateIssuesResult": {GateIssuesResult{Issues: []GateIssueResult{{TaskID: task, Number: 7, URL: "https://example.test/issues/7"}}},
			`{"issues":[{"taskId":"55555555-5555-5555-5555-555555555555","number":7,"url":"https://example.test/issues/7"}]}`},
	} {
		t.Run(name, func(t *testing.T) {
			got, err := json.Marshal(tc.value)
			require.NoError(t, err)
			assert.JSONEq(t, tc.want, string(got))
		})
	}
}

// The Worker's JSON is the other direction of the same contract: a result it emits must fill
// the orchestrator's copy, not blank it.
func TestWorkerMirroredResult_DecodesWhatTheWorkerEmits(t *testing.T) {
	const fromWorker = `{
		"compares":[{"gitRepoId":"11111111-1111-1111-1111-111111111111","aheadBy":1,"baseGone":false}],
		"stale":false,
		"merges":[{"rowId":"22222222-2222-2222-2222-222222222222","kind":"MERGED","mergedAt":"2026-10-11T09:30:00Z","mergeSha":"deadbeef"}],
		"refused":""
	}`
	var got GateGitHubResult
	require.NoError(t, json.Unmarshal([]byte(fromWorker), &got))
	at := time.Date(2026, 10, 11, 9, 30, 0, 0, time.UTC)
	assert.Equal(t, GateGitHubResult{
		Compares: []GateCompareResult{{GitRepoID: uuid.MustParse("11111111-1111-1111-1111-111111111111"), AheadBy: 1}},
		Merges: []GatePRResult{{
			RowID: uuid.MustParse("22222222-2222-2222-2222-222222222222"), Kind: "MERGED", MergedAt: &at, MergeSha: "deadbeef",
		}},
	}, got)
}

// FileGateIssues' result crosses the same wire: a result the Worker emits must decode whole.
func TestWorkerMirroredIssuesResult_DecodesWhatTheWorkerEmits(t *testing.T) {
	taskID := uuid.MustParse("55555555-5555-5555-5555-555555555555")
	const fromWorker = `{"issues":[
		{"taskId":"55555555-5555-5555-5555-555555555555","number":3,"url":"https://example.test/issues/3"}
	]}`
	var got GateIssuesResult
	require.NoError(t, json.Unmarshal([]byte(fromWorker), &got))
	assert.Equal(t, GateIssuesResult{
		Issues: []GateIssueResult{{TaskID: taskID, Number: 3, URL: "https://example.test/issues/3"}},
	}, got)
}

// Params that embed GateApprovalRef cross Temporal's data converter on the way to an activity
// and must come back whole.
func TestGateParams_RoundTripThroughTheDataConverter(t *testing.T) {
	ref := GateApprovalRef{RunID: uuid.New(), NodeExecutionID: uuid.New(), ApprovalID: uuid.NewString()}
	at := time.Date(2026, 10, 11, 9, 30, 0, 0, time.UTC)
	merges := []GatePRResult{{RowID: uuid.New(), Kind: "MERGED", MergedAt: &at, MergeSha: "deadbeef"}}

	dc := converter.GetDefaultDataConverter()
	roundTrip := func(t *testing.T, in, out any) {
		t.Helper()
		payload, err := dc.ToPayload(in)
		require.NoError(t, err)
		require.NoError(t, dc.FromPayload(payload, out))
	}

	t.Run("hold", func(t *testing.T) {
		in := GateHoldParams{GateApprovalRef: ref, Compares: []GateCompareResult{{GitRepoID: uuid.New(), AheadBy: 4}}}
		var out GateHoldParams
		roundTrip(t, in, &out)
		assert.Equal(t, in, out)
	})
	t.Run("complete", func(t *testing.T) {
		in := GateCompleteParams{
			GateApprovalRef: ref, Merges: merges,
			Issues: []GateIssueResult{{TaskID: uuid.New(), Number: 1, URL: "u"}, {TaskID: uuid.New(), Failure: "x"}},
		}
		var out GateCompleteParams
		roundTrip(t, in, &out)
		assert.Equal(t, in, out)
	})
	t.Run("refuse", func(t *testing.T) {
		in := GateRefuseParams{GateApprovalRef: ref, Reason: "checks failed", Merges: merges}
		var out GateRefuseParams
		roundTrip(t, in, &out)
		assert.Equal(t, in, out)
	})
	t.Run("materialize and materialized", func(t *testing.T) {
		in := GateMaterializeParams{GateApprovalRef: ref, Merges: merges}
		var out GateMaterializeParams
		roundTrip(t, in, &out)
		assert.Equal(t, in, out)

		materialized := GateMaterialized{
			Since:  at,
			Issues: []GateIssue{{TaskID: uuid.New(), GitRepoID: uuid.New(), OwnerRepo: "acme/api", Title: "t", Body: "b"}},
		}
		var materializedOut GateMaterialized
		roundTrip(t, materialized, &materializedOut)
		assert.Equal(t, materialized, materializedOut)
	})
	t.Run("issue params and result", func(t *testing.T) {
		in := GateIssueParams{
			RunID: ref.RunID, NodeExecutionID: ref.NodeExecutionID, Since: at,
			Issues: []GateIssue{{TaskID: uuid.New(), GitRepoID: uuid.New(), OwnerRepo: "acme/api", Title: "t", Body: "b"}},
		}
		var out GateIssueParams
		roundTrip(t, in, &out)
		assert.Equal(t, in, out)

		result := GateIssuesResult{Issues: []GateIssueResult{{TaskID: uuid.New(), Number: 1, URL: "u"}}}
		var resultOut GateIssuesResult
		roundTrip(t, result, &resultOut)
		assert.Equal(t, result, resultOut)
	})
	t.Run("plan and step result", func(t *testing.T) {
		in := GatePlan{
			Outcome:       GatePlanProceed,
			Compares:      []GateCompare{{GitRepoID: uuid.New(), OwnerRepo: "acme/api", Branch: "main", BaseSha: "abc"}},
			PRs:           []GatePR{{RowID: uuid.New(), OwnerRepo: "acme/api", Number: 1, Label: "api", PRURL: "u"}},
			MergeMethod:   "merge",
			WritesRoadmap: true,
		}
		var out GatePlan
		roundTrip(t, in, &out)
		assert.Equal(t, in, out)

		step := GateStepResult{Outcome: GateStepCompleted, Result: "r", AttachmentRefs: "{}", Reason: "x"}
		var stepOut GateStepResult
		roundTrip(t, step, &stepOut)
		assert.Equal(t, step, stepOut)
	})
}
