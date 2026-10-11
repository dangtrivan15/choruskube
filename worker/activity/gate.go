package activity

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/google/uuid"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// The Kind values of a GatePRResult.
const (
	GatePRMerged        = "MERGED"
	GatePRAlreadyMerged = "ALREADY_MERGED"
	GatePRSkippedClosed = "SKIPPED_CLOSED"
)

// The Gate* types are copied verbatim by the orchestrator. Temporal's JSON converter makes these
// tags the wire contract, so a rename on one side blanks fields silently instead of failing.

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

type GateGitHubParams struct {
	RunID       uuid.UUID     `json:"runId"`
	Compares    []GateCompare `json:"compares"`
	PRs         []GatePR      `json:"prs"`
	MergeMethod string        `json:"mergeMethod"`
}

type GateCompareResult struct {
	GitRepoID uuid.UUID `json:"gitRepoId"`
	AheadBy   int       `json:"aheadBy"`
	BaseGone  bool      `json:"baseGone"`
}

// GatePRResult.Kind is one of GatePRMerged, GatePRAlreadyMerged and GatePRSkippedClosed.
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

// recordHeartbeat is the SDK's RecordHeartbeat behind a seam, which panics outside a running
// activity. Reassigned only from _test.go, and never from a test calling t.Parallel.
var recordHeartbeat = temporalactivity.RecordHeartbeat

const (
	gatePhaseCompare = "compare"
	gatePhaseInspect = "inspect"
	gatePhaseMerge   = "merge"
)

// gateProgress is what every heartbeat saves for a retry. A retry that finds Phase merge skips the
// compares: the merges an earlier attempt made are on main by then and would read as drift.
type gateProgress struct {
	Phase    string              `json:"phase"`
	Index    int                 `json:"index"`
	Compares []GateCompareResult `json:"compares,omitempty"`
}

// savedProgress is the previous attempt's last heartbeat; one that cannot be read counts as none,
// which re-runs the compares. A seam like recordHeartbeat, for the same reason.
var savedProgress = func(ctx context.Context) (gateProgress, bool) {
	var p gateProgress
	if !temporalactivity.HasHeartbeatDetails(ctx) || temporalactivity.GetHeartbeatDetails(ctx, &p) != nil {
		return gateProgress{}, false
	}
	return p, true
}

// RunGateGitHubSteps re-checks each repo's base, then inspects and merges the pull requests. A
// failure a person must act on is Refused; one that may heal is an error for Temporal to retry,
// and a retry reports what already merged as ALREADY_MERGED.
func (a *Activities) RunGateGitHubSteps(ctx context.Context, p GateGitHubParams) (GateGitHubResult, error) {
	res := GateGitHubResult{Compares: []GateCompareResult{}, Merges: []GatePRResult{}}
	if err := bindGateRun(ctx, p.RunID); err != nil {
		return res, err
	}
	if len(p.Compares) == 0 && len(p.PRs) == 0 {
		return res, nil
	}
	if len(p.PRs) > 0 && !validMergeMethod(p.MergeMethod) {
		return res, temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("merge method %q is not merge, squash or rebase", p.MergeMethod), "GateMergeMethod", nil)
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
	s := &gateSteps{gh: a.gh, token: tok.Value, method: p.MergeMethod}

	saved, resumed := savedProgress(ctx)
	if resumed && saved.Phase == gatePhaseMerge {
		s.merging = true
		res.Compares = append(res.Compares, saved.Compares...)
		s.passed = res.Compares
	}
	in, err := s.inspect(ctx, p.PRs, &res)
	if err != nil {
		return res, err
	}
	if !s.merging {
		if err := s.compare(ctx, p.Compares, in, &res); err != nil {
			return res, err
		}
		if res.Stale {
			res.Merges = []GatePRResult{}
			return res, nil
		}
		if res.Refused != "" {
			return res, nil
		}
		s.passed = res.Compares
	}
	if len(in.blockers) > 0 {
		res.Refused = refusalBeforeMerging(in)
		return res, nil
	}
	return res, s.merge(ctx, in.queue, &res)
}

func refusalBeforeMerging(in inspection) string {
	text := "Approval needs every pull request to be mergeable; nothing was merged. "
	if len(in.alreadyMerged) > 0 {
		text = "Approval needs every pull request to be mergeable; nothing more was merged. Already merged: " +
			strings.Join(in.alreadyMerged, ", ") + ". "
	}
	return text + strings.Join(in.blockers, "; ")
}

// bindGateRun refuses a run id the workflow's own id contradicts: the params travel through the
// workflow, but the activity must only ever act on, and fetch credentials for, its own run.
func bindGateRun(ctx context.Context, runID uuid.UUID) error {
	bound, err := runIDOf(ctx)
	if err != nil {
		return temporal.NewNonRetryableApplicationError("gate steps outside a run's workflow", "GateRunBinding", err)
	}
	if bound != runID {
		return temporal.NewNonRetryableApplicationError(
			fmt.Sprintf("gate steps for run %s requested by the workflow of run %s", runID, bound),
			"GateRunBinding", nil)
	}
	return nil
}

