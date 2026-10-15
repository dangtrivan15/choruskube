package activity

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/orchestrator/internal/apiclient"
)

// The outcomes the API answers with, in GatePlan.Outcome and GateStepResult.Outcome.
const (
	GatePlanProceed   = "proceed"
	GatePlanHold      = "hold"
	GatePlanRefused   = "refused"
	GateStepCompleted = "completed"
	GateStepHeld      = "held"
	GateStepRefused   = "refused"
)

// The Worker's gate types, copied field for field. Temporal's JSON converter makes these tags
// the wire contract, so a rename on one side blanks fields silently instead of failing.

// GateCompare asks whether Branch has moved past BaseSha, the commit the run's last node ran on.
type GateCompare struct {
	GitRepoID uuid.UUID `json:"gitRepoId"`
	OwnerRepo string    `json:"ownerRepo"`
	Branch    string    `json:"branch"`
	BaseSha   string    `json:"baseSha"`
}

// GatePR is one registered pull request, in the order it must merge.
type GatePR struct {
	RowID     uuid.UUID `json:"rowId"`
	OwnerRepo string    `json:"ownerRepo"`
	Number    int       `json:"number"`
	Label     string    `json:"label"`
	PRURL     string    `json:"prUrl"`
}

// GateGitHubParams.NodeExecutionID is the gate's execution, read before every merge.
type GateGitHubParams struct {
	RunID           uuid.UUID     `json:"runId"`
	NodeExecutionID uuid.UUID     `json:"nodeExecutionId"`
	Compares        []GateCompare `json:"compares"`
	PRs             []GatePR      `json:"prs"`
	MergeMethod     string        `json:"mergeMethod"`
}

type GateCompareResult struct {
	GitRepoID uuid.UUID `json:"gitRepoId"`
	AheadBy   int       `json:"aheadBy"`
	BaseGone  bool      `json:"baseGone"`
}

// GatePRResult.Kind is one of MERGED, ALREADY_MERGED and SKIPPED_CLOSED.
type GatePRResult struct {
	RowID    uuid.UUID  `json:"rowId"`
	Kind     string     `json:"kind"`
	MergedAt *time.Time `json:"mergedAt,omitempty"`
	MergeSha string     `json:"mergeSha,omitempty"`
}

// GateGitHubResult carries one outcome. Stale: nothing was merged. Refused: a person must act,
// and Merges holds what merged, was found merged, or was found closed. Otherwise Merges covers
// every pull request.
type GateGitHubResult struct {
	Compares []GateCompareResult `json:"compares"`
	Stale    bool                `json:"stale"`
	Merges   []GatePRResult      `json:"merges"`
	Refused  string              `json:"refused"`
}

// RunGateGitHubSteps is dispatched to the Worker's task queue and never runs here. The method
// exists so the workflow can derive the activity type name from it.
func (a *Activities) RunGateGitHubSteps(_ context.Context, _ GateGitHubParams) (GateGitHubResult, error) {
	return GateGitHubResult{}, fmt.Errorf("activity dispatched to Worker task queue, not the orchestrator")
}

// --- Orchestrator-queue steps of an asynchronous gate approval ---
//
// Each returns the client's error untouched: the SDK reads an error's retry class from its
// concrete type, so wrapping a rejection here would make the workflow retry it.

// GateRef names an asynchronous gate's node execution.
type GateRef struct {
	RunID           uuid.UUID `json:"runId"`
	NodeExecutionID uuid.UUID `json:"nodeExecutionId"`
}

// GateApprovalRef names one approval of a gate. ApprovalID is a UUID in string form.
type GateApprovalRef struct {
	RunID           uuid.UUID `json:"runId"`
	NodeExecutionID uuid.UUID `json:"nodeExecutionId"`
	ApprovalID      string    `json:"approvalId"`
}

// GatePlan.Outcome is GatePlanProceed, GatePlanHold or GatePlanRefused. Compares, PRs and
// MergeMethod are the work to hand the Worker, and only a proceed carries any. WritesRoadmap is
// false on an API that predates the split write step, which keeps the workflow on one-step complete.
type GatePlan struct {
	Outcome       string        `json:"outcome"`
	Reason        string        `json:"reason"`
	Compares      []GateCompare `json:"compares"`
	PRs           []GatePR      `json:"prs"`
	MergeMethod   string        `json:"mergeMethod"`
	WritesRoadmap bool          `json:"writesRoadmap"`
}

