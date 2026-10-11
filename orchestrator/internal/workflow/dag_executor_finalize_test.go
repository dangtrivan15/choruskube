package workflow

import (
	"context"
	"errors"
	"reflect"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/mock"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/converter"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/workflow"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/activity"
)

const finalizeWorkerQueue = "fleet-acme"

// gateRun is a run whose graph is one human gate, final_approval, with no outgoing edges.
type gateRun struct {
	runID, node, exec uuid.UUID
}

func (g gateRun) approval(id string) activity.GateApprovalRef {
	return activity.GateApprovalRef{RunID: g.runID, NodeExecutionID: g.exec, ApprovalID: id}
}

// newGateRun stubs everything around the gate. Status writes, decisions and the gate-finalize
// steps are left to each test, since testify matches overlapping expectations in registration order.
func (s *DAGExecutorTestSuite) newGateRun(timeoutSecs int) gateRun {
	g := gateRun{runID: s.dagRunID(), node: uuid.New(), exec: uuid.New()}
	snapshot := `{
		"nodes": [
			{"templateNodeId": "` + g.node.String() + `", "label": "final_approval", "executorType": "human", "timeoutSeconds": ` +
		strconv.Itoa(timeoutSecs) + `, "isEntrypoint": true}
		],
		"edges": []
	}`
	s.env.OnActivity("UpdateWorkflowRunStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("GetGraphRuntime", mock.Anything, g.runID).Return(snapshot, nil)
	s.env.OnActivity("WriteExecutionLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("InitRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("AppendRunLog", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("SetTraversedEdges", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("DeleteStaleBranches", mock.Anything, mock.Anything).Return(nil).Maybe()
	s.env.OnActivity("CreateNodeExecution", mock.Anything, mock.MatchedBy(func(p activity.CreateNodeExecParams) bool {
		return p.TemplateNodeID == g.node
	})).Return(g.exec, nil).Once()
	return g
}

func (s *DAGExecutorTestSuite) runGate(g gateRun) {
	s.env.ExecuteWorkflow(DAGExecutorWorkflow, DAGExecutorParams{
		RunID: g.runID, GraphVersion: 1, WorkerTaskQueue: finalizeWorkerQueue,
	})
}

func (s *DAGExecutorTestSuite) signalGate(g gateRun, after time.Duration, decision, approvalID string) {
	s.env.RegisterDelayedCallback(func() {
		s.env.SignalWorkflow(SignalHumanDecisionPrefix+g.exec.String(), HumanDecisionSignal{
			NodeExecutionID: g.exec.String(), Decision: decision, Feedback: "reviewer feedback", ApprovalID: approvalID,
		})
	}, after)
}

// expectGateOpened pins the awaiting_human write and the finalize mode it carries (nil: none).
func (s *DAGExecutorTestSuite) expectGateOpened(g gateRun, mode *string) {
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == g.exec && p.Status == "awaiting_human" && reflect.DeepEqual(p.FinalizeMode, mode)
	})).Return(nil).Once()
}

func (s *DAGExecutorTestSuite) expectGateCompleted(g gateRun, result, refs string) {
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == g.exec && p.Status == "completed" &&
			p.Result != nil && *p.Result == result && p.ArtifactRefs != nil && *p.ArtifactRefs == refs
	})).Return(nil).Once()
}

func (s *DAGExecutorTestSuite) expectGateFailed(g gateRun, contains string) {
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == g.exec && p.Status == "failed" &&
			p.ErrorMessage != nil && strings.Contains(*p.ErrorMessage, contains)
	})).Return(nil).Once()
}

func (s *DAGExecutorTestSuite) anyOtherStatusWrite() {
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.Anything).Return(nil).Maybe()
}

func (s *DAGExecutorTestSuite) decisionReads(g gateRun, decision string) {
	s.env.OnActivity("GetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.GetNodeDecisionParams) bool {
		return p.NodeExecutionID == g.exec
	})).Return(decision, nil).Once()
}

func (s *DAGExecutorTestSuite) expectPlan(g gateRun, approvalID string, plan activity.GatePlan) {
	s.env.OnActivity("PlanGateFinalize", mock.Anything, g.approval(approvalID)).Return(plan, nil).Once()
}