func validMergeMethod(m string) bool { return m == "merge" || m == "squash" || m == "rebase" }

type gateSteps struct {
	gh     *github.Client
	token  string
	method string
	// merging is set on a retry that resumed in the merge phase: every later heartbeat keeps
	// that phase, or a second failure before the first merge would send the next retry to re-compare.
	merging bool
	passed  []GateCompareResult
}

func (s *gateSteps) beat(ctx context.Context, phase string, i int) {
	if s.merging {
		phase = gatePhaseMerge
	}
	recordHeartbeat(ctx, gateProgress{Phase: phase, Index: i, Compares: s.passed})
}

type inspection struct {
	queue         []queuedMerge
	blockers      []string
	alreadyMerged []string
	repos         map[string]*repoPRs
}

type repoPRs struct{ total, merged int }

// fullyMerged is true only for a repo with pull requests that all merged. A repo with none, or
// with any unmerged, must still be compared, or an unchecked merge could follow.
func (in inspection) fullyMerged(ownerRepo string) bool {
	r := in.repos[ownerRepo]
	return r != nil && r.merged == r.total
}

type queuedMerge struct {
	pr    GatePR
	label string
	head  string
}

// compare records every repo's drift, and stops at the first refusal. Merging a repo whose main
// moved would ship a change nobody tested against the new main.
func (s *gateSteps) compare(ctx context.Context, compares []GateCompare, in inspection, res *GateGitHubResult) error {
	for i, c := range compares {
		if in.fullyMerged(c.OwnerRepo) {
			// Main carries this repo's own merges, which a compare would read as drift. A zero
			// result keeps the repo from reading as unavailable should another repo hold the run.
			res.Compares = append(res.Compares, GateCompareResult{GitRepoID: c.GitRepoID})
			continue
		}
		s.beat(ctx, gatePhaseCompare, i)
		if err := ctx.Err(); err != nil {
			return err
		}
		ahead, err := s.gh.Compare(ctx, s.token, c.OwnerRepo, c.BaseSha, c.Branch)
		var api *github.APIError
		switch {
		case err == nil:
			res.Compares = append(res.Compares, GateCompareResult{GitRepoID: c.GitRepoID, AheadBy: ahead})
			res.Stale = res.Stale || ahead > 0
		case errors.As(err, &api) && api.Status == http.StatusNotFound:
			res.Compares = append(res.Compares, GateCompareResult{GitRepoID: c.GitRepoID, BaseGone: true})
			res.Stale = true
		default:
			reason, refused := readRefusal(err, c.OwnerRepo, c.OwnerRepo)
			if !refused {
				return failed(ctx, "compare "+c.OwnerRepo, err)
			}
			// A credential fault must not read as drift, which would send the run back for a rebase.
			res.Stale = false
			res.Refused = reason
			return nil
		}
	}
	return nil
}

// inspect reads every pull request before anything merges, so one that cannot merge refuses the
// whole approval instead of leaving the repos half-merged.
func (s *gateSteps) inspect(ctx context.Context, prs []GatePR, res *GateGitHubResult) (inspection, error) {
	in := inspection{repos: map[string]*repoPRs{}}
	for i, pr := range prs {
		s.beat(ctx, gatePhaseInspect, i)
		if err := ctx.Err(); err != nil {
			return inspection{}, err
		}
		label := prLabel(pr)
		if in.repos[pr.OwnerRepo] == nil {
			in.repos[pr.OwnerRepo] = &repoPRs{}
		}
		in.repos[pr.OwnerRepo].total++
		detail, err := s.gh.PullRequest(ctx, s.token, pr.OwnerRepo, pr.Number)
		if err != nil {
			reason, refused := readRefusal(err, pr.OwnerRepo, label)
			if !refused {
				return inspection{}, failed(ctx, "inspect "+label, err)
			}
			if !slices.Contains(in.blockers, reason) {
				in.blockers = append(in.blockers, reason)
			}
			continue
		}
		switch {
		case detail.Merged:
			res.Merges = append(res.Merges,
				GatePRResult{RowID: pr.RowID, Kind: GatePRAlreadyMerged, MergedAt: utcOrNow(detail.MergedAt)})
			in.alreadyMerged = append(in.alreadyMerged, label)
			in.repos[pr.OwnerRepo].merged++
		case detail.State == "closed":
			res.Merges = append(res.Merges, GatePRResult{RowID: pr.RowID, Kind: GatePRSkippedClosed})
		case detail.Draft:
			in.blockers = append(in.blockers, label+" is a draft — mark it ready for review or merge it by hand")
		// A nil Mergeable is GitHub still computing it, and the merge call refuses a real conflict.
		case detail.Mergeable != nil && !*detail.Mergeable:
			in.blockers = append(in.blockers, label+" has merge conflicts with "+detail.BaseRef)
		default:
			in.queue = append(in.queue, queuedMerge{pr: pr, label: label, head: detail.HeadSHA})
		}
	}
	return in, nil
}