// GateIssue is one GitHub issue the roadmap write step asks the run's Worker to file, copied
// field for field from the Worker's mirror of the same name.
type GateIssue struct {
	TaskID    uuid.UUID `json:"taskId"`
	GitRepoID uuid.UUID `json:"gitRepoId"`
	OwnerRepo string    `json:"ownerRepo"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
}

// GateIssueResult.Failure is set, and Number/URL left zero, when that issue was not filed.
type GateIssueResult struct {
	TaskID  uuid.UUID `json:"taskId"`
	Number  int       `json:"number,omitempty"`
	URL     string    `json:"url,omitempty"`
	Failure string    `json:"failure,omitempty"`
}

// GateIssueParams is the Worker's issue step input, copied field for field from its mirror.
// Since is the roadmap write's claim time: the Worker lists issues updated from that moment to
// find one a retry already filed.
type GateIssueParams struct {
	RunID           uuid.UUID   `json:"runId"`
	NodeExecutionID uuid.UUID   `json:"nodeExecutionId"`
	Since           time.Time   `json:"since"`
	Issues          []GateIssue `json:"issues"`
}

// GateIssuesResult is the Worker's issue step output, copied field for field from its mirror.
type GateIssuesResult struct {
	Issues []GateIssueResult `json:"issues"`
}

// GateStepResult.Outcome is GateStepCompleted, GateStepHeld or GateStepRefused; Reason explains
// a refusal.
type GateStepResult struct {
	Outcome        string `json:"outcome"`
	Result         string `json:"result"`
	AttachmentRefs string `json:"attachmentRefs"`
	Reason         string `json:"reason"`
}

type GateHoldParams struct {
	GateApprovalRef
	Compares []GateCompareResult `json:"compares"`
}

// GateCompleteParams.Issues is the issue step's per-task result, empty when nothing was requested.
type GateCompleteParams struct {
	GateApprovalRef
	Merges []GatePRResult    `json:"merges"`
	Issues []GateIssueResult `json:"issues"`
}

// GateMaterializeParams.Merges are the merges the Worker already made, recorded by the write
// step before it writes the roadmap.
type GateMaterializeParams struct {
	GateApprovalRef
	Merges []GatePRResult `json:"merges"`
}

// GateMaterialized.Since is the write step's claim time, passed to the issue step so a retry's
// listing starts from the same moment. Issues is empty when the gate writes no roadmap.
type GateMaterialized struct {
	Since  time.Time   `json:"since"`
	Issues []GateIssue `json:"issues"`
}

// GateRefuseParams.Merges are the merges the Worker made before it refused; the API records them
// before it voids the approval.
type GateRefuseParams struct {
	GateApprovalRef
	Reason string         `json:"reason"`
	Merges []GatePRResult `json:"merges"`
}

// parseApprovalID returns the approval id as a UUID, so the string that reaches a URL is always
// the canonical form and never a path the workflow made up.
func parseApprovalID(s string) (uuid.UUID, error) {
	id, err := uuid.Parse(s)
	if err != nil {
		return uuid.Nil, temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("approval id %q is not a UUID", s), "GateApprovalID", err)
	}
	return id, nil
}

// ClaimHeldApproval returns the id of the approval it claimed for finalizing, or "" when the gate
// has none held.
func (a *Activities) ClaimHeldApproval(ctx context.Context, p GateRef) (string, error) {
	if err := guardRun(ctx, p.RunID); err != nil {
		return "", err
	}
	return a.client.ClaimHeldApproval(ctx, p.RunID, p.NodeExecutionID)
}

func (a *Activities) PlanGateFinalize(ctx context.Context, p GateApprovalRef) (GatePlan, error) {
	if err := guardRun(ctx, p.RunID); err != nil {
		return GatePlan{}, err
	}
	approvalID, err := parseApprovalID(p.ApprovalID)
	if err != nil {
		return GatePlan{}, err
	}
	resp, err := a.client.PlanGateFinalize(ctx, p.RunID, p.NodeExecutionID, approvalID)
	if err != nil {
		return GatePlan{}, err
	}
	plan := GatePlan{
		Outcome:       resp.Outcome,
		Reason:        resp.Reason,
		Compares:      make([]GateCompare, len(resp.Compares)),
		PRs:           make([]GatePR, len(resp.PRs)),
		MergeMethod:   resp.MergeMethod,
		WritesRoadmap: resp.WritesRoadmap,
	}
	for i, c := range resp.Compares {
		plan.Compares[i] = GateCompare(c)
	}
	for i, pr := range resp.PRs {
		plan.PRs[i] = GatePR(pr)
	}
	return plan, nil
}

func (a *Activities) HoldGateApproval(ctx context.Context, p GateHoldParams) (GateStepResult, error) {
	if err := guardRun(ctx, p.RunID); err != nil {
		return GateStepResult{}, err
	}
	approvalID, err := parseApprovalID(p.ApprovalID)
	if err != nil {
		return GateStepResult{}, err
	}
	compares := make([]apiclient.GateCompareResultItem, len(p.Compares))
	for i, c := range p.Compares {
		compares[i] = apiclient.GateCompareResultItem(c)
	}
	resp, err := a.client.HoldGateApproval(ctx, p.RunID, p.NodeExecutionID, approvalID, compares)
	if err != nil {
		return GateStepResult{}, err
	}
	return GateStepResult(resp), nil
}

func (a *Activities) CompleteGateFinalize(ctx context.Context, p GateCompleteParams) (GateStepResult, error) {
	if err := guardRun(ctx, p.RunID); err != nil {
		return GateStepResult{}, err
	}
	approvalID, err := parseApprovalID(p.ApprovalID)
	if err != nil {
		return GateStepResult{}, err
	}
	resp, err := a.client.CompleteGateFinalize(
		ctx, p.RunID, p.NodeExecutionID, approvalID, prResultItems(p.Merges), issueResultItems(p.Issues))
	if err != nil {
		return GateStepResult{}, err
	}
	return GateStepResult(resp), nil
}

// MaterializeGateApproval runs the roadmap write step: it records the Worker's merges, writes the
// roadmap once, and returns the issues it wants the Worker to file.
func (a *Activities) MaterializeGateApproval(ctx context.Context, p GateMaterializeParams) (GateMaterialized, error) {
	if err := guardRun(ctx, p.RunID); err != nil {
		return GateMaterialized{}, err
	}
	approvalID, err := parseApprovalID(p.ApprovalID)
	if err != nil {
		return GateMaterialized{}, err
	}
	resp, err := a.client.MaterializeGateApproval(ctx, p.RunID, p.NodeExecutionID, approvalID, prResultItems(p.Merges))
	if err != nil {
		return GateMaterialized{}, err
	}
	issues := make([]GateIssue, len(resp.Issues))
	for i, iss := range resp.Issues {
		issues[i] = GateIssue(iss)
	}
	return GateMaterialized{Since: resp.Since, Issues: issues}, nil
}

// FileGateIssues is dispatched to the Worker's task queue and never runs here, exactly like
// RunGateGitHubSteps: the orchestrator registers only this stub so the workflow can name it.
func (a *Activities) FileGateIssues(_ context.Context, _ GateIssueParams) (GateIssuesResult, error) {
	return GateIssuesResult{}, fmt.Errorf("activity dispatched to Worker task queue, not the orchestrator")
}

func (a *Activities) RefuseGateApproval(ctx context.Context, p GateRefuseParams) error {
	if err := guardRun(ctx, p.RunID); err != nil {
		return err
	}
	approvalID, err := parseApprovalID(p.ApprovalID)
	if err != nil {
		return err
	}
	return a.client.RefuseGateApproval(ctx, p.RunID, p.NodeExecutionID, approvalID, p.Reason, prResultItems(p.Merges))
}

func issueResultItems(issues []GateIssueResult) []apiclient.GateIssueResultItem {
	items := make([]apiclient.GateIssueResultItem, len(issues))
	for i, iss := range issues {
		items[i] = apiclient.GateIssueResultItem(iss)
	}
	return items
}

func prResultItems(merges []GatePRResult) []apiclient.GatePRResultItem {
	items := make([]apiclient.GatePRResultItem, len(merges))
	for i, m := range merges {
		items[i] = apiclient.GatePRResultItem(m)
	}
	return items
}
