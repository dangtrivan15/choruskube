package activity

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.temporal.io/sdk/temporal"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// The GateIssue* types below are copied verbatim by the orchestrator, the same as the Gate* types
// in gate.go: Temporal's JSON converter makes these tags the wire contract, so a rename on one side
// blanks fields silently instead of failing.

// GateIssue is one task's requested GitHub issue, from the control plane's roadmap write step.
type GateIssue struct {
	TaskID    uuid.UUID `json:"taskId"`
	GitRepoID uuid.UUID `json:"gitRepoId"`
	OwnerRepo string    `json:"ownerRepo"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
}

// GateIssueParams.Since is the roadmap write's claim time; the lookup widens it by its own
// clock-skew allowance rather than requiring the caller to.
type GateIssueParams struct {
	RunID           uuid.UUID   `json:"runId"`
	NodeExecutionID uuid.UUID   `json:"nodeExecutionId"`
	Since           time.Time   `json:"since"`
	Issues          []GateIssue `json:"issues"`
}

// GateIssueResult is one task's outcome: a filed or found issue (Number, URL), or Failure. Exactly
// one of the two is set.
type GateIssueResult struct {
	TaskID  uuid.UUID `json:"taskId"`
	Number  int       `json:"number,omitempty"`
	URL     string    `json:"url,omitempty"`
	Failure string    `json:"failure,omitempty"`
}

type GateIssuesResult struct {
	Issues []GateIssueResult `json:"issues"`
}

const (
	// maxIssueBodyRunes sits under GitHub's 65,536-character body limit by enough to fit the key
	// appended after it; a body over the limit is refused, and the task left without an issue.
	maxIssueBodyRunes  = 65000
	maxIssueTitleRunes = 256
	// issueLookupSkew covers clock skew between the write step's claim and GitHub's own clock, so
	// a listing never starts after the issue it is meant to find.
	issueLookupSkew = time.Hour
)

func issueKey(taskID uuid.UUID) string {
	return "<!-- choruskube-task:" + taskID.String() + " -->"
}

var issueKeyPattern = regexp.MustCompile(`<!-- choruskube-task:([0-9a-fA-F-]{36}) -->`)

// taskKeyIn extracts the task id hidden in an issue's body, if any.
func taskKeyIn(body string) (uuid.UUID, bool) {
	m := issueKeyPattern.FindStringSubmatch(body)
	if m == nil {
		return uuid.Nil, false
	}
	id, err := uuid.Parse(m[1])
	if err != nil {
		return uuid.Nil, false
	}
	return id, true
}

func truncateRunes(s string, max int) string {
	r := []rune(s)
	if len(r) <= max {
		return s
	}
	return string(r[:max])
}

// issueBody cuts description so the key that follows it always fits, however long description is.
func issueBody(description string, taskID uuid.UUID) string {
	return truncateRunes(description, maxIssueBodyRunes) + "\n\n" + issueKey(taskID)
}

// issueHeartbeat is a liveness signal only. Unlike the gate's merge step, a retried issue step
// starts over from the lookup rather than resuming from here, because the lookup itself is how it
// recognises what an earlier attempt already filed.
type issueHeartbeat struct {
	Phase string `json:"phase"`
	Index int    `json:"index"`
}

// lastAttempt reports whether this is the activity's final scheduled try, so a transient failure
// can stop retrying and hand back best-effort results instead of blocking the gate forever.
// Reassigned only from _test.go, and never from a test calling t.Parallel.
var lastAttempt = func(ctx context.Context) bool {
	info := activityInfo(ctx)
	if info.RetryPolicy == nil || info.RetryPolicy.MaximumAttempts <= 0 {
		return false
	}
	return info.Attempt >= info.RetryPolicy.MaximumAttempts
}

// FileGateIssues looks up each requested task's issue by its hidden key, among a repo's issues
// updated since the roadmap write (minus an hour of clock-skew slack), and files only the ones
// missing. It is bound to its own run the same way the gate's GitHub step is, and it only calls
// GitHub and returns results: nothing here writes to the control plane.
func (a *Activities) FileGateIssues(ctx context.Context, p GateIssueParams) (GateIssuesResult, error) {
	if err := bindRun(ctx, p.RunID, "gate issue filing", "GateRunBinding"); err != nil {
		return GateIssuesResult{}, err
	}
	if len(p.Issues) == 0 {
		return GateIssuesResult{Issues: []GateIssueResult{}}, nil
	}
	if p.Since.IsZero() {
		return GateIssuesResult{}, temporal.NewNonRetryableApplicationError(
			"GateIssueParams without Since, which the lookup window is measured from", "GateIssueParams", nil)
	}
	if a.credentials == nil || a.gh == nil {
		return GateIssuesResult{}, errors.New("this Worker has no GitHub credentials or client configured")
	}
	tok, err := a.credentials.GitHubToken(ctx, p.RunID)
	if err != nil {
		return GateIssuesResult{}, fmt.Errorf("obtain GitHub token for run %s: %w", p.RunID, err)
	}
	if strings.TrimSpace(tok.Value) == "" {
		return GateIssuesResult{}, errors.New("credential provider returned a blank GitHub token")
	}

	f := &issueFiler{gh: a.gh, token: tok.Value, last: lastAttempt(ctx)}
	return f.run(ctx, p)
}

type issueFiler struct {
	gh    *github.Client
	token string
	last  bool
}

func (f *issueFiler) run(ctx context.Context, p GateIssueParams) (GateIssuesResult, error) {
	order, byTask := dedupeGateIssues(p.Issues)
	outcome := make(map[uuid.UUID]GateIssueResult, len(order))

	var repoOrder []string
	repoTasks := map[string][]uuid.UUID{}
	seenRepo := map[string]bool{}
	for _, id := range order {
		repo := byTask[id].OwnerRepo
		if !github.ValidOwnerRepo(repo) {
			outcome[id] = GateIssueResult{TaskID: id, Failure: "invalid repository"}
			continue
		}
		if !seenRepo[repo] {
			seenRepo[repo] = true
			repoOrder = append(repoOrder, repo)
		}
		repoTasks[repo] = append(repoTasks[repo], id)
	}

	since := p.Since.Add(-issueLookupSkew)
	for _, repo := range repoOrder {
		issues, err := f.gh.IssuesSince(ctx, f.token, repo, since, func(page int) {
			recordHeartbeat(ctx, issueHeartbeat{Phase: "lookup", Index: page})
		})
		if err != nil {
			if reason, failed := classifyRepoErr(err, repo); failed {
				for _, id := range repoTasks[repo] {
					outcome[id] = GateIssueResult{TaskID: id, Failure: reason}
				}
				continue
			}
			if f.last {
				markRemainingAsKeptFailing(order, outcome)
				return orderedIssueResult(order, outcome), nil
			}
			return GateIssuesResult{}, fmt.Errorf("list issues for %s: %w", repo, err)
		}
		// Matched only against this repo's own tasks: a key quoted in another repo's issue would
		// link that repo's issue number here, and closing the task closes it. Recorded at once, so
		// a later failure on the last attempt cannot report an issue that exists as unfiled.
		found := lowestKeyedIssues(issues)
		for _, id := range repoTasks[repo] {
			if iss, ok := found[id]; ok {
				outcome[id] = GateIssueResult{TaskID: id, Number: iss.Number, URL: iss.HTMLURL}
			}
		}
	}

	for i, id := range order {
		if _, done := outcome[id]; done {
			continue
		}
		recordHeartbeat(ctx, issueHeartbeat{Phase: "file", Index: i})
		item := byTask[id]
		iss, err := f.gh.CreateIssue(ctx, f.token, item.OwnerRepo,
			truncateRunes(item.Title, maxIssueTitleRunes), issueBody(item.Body, id))
		if err == nil {
			outcome[id] = GateIssueResult{TaskID: id, Number: iss.Number, URL: iss.HTMLURL}
			continue
		}
		if reason, failed := classifyCreateErr(err); failed {
			outcome[id] = GateIssueResult{TaskID: id, Failure: reason}
			continue
		}
		if f.last {
			markRemainingAsKeptFailing(order, outcome)
			return orderedIssueResult(order, outcome), nil
		}
		return GateIssuesResult{}, fmt.Errorf("create issue for %s: %w", item.OwnerRepo, err)
	}

	return orderedIssueResult(order, outcome), nil
}

// lowestKeyedIssues maps each task key in one repo's listing to the lowest-numbered issue carrying
// it: the original, since an issue that later quotes the key has a higher number.
func lowestKeyedIssues(issues []github.Issue) map[uuid.UUID]github.Issue {
	found := map[uuid.UUID]github.Issue{}
	for _, iss := range issues {
		id, ok := taskKeyIn(iss.Body)
		if !ok {
			continue
		}
		if existing, have := found[id]; !have || iss.Number < existing.Number {
			found[id] = iss
		}
	}
	return found
}

// dedupeGateIssues keeps the first occurrence of each task id, and the order requests first named it.
func dedupeGateIssues(issues []GateIssue) ([]uuid.UUID, map[uuid.UUID]GateIssue) {
	order := make([]uuid.UUID, 0, len(issues))
	byTask := make(map[uuid.UUID]GateIssue, len(issues))
	for _, it := range issues {
		if _, ok := byTask[it.TaskID]; ok {
			continue
		}
		byTask[it.TaskID] = it
		order = append(order, it.TaskID)
	}
	return order, byTask
}

// classifyRepoErr reports a lookup failure a retry cannot fix: a refusal, or a credential GitHub no
// longer accepts. Anything else (failed=false) may heal, so the caller retries the whole step.
func classifyRepoErr(err error, repo string) (reason string, failed bool) {
	var refused *github.RefusedError
	if errors.As(err, &refused) {
		return "GitHub refused access to " + repo + ": " + refused.Reason, true
	}
	var api *github.APIError
	if errors.As(err, &api) && api.Status == http.StatusUnauthorized {
		return "GitHub refused access to " + repo + ": the run's GitHub credential was refused", true
	}
	return "", false
}

func classifyCreateErr(err error) (reason string, failed bool) {
	var refused *github.RefusedError
	if errors.As(err, &refused) {
		return "GitHub refused: " + refused.Reason, true
	}
	var api *github.APIError
	if errors.As(err, &api) && api.Status == http.StatusUnauthorized {
		return "the run's GitHub credential was refused", true
	}
	return "", false
}

// markRemainingAsKeptFailing is only reached on the step's last scheduled attempt: every task
// still without an outcome, including the one whose call just failed, is reported failed instead of
// retried again, so the gate can complete instead of waiting on a GitHub outage forever.
func markRemainingAsKeptFailing(order []uuid.UUID, outcome map[uuid.UUID]GateIssueResult) {
	for _, id := range order {
		if _, done := outcome[id]; !done {
			outcome[id] = GateIssueResult{TaskID: id, Failure: "GitHub kept failing"}
		}
	}
}

func orderedIssueResult(order []uuid.UUID, outcome map[uuid.UUID]GateIssueResult) GateIssuesResult {
	res := make([]GateIssueResult, 0, len(order))
	for _, id := range order {
		if r, ok := outcome[id]; ok {
			res = append(res, r)
			continue
		}
		// Unreached in practice: every id gains an outcome before this is called. Kept as a
		// fallback so a result is never silently dropped if that ever stops being true.
		res = append(res, GateIssueResult{TaskID: id, Failure: "GitHub kept failing"})
	}
	return GateIssuesResult{Issues: res}
}