func (s *DAGExecutorTestSuite) expectGitHubSteps(g gateRun, plan activity.GatePlan, result activity.GateGitHubResult) {
	s.env.OnActivity("RunGateGitHubSteps", mock.Anything, mock.MatchedBy(func(p activity.GateGitHubParams) bool {
		return reflect.DeepEqual(p, activity.GateGitHubParams{
			RunID: g.runID, Compares: plan.Compares, PRs: plan.PRs, MergeMethod: plan.MergeMethod,
		})
	})).Return(result, nil).Once()
}

func (s *DAGExecutorTestSuite) expectComplete(g gateRun, approvalID string, merges []activity.GatePRResult, result activity.GateStepResult) {
	s.env.OnActivity("CompleteGateFinalize", mock.Anything, mock.MatchedBy(func(p activity.GateCompleteParams) bool {
		return p.GateApprovalRef == g.approval(approvalID) && reflect.DeepEqual(p.Merges, merges)
	})).Return(result, nil).Once()
}

func (s *DAGExecutorTestSuite) expectRefuse(g gateRun, approvalID string, reason func(string) bool, merges []activity.GatePRResult) {
	s.env.OnActivity("RefuseGateApproval", mock.Anything, mock.MatchedBy(func(p activity.GateRefuseParams) bool {
		return p.GateApprovalRef == g.approval(approvalID) && reason(p.Reason) && reflect.DeepEqual(p.Merges, merges)
	})).Return(nil).Once()
}

func (s *DAGExecutorTestSuite) assertCompleted() {
	s.True(s.env.IsWorkflowCompleted())
	s.NoError(s.env.GetWorkflowError())
}

// startedActivities records every started activity attempt, in order, with its info and the
// workflow clock's time.
type startedActivities struct {
	mu    sync.Mutex
	order []string
	infos map[string][]temporalactivity.Info
	at    map[string][]time.Time
}

func (r *startedActivities) of(name string) ([]temporalactivity.Info, []time.Time) {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]temporalactivity.Info(nil), r.infos[name]...), append([]time.Time(nil), r.at[name]...)
}

func (r *startedActivities) sequence() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]string(nil), r.order...)
}

// recordStarts is the suite's one activity-started listener; hook, if set, runs after recording.
func (s *DAGExecutorTestSuite) recordStarts(hook func(info *temporalactivity.Info)) *startedActivities {
	r := &startedActivities{infos: map[string][]temporalactivity.Info{}, at: map[string][]time.Time{}}
	s.env.SetOnActivityStartedListener(func(info *temporalactivity.Info, _ context.Context, _ converter.EncodedValues) {
		r.mu.Lock()
		r.order = append(r.order, info.ActivityType.Name)
		r.infos[info.ActivityType.Name] = append(r.infos[info.ActivityType.Name], *info)
		r.at[info.ActivityType.Name] = append(r.at[info.ActivityType.Name], s.env.Now())
		r.mu.Unlock()
		if hook != nil {
			hook(info)
		}
	})
	return r
}

// gaps returns the time between consecutive attempts.
func gaps(at []time.Time) []time.Duration {
	var out []time.Duration
	for i := 1; i < len(at); i++ {
		out = append(out, at[i].Sub(at[i-1]))
	}
	return out
}

// assertBackoff checks each retry waited the expected backoff, allowing the wall-clock drift the
// test environment adds while an activity runs.
func (s *DAGExecutorTestSuite) assertBackoff(at []time.Time, want ...time.Duration) {
	got := gaps(at)
	s.Require().Len(got, len(want), "retry gaps %v", got)
	for i := range want {
		s.GreaterOrEqual(got[i], want[i], "retry gap %d", i)
		s.Less(got[i], want[i]+time.Second, "retry gap %d", i)
	}
}

var asyncMode = func() *string { m := "async"; return &m }()

