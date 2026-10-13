package activity

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"

	"github.com/google/uuid"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// The outcomes CleanupRunBranches answers with, in BranchCleanupResult.Outcomes[i].Outcome. The
// orchestrator copies these constants verbatim.
const (
	BranchCleanupDeleted      = "DELETED"
	BranchCleanupKeptAhead    = "KEPT_AHEAD"
	BranchCleanupNotFound     = "NOT_FOUND"
	BranchCleanupSkippedError = "SKIPPED_ERROR"
)

// runBranchPrefix must equal the orchestrator's working_branch format; a mismatch compares and
// deletes nothing, because every repo's run branch would then simply not exist under this name.
const runBranchPrefix = "choruskube-run-"

// The BranchCleanup* types are copied verbatim by the orchestrator. Temporal's JSON converter
// makes these tags the wire contract, so a rename on one side blanks fields silently instead of
// failing.

// BranchCleanupRepo is one repo to compare and, if not ahead, delete the run branch for.
type BranchCleanupRepo struct {
	GitRepoID     uuid.UUID `json:"gitRepoId"`
	OwnerRepo     string    `json:"ownerRepo"`
	DefaultBranch string    `json:"defaultBranch"`
}

// BranchCleanupParams is CleanupRunBranches' input.
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

// CleanupRunBranches best-effort deletes a completed run's per-repo run branch, once it is not
// ahead of its repo's default branch. A fault on one repo (an error, a 404, a malformed compare
// response, or an ahead branch) never stops the next: only a token failure or a cancelled context
// fails the step as a whole, which is the one case Temporal retries.
func (a *Activities) CleanupRunBranches(ctx context.Context, p BranchCleanupParams) (BranchCleanupResult, error) {
	res := BranchCleanupResult{Outcomes: []BranchCleanupOutcome{}}
	if err := bindRun(ctx, p.RunID, "branch cleanup", "BranchCleanupRunBinding"); err != nil {
		return res, err
	}
	if len(p.Repos) == 0 {
		return res, nil
	}
	if a.credentials == nil || a.gh == nil {
		return res, errors.New("this Worker has no GitHub credentials or client configured")
	}
	tok, err := a.credentials.GitHubToken(ctx, p.RunID)
	if err != nil {
		return res, fmt.Errorf("obtain GitHub token for run %s: %w", p.RunID, err)
	}
	if strings.TrimSpace(tok.Value) == "" {
		return res, errors.New("credential provider returned a blank GitHub token")
	}

	counts := map[string]int{}
	for _, repo := range p.Repos {
		if err := ctx.Err(); err != nil {
			return res, err
		}
		outcome, err := a.cleanupOneBranch(ctx, tok.Value, p.RunID, repo)
		if err != nil {
			return res, err
		}
		res.Outcomes = append(res.Outcomes, BranchCleanupOutcome{
			GitRepoID: repo.GitRepoID, OwnerRepo: repo.OwnerRepo, Outcome: outcome,
		})
		counts[outcome]++
	}
	slog.Info("run branch cleanup", "run_id", p.RunID,
		"deleted", counts[BranchCleanupDeleted], "kept_ahead", counts[BranchCleanupKeptAhead],
		"not_found", counts[BranchCleanupNotFound], "skipped_error", counts[BranchCleanupSkippedError])
	return res, nil
}

// cleanupOneBranch returns a non-nil error only once ctx is done, which fails the step for
// Temporal to retry; turned into an outcome instead, every remaining repo would read
// SKIPPED_ERROR and the step would end without a retry.
func (a *Activities) cleanupOneBranch(ctx context.Context, token string, runID uuid.UUID, repo BranchCleanupRepo) (string, error) {
	branch := runBranchPrefix + runID.String()
	skip := func(cause error) (string, error) {
		warnSkipped(runID, repo, cause)
		return BranchCleanupSkippedError, nil
	}
	fault := func(call string, err error) (string, error) {
		if cerr := ctx.Err(); cerr != nil {
			return "", cerr
		}
		if isNotFound(err) {
			return BranchCleanupNotFound, nil
		}
		return skip(fmt.Errorf("%s: %w", call, err))
	}

	defaultBranch := strings.TrimSpace(repo.DefaultBranch)
	if defaultBranch == "" || defaultBranch == branch {
		return skip(fmt.Errorf("planned default branch %q is blank or is the run branch", repo.DefaultBranch))
	}

	recordHeartbeat(ctx)
	ahead, err := a.gh.Compare(ctx, token, repo.OwnerRepo, defaultBranch, branch)
	switch {
	case err != nil:
		return fault("compare", err)
	case ahead > 0:
		return BranchCleanupKeptAhead, nil
	case ahead < 0:
		// GitHub never sends a negative ahead_by, so this is a malformed answer, not "not ahead".
		return skip(fmt.Errorf("compare answered ahead_by %d", ahead))
	}

	recordHeartbeat(ctx)
	if err := a.gh.DeleteRef(ctx, token, repo.OwnerRepo, "heads/"+branch); err != nil {
		return fault("delete", err)
	}
	return BranchCleanupDeleted, nil
}

// warnSkipped logs why a repo's branch was kept. cause must never carry the token; the github
// client's errors hold neither it nor a response body.
func warnSkipped(runID uuid.UUID, repo BranchCleanupRepo, cause error) {
	attrs := []any{"run_id", runID, "repo", repo.OwnerRepo, "error", cause}
	var apiErr *github.APIError
	if errors.As(cause, &apiErr) {
		attrs = append(attrs, "status", apiErr.Status)
	}
	slog.Warn("run branch cleanup skipped a repo", attrs...)
}

func isNotFound(err error) bool {
	var apiErr *github.APIError
	return errors.As(err, &apiErr) && apiErr.Status == http.StatusNotFound
}
