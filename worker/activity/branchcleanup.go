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

	branch := runBranchPrefix + p.RunID.String()
	counts := map[string]int{}
	for _, repo := range p.Repos {
		if err := ctx.Err(); err != nil {
			return res, err
		}
		outcome, err := a.cleanupOneBranch(ctx, tok.Value, repo, branch)
		if err != nil {
			return res, err
		}
		res.Outcomes = append(res.Outcomes, BranchCleanupOutcome{
			GitRepoID: repo.GitRepoID, OwnerRepo: repo.OwnerRepo, Outcome: outcome,
		})
		counts[outcome]++
		if outcome == BranchCleanupSkippedError {
			slog.Warn("run branch cleanup skipped a repo",
				"run_id", p.RunID, "repo", repo.OwnerRepo)
		}
	}
	slog.Info("run branch cleanup", "run_id", p.RunID,
		"deleted", counts[BranchCleanupDeleted], "kept_ahead", counts[BranchCleanupKeptAhead],
		"not_found", counts[BranchCleanupNotFound], "skipped_error", counts[BranchCleanupSkippedError])
	return res, nil
}

// cleanupOneBranch returns a non-nil error only for a cancelled context: that fails the whole
// step so Temporal retries it, since a partial attempt's deletions would otherwise be undetectable
// from a second attempt's NOT_FOUND reads. Every other fault becomes an outcome, never an error.
func (a *Activities) cleanupOneBranch(ctx context.Context, token string, repo BranchCleanupRepo, branch string) (string, error) {
	defaultBranch := strings.TrimSpace(repo.DefaultBranch)
	if defaultBranch == "" || defaultBranch == branch {
		return BranchCleanupSkippedError, nil
	}

	recordHeartbeat(ctx)
	ahead, err := a.gh.Compare(ctx, token, repo.OwnerRepo, defaultBranch, branch)
	switch {
	case err != nil:
		if cerr := ctx.Err(); cerr != nil {
			return "", cerr
		}
		if isNotFound(err) {
			return BranchCleanupNotFound, nil
		}
		return BranchCleanupSkippedError, nil
	case ahead > 0:
		return BranchCleanupKeptAhead, nil
	case ahead < 0:
		// GitHub never sends a negative ahead_by, so this is a malformed answer, not "not ahead".
		return BranchCleanupSkippedError, nil
	}

	recordHeartbeat(ctx)
	if err := a.gh.DeleteRef(ctx, token, repo.OwnerRepo, "heads/"+branch); err != nil {
		if cerr := ctx.Err(); cerr != nil {
			return "", cerr
		}
		if isNotFound(err) {
			return BranchCleanupNotFound, nil
		}
		return BranchCleanupSkippedError, nil
	}
	return BranchCleanupDeleted, nil
}

func isNotFound(err error) bool {
	var apiErr *github.APIError
	return errors.As(err, &apiErr) && apiErr.Status == http.StatusNotFound
}