func onePRPlan() (activity.GatePlan, activity.GatePRResult) {
	repo, pr := uuid.New(), uuid.New()
	plan := activity.GatePlan{
		Outcome:     activity.GatePlanProceed,
		Compares:    []activity.GateCompare{{GitRepoID: repo, OwnerRepo: "acme/app", Branch: "main", BaseSha: "abc123"}},
		PRs:         []activity.GatePR{{RowID: pr, OwnerRepo: "acme/app", Number: 7, Label: "app", PRURL: "https://github.com/acme/app/pull/7"}},
		MergeMethod: "squash",
	}
	return plan, activity.GatePRResult{RowID: pr, Kind: "MERGED", MergeSha: "def456"}
}

// TestHumanGateFinalizeCompletes: an approval carrying an approval id is finalized in three
// steps, and the API's result completes the gate; the API persisted the decision, not the workflow.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizeCompletes() {
	g := s.newGateRun(3600)
	plan, merged := onePRPlan()
	starts := s.recordStarts(nil)

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "done", `{"shot.png":"runs/x/shot.png"}`)
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", plan)
	s.expectGitHubSteps(g, plan, activity.GateGitHubResult{
		Compares: []activity.GateCompareResult{{GitRepoID: plan.Compares[0].GitRepoID}},
		Merges:   []activity.GatePRResult{merged},
	})
	s.expectComplete(g, "s1", []activity.GatePRResult{merged}, activity.GateStepResult{
		Outcome: activity.GateStepCompleted, Result: "done", AttachmentRefs: `{"shot.png":"runs/x/shot.png"}`,
	})
	s.decisionReads(g, "no_decision")
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "SetNodeDecision", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "RefuseGateApproval", mock.Anything, mock.Anything)
	s.Equal([]activity.GateRef{{RunID: g.runID, NodeExecutionID: g.exec}}, s.claimedRefs())

	// The option sets: API steps on this workflow's queue, the GitHub step on the run's Worker.
	for name, want := range map[string]time.Duration{
		"ClaimHeldApproval": time.Minute, "PlanGateFinalize": time.Minute, "CompleteGateFinalize": 5 * time.Minute,
	} {
		infos, _ := starts.of(name)
		s.Require().Len(infos, 1, name)
		s.Equal(want, infos[0].StartToCloseTimeout, name)
		s.NotEqual(finalizeWorkerQueue, infos[0].TaskQueue, name)
	}
	gh, _ := starts.of("RunGateGitHubSteps")
	s.Require().Len(gh, 1)
	s.Equal(finalizeWorkerQueue, gh[0].TaskQueue)
	s.Equal(10*time.Minute, gh[0].StartToCloseTimeout)
	s.Equal(time.Minute, gh[0].HeartbeatTimeout)
}

// TestHumanGateFinalizeHolds: a main that moved holds the approval; the gate completes with the
// API's hold note and guidance, and the route decision the API persisted fires.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizeHolds() {
	g := s.newGateRun(3600)
	plan, _ := onePRPlan()
	compares := []activity.GateCompareResult{{GitRepoID: plan.Compares[0].GitRepoID, AheadBy: 3}}
	guidance := `{"human_guidance.md":"runs/x/human_guidance.md"}`
	starts := s.recordStarts(nil)

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "held for a re-check", guidance)
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", plan)
	s.expectGitHubSteps(g, plan, activity.GateGitHubResult{Compares: compares, Stale: true})
	s.env.OnActivity("HoldGateApproval", mock.Anything, mock.MatchedBy(func(p activity.GateHoldParams) bool {
		return p.GateApprovalRef == g.approval("s1") && reflect.DeepEqual(p.Compares, compares)
	})).Return(activity.GateStepResult{
		Outcome: activity.GateStepHeld, Result: "held for a re-check", AttachmentRefs: guidance,
	}, nil).Once()
	s.decisionReads(g, "rereview")
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "CompleteGateFinalize", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "SetNodeDecision", mock.Anything, mock.Anything)
	holds, _ := starts.of("HoldGateApproval")
	s.Require().Len(holds, 1)
	s.Equal(time.Minute, holds[0].StartToCloseTimeout)
}

