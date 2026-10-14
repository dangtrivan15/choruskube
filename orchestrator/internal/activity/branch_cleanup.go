package activity

import (
	"context"
	"fmt"

	"github.com/google/uuid"
)

// The outcomes the Worker answers with, in BranchCleanupResult.Outcomes[i].Outcome.
const (
	BranchCleanupDeleted      = "DELETED"
	BranchCleanupKeptAhead    = "KEPT_AHEAD"
	BranchCleanupNotFound     = "NOT_FOUND"
	BranchCleanupSkippedError = "SKIPPED_ERROR"
)

// The Worker's branch-cleanup types, copied field for field. Temporal's JSON converter makes
// these tags the wire contract, so a rename on one side blanks fields silently instead of failing.

// BranchCleanupRepo is one repo the Worker should compare and, if not ahead, delete the run
// branch for.
type BranchCleanupRepo struct {
	GitRepoID     uuid.UUID `json:"gitRepoId"`
	OwnerRepo     string    `json:"ownerRepo"`
	DefaultBranch string    `json:"defaultBranch"`
}

// BranchCleanupParams is the Worker step's input, dispatched to the run's own task queue.
type BranchCleanupParams struct {
	RunID uuid.UUID           `json:"runId"`
	Repos []BranchCleanupRepo `json:"repos"`
}

// BranchCleanupOutcome.Outcome is one of the BranchCleanup* constants above.
type BranchCleanupOutcome struct {
	GitRepoID uuid.UUID `json:"gitRepoId"`
	OwnerRepo string    `json:"ownerRepo"`
	Outcome   string    `json:"outcome"`
}

// BranchCleanupResult carries one outcome per planned repo.
type BranchCleanupResult struct {
	Outcomes []BranchCleanupOutcome `json:"outcomes"`
}

// BranchCleanupPlanParams names the run whose repos to plan cleanup for.
type BranchCleanupPlanParams struct {
	RunID uuid.UUID
}

// BranchCleanupPlan is the read-only answer PlanBranchCleanup hands to the Worker step.
type BranchCleanupPlan struct {
	Repos []BranchCleanupRepo `json:"repos"`
}

// PlanBranchCleanup reads which repos a completed run touched, and each one's owner/repo slug and
// default branch, from the API server. Returns the client's error untouched: a 400/404/409
// rejection is a *temporal.ApplicationError the SDK already reads as non-retryable, and wrapping
// it here would hide that from the workflow.
func (a *Activities) PlanBranchCleanup(ctx context.Context, p BranchCleanupPlanParams) (BranchCleanupPlan, error) {
	if err := guardRun(ctx, p.RunID); err != nil {
		return BranchCleanupPlan{}, err
	}
	resp, err := a.client.GetBranchCleanupPlan(ctx, p.RunID)
	if err != nil {
		return BranchCleanupPlan{}, err
	}
	repos := make([]BranchCleanupRepo, len(resp.Repos))
	for i, r := range resp.Repos {
		repos[i] = BranchCleanupRepo(r)
	}
	return BranchCleanupPlan{Repos: repos}, nil
}

// CleanupRunBranches is dispatched to the Worker's task queue and never runs here. The method
// exists so the workflow can derive the activity type name from it.
func (a *Activities) CleanupRunBranches(_ context.Context, _ BranchCleanupParams) (BranchCleanupResult, error) {
	return BranchCleanupResult{}, fmt.Errorf("activity dispatched to Worker task queue, not the orchestrator")
}