// merge merges in order, each pinned to the head inspected, and stops at the first refusal. A
// merge on GitHub cannot be undone, so res keeps everything merged before that.
func (s *gateSteps) merge(ctx context.Context, queue []queuedMerge, res *GateGitHubResult) error {
	var mergedNow []string
	for i, item := range queue {
		s.beat(ctx, gatePhaseMerge, i)
		if err := ctx.Err(); err != nil {
			return err
		}
		sha, err := s.gh.Merge(ctx, s.token, item.pr.OwnerRepo, item.pr.Number, s.method, item.head)
		if err == nil {
			res.Merges = append(res.Merges,
				GatePRResult{RowID: item.pr.RowID, Kind: GatePRMerged, MergedAt: utcOrNow(nil), MergeSha: sha})
			mergedNow = append(mergedNow, item.label)
			continue
		}
		// A merge call that hit the client timeout plus a slow re-read can outlast the heartbeat
		// timeout, and Temporal would then start a concurrent retry mid-merge.
		s.beat(ctx, gatePhaseMerge, i)
		recovered, ok := s.reReadAsMerged(ctx, item.pr)
		if cerr := ctx.Err(); cerr != nil {
			return cerr
		}
		if ok {
			res.Merges = append(res.Merges, recovered)
			mergedNow = append(mergedNow, item.label)
			continue
		}
		reason, refused := mergeRefusal(err, item.pr.OwnerRepo, item.label)
		if !refused {
			return failed(ctx, "merge "+item.label, err)
		}
		merged := "none"
		if len(mergedNow) > 0 {
			merged = strings.Join(mergedNow, ", ")
		}
		res.Refused = fmt.Sprintf("Merged: %s. Could not merge %s: %s", merged, item.label, reason)
		return nil
	}
	return nil
}

// reReadAsMerged counts a failed merge call that GitHub then reports merged as a merge: a lost
// response most likely did it, and refusing would strand a pull request that already landed.
func (s *gateSteps) reReadAsMerged(ctx context.Context, pr GatePR) (GatePRResult, bool) {
	if ctx.Err() != nil {
		return GatePRResult{}, false
	}
	detail, err := s.gh.PullRequest(ctx, s.token, pr.OwnerRepo, pr.Number)
	if err != nil || !detail.Merged {
		return GatePRResult{}, false
	}
	return GatePRResult{RowID: pr.RowID, Kind: GatePRMerged, MergedAt: utcOrNow(detail.MergedAt)}, true
}

// readRefusal gives the reason for a GitHub failure a person must act on. ok=false means it may
// heal, and the caller returns the error so Temporal retries.
func readRefusal(err error, ownerRepo, label string) (reason string, ok bool) {
	var api *github.APIError
	if !errors.As(err, &api) {
		return "", false
	}
	switch {
	case api.Status == http.StatusUnauthorized || api.Status == http.StatusForbidden:
		return "the GitHub credential cannot access " + ownerRepo, true
	case api.Status == http.StatusNotFound:
		return label + " not found (or not visible to the credential)", true
	case api.Status >= 500 || api.Status == http.StatusTooManyRequests || api.Status == http.StatusRequestTimeout:
		return "", false
	default:
		return fmt.Sprintf("GitHub rejected the request for %s (HTTP %d)", label, api.Status), true
	}
}

func mergeRefusal(err error, ownerRepo, label string) (reason string, ok bool) {
	var refused *github.RefusedError
	if errors.As(err, &refused) {
		if refused.Status == http.StatusConflict {
			return "its head branch changed while merging — retry", true
		}
		return refused.Reason, true
	}
	var api *github.APIError
	if errors.As(err, &api) && api.Status == http.StatusForbidden {
		return "the GitHub credential cannot access " + ownerRepo + " for merging — it needs write access to " +
			"contents and pull requests (and workflows, if the PR changes .github/workflows/)", true
	}
	return readRefusal(err, ownerRepo, label)
}

func failed(ctx context.Context, op string, err error) error {
	if cerr := ctx.Err(); cerr != nil {
		return cerr
	}
	return fmt.Errorf("%s: %w", op, err)
}

func prLabel(pr GatePR) string {
	if pr.Label != "" {
		return pr.Label
	}
	return fmt.Sprintf("%s#%d", pr.OwnerRepo, pr.Number)
}

func utcOrNow(t *time.Time) *time.Time {
	u := time.Now().UTC()
	if t != nil {
		u = t.UTC()
	}
	return &u
}