// TestHumanGateFinalizePlanHoldSkipsTheWorker: a repo already known stale holds the approval
// without asking GitHub anything.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizePlanHoldSkipsTheWorker() {
	g := s.newGateRun(3600)

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "held: no recorded base", "{}")
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", activity.GatePlan{Outcome: activity.GatePlanHold, Reason: "no recorded base"})
	s.env.OnActivity("HoldGateApproval", mock.Anything, mock.MatchedBy(func(p activity.GateHoldParams) bool {
		return p.GateApprovalRef == g.approval("s1") && len(p.Compares) == 0
	})).Return(activity.GateStepResult{
		Outcome: activity.GateStepHeld, Result: "held: no recorded base", AttachmentRefs: "{}",
	}, nil).Once()
	s.decisionReads(g, "rereview")
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "RunGateGitHubSteps", mock.Anything, mock.Anything)
}

// TestHumanGateFinalizeRefusedThenReapproved: a refused approval sends the gate back to waiting
// with a fresh full timeout, and the next approval finalizes.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizeRefusedThenReapproved() {
	g := s.newGateRun(3600)
	plan, merged := onePRPlan()
	// The second approval lands 100 minutes in: past the gate's first hour, inside an hour from
	// the refusal at 50 minutes. Only a timeout restarted in full at the refusal admits it.
	s.recordStarts(func(info *temporalactivity.Info) {
		if info.ActivityType.Name == "RefuseGateApproval" {
			s.signalGate(g, 50*time.Minute, "approved", "s2")
		}
	})

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "done", "{}")
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", activity.GatePlan{Outcome: activity.GatePlanRefused, Reason: "the roadmap has an empty title"})
	s.expectRefuse(g, "s1", func(r string) bool { return r == "the roadmap has an empty title" }, nil)
	s.expectPlan(g, "s2", plan)
	s.expectGitHubSteps(g, plan, activity.GateGitHubResult{Merges: []activity.GatePRResult{merged}})
	s.expectComplete(g, "s2", []activity.GatePRResult{merged}, activity.GateStepResult{
		Outcome: activity.GateStepCompleted, Result: "done", AttachmentRefs: "{}",
	})
	s.decisionReads(g, "approved")
	s.signalGate(g, 50*time.Minute, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNumberOfCalls(s.T(), "PlanGateFinalize", 2)
	s.env.AssertActivityNotCalled(s.T(), "SetNodeDecision", mock.Anything, mock.Anything)
}

// TestHumanGateHeldApprovalClaimedOnOpen: a held approval is finalized the moment its gate
// reopens, with no signal.
func (s *DAGExecutorTestSuite) TestHumanGateHeldApprovalClaimedOnOpen() {
	g := s.newGateRun(3600)
	plan, merged := onePRPlan()
	s.heldApproval = "s9"

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "done", "{}")
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s9", plan)
	s.expectGitHubSteps(g, plan, activity.GateGitHubResult{Merges: []activity.GatePRResult{merged}})
	s.expectComplete(g, "s9", []activity.GatePRResult{merged}, activity.GateStepResult{
		Outcome: activity.GateStepCompleted, Result: "done", AttachmentRefs: "{}",
	})
	s.decisionReads(g, "approved")

	s.runGate(g)

	s.assertCompleted()
	s.Equal([]activity.GateRef{{RunID: g.runID, NodeExecutionID: g.exec}}, s.claimedRefs())
}

// TestHumanGateFinalizeWorkerFailureRefuses: a GitHub step that exhausts its retries refuses the
// approval, and the gate waits for the next decision.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizeWorkerFailureRefuses() {
	g := s.newGateRun(3600)
	plan, _ := onePRPlan()
	starts := s.recordStarts(func(info *temporalactivity.Info) {
		if info.ActivityType.Name == "RefuseGateApproval" {
			s.signalGate(g, time.Minute, "rereview", "")
		}
	})

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "reviewer feedback", "{}")
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", plan)
	s.env.OnActivity("RunGateGitHubSteps", mock.Anything, mock.Anything).
		Return(activity.GateGitHubResult{}, errors.New("github is unreachable"))
	// The Worker's message, without the activity envelope that names the Worker's host.
	s.expectRefuse(g, "s1", func(r string) bool {
		return r == "Finalizing could not reach GitHub: github is unreachable"
	}, nil)
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == g.exec && p.Decision == "rereview"
	})).Return(nil).Once()
	s.decisionReads(g, "rereview")
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "CompleteGateFinalize", mock.Anything, mock.Anything)
	_, at := starts.of("RunGateGitHubSteps")
	s.assertBackoff(at, 5*time.Second, 10*time.Second, 20*time.Second, 40*time.Second)
}

// TestHumanGateFinalizeWorkerRefusalRecordsItsMerges: a Worker refusal after a partial merge
// hands the API the merges already made, so they are recorded before the approval is voided.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizeWorkerRefusalRecordsItsMerges() {
	g := s.newGateRun(3600)
	plan, merged := onePRPlan()
	s.recordStarts(func(info *temporalactivity.Info) {
		if info.ActivityType.Name == "RefuseGateApproval" {
			s.signalGate(g, time.Minute, "rereview", "")
		}
	})

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "reviewer feedback", "{}")
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", plan)
	s.expectGitHubSteps(g, plan, activity.GateGitHubResult{
		Refused: "acme/app#8 has conflicts", Merges: []activity.GatePRResult{merged},
	})
	s.expectRefuse(g, "s1", func(r string) bool { return r == "acme/app#8 has conflicts" }, []activity.GatePRResult{merged})
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == g.exec && p.Decision == "rereview"
	})).Return(nil).Once()
	s.decisionReads(g, "rereview")
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "CompleteGateFinalize", mock.Anything, mock.Anything)
}

// TestHumanGateFinalizeHoldRefusedAtCapWaitsAgain: a hold past the re-check cap comes back
// refused; the API already sent the gate back, so the workflow waits without refusing again.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizeHoldRefusedAtCapWaitsAgain() {
	g := s.newGateRun(3600)
	plan, _ := onePRPlan()
	compares := []activity.GateCompareResult{{GitRepoID: plan.Compares[0].GitRepoID, AheadBy: 1}}
	s.recordStarts(func(info *temporalactivity.Info) {
		if info.ActivityType.Name == "HoldGateApproval" {
			s.signalGate(g, time.Minute, "rereview", "")
		}
	})

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "reviewer feedback", "{}")
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", plan)
	s.expectGitHubSteps(g, plan, activity.GateGitHubResult{Compares: compares, Stale: true})
	s.env.OnActivity("HoldGateApproval", mock.Anything, mock.Anything).Return(activity.GateStepResult{
		Outcome: activity.GateStepRefused, Reason: "the default branch moved again after 2 re-check rounds",
	}, nil).Once()
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == g.exec && p.Decision == "rereview"
	})).Return(nil).Once()
	s.decisionReads(g, "rereview")
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "RefuseGateApproval", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "CompleteGateFinalize", mock.Anything, mock.Anything)
}

// TestHumanGateFinalizeRejectedCompleteFailsTheNode: an API step the API rejects outright fails
// the node once, instead of retrying or sending the gate back to waiting.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizeRejectedCompleteFailsTheNode() {
	g := s.newGateRun(3600)
	plan, merged := onePRPlan()

	s.expectGateOpened(g, asyncMode)
	s.expectGateFailed(g, "finalize approval")
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", plan)
	s.expectGitHubSteps(g, plan, activity.GateGitHubResult{Merges: []activity.GatePRResult{merged}})
	s.env.OnActivity("CompleteGateFinalize", mock.Anything, mock.Anything).Return(activity.GateStepResult{},
		temporal.NewNonRetryableApplicationError("complete: 409 approval is not finalizing", "GateFinalizeRejected", nil))
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNumberOfCalls(s.T(), "CompleteGateFinalize", 1)
	s.env.AssertActivityNotCalled(s.T(), "RefuseGateApproval", mock.Anything, mock.Anything)
}

// TestHumanGateFinalizePlanErrorFailsTheNodeAfterRetries: an API step that keeps failing is
// retried with backoff, then fails the node.
func (s *DAGExecutorTestSuite) TestHumanGateFinalizePlanErrorFailsTheNodeAfterRetries() {
	g := s.newGateRun(3600)
	starts := s.recordStarts(nil)

	s.expectGateOpened(g, asyncMode)
	s.expectGateFailed(g, "finalize approval")
	s.anyOtherStatusWrite()
	s.env.OnActivity("PlanGateFinalize", mock.Anything, mock.Anything).
		Return(activity.GatePlan{}, errors.New("plan: 503 Service Unavailable"))
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	_, at := starts.of("PlanGateFinalize")
	s.assertBackoff(at, 2*time.Second, 4*time.Second, 8*time.Second, 16*time.Second)
	s.env.AssertActivityNotCalled(s.T(), "RefuseGateApproval", mock.Anything, mock.Anything)
}

// TestHumanGateRunCancelledWhileFinalizing: cancelling the run mid-finalize ends the workflow
// without refusing or completing the approval; the run's end voids it on the API.
func (s *DAGExecutorTestSuite) TestHumanGateRunCancelledWhileFinalizing() {
	g := s.newGateRun(3600)
	plan, merged := onePRPlan()
	s.recordStarts(func(info *temporalactivity.Info) {
		if info.ActivityType.Name == "RunGateGitHubSteps" {
			s.env.RegisterDelayedCallback(func() { s.env.SignalWorkflow(SignalCancel, nil) }, time.Minute)
		}
	})

	s.expectGateOpened(g, asyncMode)
	s.env.OnActivity("UpdateNodeExecutionStatus", mock.Anything, mock.MatchedBy(func(p activity.UpdateNodeExecStatusParams) bool {
		return p.NodeExecutionID == g.exec && p.Status == "skipped"
	})).Return(nil).Once()
	s.anyOtherStatusWrite()
	s.expectPlan(g, "s1", plan)
	s.env.OnActivity("RunGateGitHubSteps", mock.Anything, mock.Anything).
		Return(activity.GateGitHubResult{Merges: []activity.GatePRResult{merged}}, nil).After(time.Hour)
	s.signalGate(g, 0, "approved", "s1")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "RefuseGateApproval", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "CompleteGateFinalize", mock.Anything, mock.Anything)
}

// TestHumanGateOldVersionKeepsFinalSignal: a gate opened by a history recorded before the
// version marker keeps the synchronous contract: no finalize mode, no claim, the same commands.
func (s *DAGExecutorTestSuite) TestHumanGateOldVersionKeepsFinalSignal() {
	g := s.newGateRun(3600)
	s.env.OnGetVersion("async-gate-finalize", workflow.DefaultVersion, 1).Return(workflow.DefaultVersion)
	starts := s.recordStarts(nil)

	s.expectGateOpened(g, nil)
	s.expectGateCompleted(g, "reviewer feedback", "{}")
	s.anyOtherStatusWrite()
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == g.exec && p.Decision == "approved"
	})).Return(nil).Once()
	s.decisionReads(g, "approved")
	s.signalGate(g, 0, "approved", "")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "ClaimHeldApproval", mock.Anything, mock.Anything)
	// The activity order a single approved gate produced before the version marker existed.
	s.Equal([]string{
		"UpdateWorkflowRunStatus", "InitRunLog", "GetGraphRuntime", "CreateNodeExecution",
		"UpdateNodeExecutionStatus", "UpdateWorkflowRunStatus", "WriteExecutionLog",
		"SetNodeDecision", "UpdateNodeExecutionStatus", "UpdateWorkflowRunStatus", "WriteExecutionLog",
		"GetNodeDecision", "SetTraversedEdges", "AppendRunLog", "UpdateWorkflowRunStatus", "DeleteStaleBranches",
	}, starts.sequence())
}

// TestHumanGateSignalWithoutApprovalIdOnAsyncGate_isFinal: a decision with no approval id is
// final on an asynchronous gate too, as the API sends for everything but an approval to finalize.
func (s *DAGExecutorTestSuite) TestHumanGateSignalWithoutApprovalIdOnAsyncGate_isFinal() {
	g := s.newGateRun(3600)

	s.expectGateOpened(g, asyncMode)
	s.expectGateCompleted(g, "reviewer feedback", "{}")
	s.anyOtherStatusWrite()
	s.env.OnActivity("SetNodeDecision", mock.Anything, mock.MatchedBy(func(p activity.SetNodeDecisionParams) bool {
		return p.NodeExecutionID == g.exec && p.Decision == "rereview"
	})).Return(nil).Once()
	s.decisionReads(g, "rereview")
	s.signalGate(g, 0, "rereview", "")

	s.runGate(g)

	s.assertCompleted()
	s.env.AssertActivityNotCalled(s.T(), "PlanGateFinalize", mock.Anything, mock.Anything)
}

// gateWaitAlone runs one asynchronous gate's wait outside DAGExecutorWorkflow, whose loop has no
// branch for a cancelled workflow, and reports whether the wait sent a completion.
func gateWaitAlone(ctx workflow.Context, runID, execID, nodeID uuid.UUID) (bool, error) {
	completionCh := workflow.NewBufferedChannel(ctx, 1)
	humanCh := workflow.GetSignalChannel(ctx, SignalHumanDecisionPrefix+execID.String())
	dbCtx := workflow.WithActivityOptions(ctx, workflow.ActivityOptions{StartToCloseTimeout: time.Minute})
	var acts *activity.Activities
	returned := false
	workflow.Go(ctx, func(gCtx workflow.Context) {
		waitForHumanDecision(gCtx, dbCtx, humanCh, completionCh, acts, runID, execID, nodeID, 3600, true, finalizeWorkerQueue)
		returned = true
	})
	waitCtx, _ := workflow.NewDisconnectedContext(ctx)
	if err := workflow.Await(waitCtx, func() bool { return returned }); err != nil {
		return false, err
	}
	var c nodeCompletion
	return completionCh.ReceiveAsync(&c), nil
}

// TestHumanGateCancelledWorkflowAbandonsFinalize: a workflow cancelled while the Worker merges
// ends the gate's wait without refusing or completing the approval, and without a completion.
func (s *DAGExecutorTestSuite) TestHumanGateCancelledWorkflowAbandonsFinalize() {
	g := gateRun{runID: s.dagRunID(), node: uuid.New(), exec: uuid.New()}
	plan, merged := onePRPlan()
	s.recordStarts(func(info *temporalactivity.Info) {
		if info.ActivityType.Name == "RunGateGitHubSteps" {
			s.env.RegisterDelayedCallback(s.env.CancelWorkflow, time.Minute)
		}
	})
	s.expectPlan(g, "s1", plan)
	s.env.OnActivity("RunGateGitHubSteps", mock.Anything, mock.Anything).
		Return(activity.GateGitHubResult{Merges: []activity.GatePRResult{merged}}, nil).After(time.Hour)
	s.signalGate(g, 0, "approved", "s1")

	s.env.ExecuteWorkflow(gateWaitAlone, g.runID, g.exec, g.node)

	s.True(s.env.IsWorkflowCompleted())
	var sent bool
	s.NoError(s.env.GetWorkflowResult(&sent))
	s.False(sent, "a cancelled finalize sent a completion")
	s.env.AssertActivityNotCalled(s.T(), "RefuseGateApproval", mock.Anything, mock.Anything)
	s.env.AssertActivityNotCalled(s.T(), "CompleteGateFinalize", mock.Anything, mock.Anything)
}

// TestHumanGateCancelledWorkflowWhileWaitingSendsNothing: a workflow cancelled while the gate
// waits ends the wait without persisting a decision nobody made.
func (s *DAGExecutorTestSuite) TestHumanGateCancelledWorkflowWhileWaitingSendsNothing() {
	g := gateRun{runID: s.dagRunID(), node: uuid.New(), exec: uuid.New()}
	s.env.RegisterDelayedCallback(s.env.CancelWorkflow, time.Minute)

	s.env.ExecuteWorkflow(gateWaitAlone, g.runID, g.exec, g.node)

	s.True(s.env.IsWorkflowCompleted())
	var sent bool
	s.NoError(s.env.GetWorkflowResult(&sent))
	s.False(sent, "a cancelled wait sent a completion")
	s.env.AssertActivityNotCalled(s.T(), "SetNodeDecision", mock.Anything, mock.Anything)
}
