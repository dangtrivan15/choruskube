package activity

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	temporalactivity "go.temporal.io/sdk/activity"
	"go.temporal.io/sdk/converter"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/worker/credential"
	"github.com/dangtrivan15/choruskube/worker/github"
)

const gateToken = "ghs_gate_test_token"

type fakeGateProvider struct {
	err   error
	calls []uuid.UUID
}

func (f *fakeGateProvider) GitHubToken(_ context.Context, runID uuid.UUID) (credential.Token, error) {
	f.calls = append(f.calls, runID)
	if f.err != nil {
		return credential.Token{}, f.err
	}
	return credential.Token{Value: gateToken}, nil
}

func (f *fakeGateProvider) AgentCredentials(context.Context, uuid.UUID) (credential.Agent, bool, error) {
	return credential.Agent{}, false, nil
}

type gateRequest struct{ method, path, body string }

// gateGitHub is a GitHub stand-in that fails the test on any request it has no route for, so a
// call the activity must not make shows up as an error rather than a silent 404.
type gateGitHub struct {
	t      *testing.T
	srv    *httptest.Server
	mu     sync.Mutex
	routes map[string]http.HandlerFunc
	seen   []gateRequest
}

func newGateGitHub(t *testing.T) *gateGitHub {
	t.Helper()
	g := &gateGitHub{t: t, routes: map[string]http.HandlerFunc{}}
	g.srv = httptest.NewServer(g)
	t.Cleanup(g.srv.Close)
	return g
}

func (g *gateGitHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	g.mu.Lock()
	g.seen = append(g.seen, gateRequest{r.Method, r.URL.Path, string(body)})
	h := g.routes[r.Method+" "+r.URL.Path]
	g.mu.Unlock()
	if got := r.Header.Get("Authorization"); got != "Bearer "+gateToken {
		g.t.Errorf("request %s %s carried Authorization %q", r.Method, r.URL.Path, got)
	}
	if h == nil {
		g.t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	h(w, r)
}

func (g *gateGitHub) route(method, path string, h http.HandlerFunc) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.routes[method+" "+path] = h
}

func (g *gateGitHub) sequence() []string {
	g.mu.Lock()
	defer g.mu.Unlock()
	out := make([]string, 0, len(g.seen))
	for _, r := range g.seen {
		out = append(out, r.method+" "+r.path)
	}
	return out
}

func (g *gateGitHub) puts() []gateRequest {
	g.mu.Lock()
	defer g.mu.Unlock()
	var out []gateRequest
	for _, r := range g.seen {
		if r.method == http.MethodPut {
			out = append(out, r)
		}
	}
	return out
}

func (g *gateGitHub) count(method, path string) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	n := 0
	for _, r := range g.seen {
		if r.method == method && r.path == path {
			n++
		}
	}
	return n
}

func jsonReply(status int, body any) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_ = json.NewEncoder(w).Encode(body)
	}
}

func statusReply(status int) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(status) }
}

func comparePath(c GateCompare) string {
	return "/repos/" + c.OwnerRepo + "/compare/" + c.BaseSha + "..." + c.Branch
}

func prPath(p GatePR) string { return fmt.Sprintf("/repos/%s/pulls/%d", p.OwnerRepo, p.Number) }

func mergePath(p GatePR) string { return prPath(p) + "/merge" }

func (g *gateGitHub) compare(c GateCompare, aheadBy int) {
	g.route(http.MethodGet, comparePath(c), jsonReply(http.StatusOK, map[string]any{"ahead_by": aheadBy}))
}

func (g *gateGitHub) pr(p GatePR, s prState) {
	g.route(http.MethodGet, prPath(p), jsonReply(http.StatusOK, s.wire()))
}

func (g *gateGitHub) merge(p GatePR, h http.HandlerFunc) { g.route(http.MethodPut, mergePath(p), h) }

type prState struct {
	State     string
	Merged    bool
	MergedAt  string
	Draft     bool
	Mergeable *bool
	Head      string
	Base      string
}

func openPR(head string) prState {
	yes := true
	return prState{State: "open", Mergeable: &yes, Head: head, Base: "main"}
}

func (s prState) wire() map[string]any {
	var mergedAt any
	if s.MergedAt != "" {
		mergedAt = s.MergedAt
	}
	base := s.Base
	if base == "" {
		base = "main"
	}
	return map[string]any{
		"state":     s.State,
		"merged":    s.Merged,
		"merged_at": mergedAt,
		"draft":     s.Draft,
		"mergeable": s.Mergeable,
		"head":      map[string]any{"sha": s.Head},
		"base":      map[string]any{"ref": base},
	}
}

func mergeOK(sha string) http.HandlerFunc {
	return jsonReply(http.StatusOK, map[string]any{"sha": sha, "merged": true})
}

func gateCompare(ownerRepo string) GateCompare {
	return GateCompare{GitRepoID: uuid.New(), OwnerRepo: ownerRepo, Branch: "main", BaseSha: "abc123"}
}

func gatePR(ownerRepo string, number int) GatePR {
	return GatePR{
		RowID:     uuid.New(),
		OwnerRepo: ownerRepo,
		Number:    number,
		Label:     fmt.Sprintf("%s#%d", ownerRepo, number),
		PRURL:     fmt.Sprintf("https://github.com/%s/pull/%d", ownerRepo, number),
	}
}

type gateRig struct {
	acts   *Activities
	gh     *gateGitHub
	prov   *fakeGateProvider
	run    uuid.UUID
	beats  []gateProgress
	onBeat func()
	// resume is what an earlier attempt's last heartbeat left behind; nil means a first attempt.
	resume *gateProgress
}

// newGateRig stubs the SDK's heartbeat calls, which panic outside a running activity.
func newGateRig(t *testing.T) *gateRig {
	t.Helper()
	rig := newGateBase(t)
	originalBeat, originalSaved := recordHeartbeat, savedProgress
	recordHeartbeat = func(_ context.Context, details ...interface{}) {
		p, ok := details[0].(gateProgress)
		if !ok {
			t.Errorf("heartbeat details are a %T, want gateProgress", details[0])
			return
		}
		rig.beats = append(rig.beats, p)
		if rig.onBeat != nil {
			rig.onBeat()
		}
	}
	savedProgress = func(context.Context) (gateProgress, bool) {
		if rig.resume == nil {
			return gateProgress{}, false
		}
		return *rig.resume, true
	}
	t.Cleanup(func() { recordHeartbeat, savedProgress = originalBeat, originalSaved })
	return rig
}

func newGateBase(t *testing.T) *gateRig {
	t.Helper()
	rig := &gateRig{run: stubbedRun(t), gh: newGateGitHub(t), prov: &fakeGateProvider{}}
	rig.acts = &Activities{}
	rig.acts.SetCredentials(rig.prov)
	rig.acts.SetGitHub(github.New(rig.gh.srv.URL))
	return rig
}

func (r *gateRig) beatNames() []string {
	var out []string
	for _, b := range r.beats {
		out = append(out, fmt.Sprintf("%s:%d", b.Phase, b.Index))
	}
	return out
}

func (r *gateRig) params(compares []GateCompare, prs []GatePR) GateGitHubParams {
	return GateGitHubParams{RunID: r.run, Compares: compares, PRs: prs, MergeMethod: "squash"}
}

func (r *gateRig) exec(t *testing.T, compares []GateCompare, prs []GatePR) (GateGitHubResult, error) {
	t.Helper()
	return r.acts.RunGateGitHubSteps(context.Background(), r.params(compares, prs))
}

func TestRunGateGitHubSteps_StaleCompareReturnsStaleAndMergesNothing(t *testing.T) {
	rig := newGateRig(t)
	web, api := gateCompare("acme/web"), gateCompare("acme/api")
	rig.gh.compare(web, 2)
	rig.gh.compare(api, 0)

	res, err := rig.exec(t, []GateCompare{web, api}, []GatePR{gatePR("acme/web", 7)})

	require.NoError(t, err)
	assert.True(t, res.Stale)
	assert.Equal(t, []GateCompareResult{
		{GitRepoID: web.GitRepoID, AheadBy: 2},
		{GitRepoID: api.GitRepoID, AheadBy: 0},
	}, res.Compares)
	assert.Empty(t, res.Merges)
	assert.Empty(t, res.Refused)
	assert.Equal(t, []string{"GET " + comparePath(web), "GET " + comparePath(api)}, rig.gh.sequence(),
		"every compare runs, and no pull request is touched")
}

func TestRunGateGitHubSteps_Compare404IsBaseGoneAndStale(t *testing.T) {
	rig := newGateRig(t)
	web, api := gateCompare("acme/web"), gateCompare("acme/api")
	rig.gh.route(http.MethodGet, comparePath(web), statusReply(http.StatusNotFound))
	rig.gh.compare(api, 0)

	res, err := rig.exec(t, []GateCompare{web, api}, []GatePR{gatePR("acme/web", 7)})

	require.NoError(t, err)
	assert.True(t, res.Stale)
	assert.Equal(t, []GateCompareResult{
		{GitRepoID: web.GitRepoID, BaseGone: true},
		{GitRepoID: api.GitRepoID},
	}, res.Compares)
	assert.Empty(t, res.Merges)
	assert.Empty(t, res.Refused)
	assert.Empty(t, rig.gh.puts())
}

func TestRunGateGitHubSteps_CurrentMergesInOrderPinnedToInspectedHead(t *testing.T) {
	rig := newGateRig(t)
	web := gateCompare("acme/web")
	first, second := gatePR("acme/web", 1), gatePR("acme/api", 2)
	rig.gh.compare(web, 0)
	rig.gh.pr(first, openPR("head-one"))
	rig.gh.pr(second, openPR("head-two"))
	rig.gh.merge(first, mergeOK("merge-one"))
	rig.gh.merge(second, mergeOK("merge-two"))

	res, err := rig.exec(t, []GateCompare{web}, []GatePR{first, second})

	require.NoError(t, err)
	assert.False(t, res.Stale)
	assert.Empty(t, res.Refused)
	assert.Equal(t, []GateCompareResult{{GitRepoID: web.GitRepoID}}, res.Compares)
	require.Len(t, res.Merges, 2)
	for i, want := range []struct {
		pr  GatePR
		sha string
	}{{first, "merge-one"}, {second, "merge-two"}} {
		assert.Equal(t, want.pr.RowID, res.Merges[i].RowID)
		assert.Equal(t, GatePRMerged, res.Merges[i].Kind)
		assert.Equal(t, want.sha, res.Merges[i].MergeSha)
		require.NotNil(t, res.Merges[i].MergedAt)
		assert.WithinDuration(t, time.Now(), *res.Merges[i].MergedAt, time.Minute)
	}
	assert.Equal(t, []string{
		"GET " + comparePath(web),
		"GET " + prPath(first),
		"GET " + prPath(second),
		"PUT " + mergePath(first),
		"PUT " + mergePath(second),
	}, rig.gh.sequence(), "every pull request is inspected before the first merge")
	puts := rig.gh.puts()
	require.Len(t, puts, 2)
	assert.JSONEq(t, `{"merge_method":"squash","sha":"head-one"}`, puts[0].body)
	assert.JSONEq(t, `{"merge_method":"squash","sha":"head-two"}`, puts[1].body)
}

func TestRunGateGitHubSteps_AlreadyMergedPrIsReportedNotMergedAgain(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 1)
	s := openPR("head-one")
	s.State, s.Merged, s.MergedAt = "closed", true, "2026-10-10T12:00:00Z"
	rig.gh.pr(pr, s)

	res, err := rig.exec(t, nil, []GatePR{pr})

	require.NoError(t, err)
	assert.Empty(t, res.Refused)
	require.Len(t, res.Merges, 1)
	assert.Equal(t, GatePRAlreadyMerged, res.Merges[0].Kind)
	assert.Equal(t, pr.RowID, res.Merges[0].RowID)
	assert.Empty(t, res.Merges[0].MergeSha)
	require.NotNil(t, res.Merges[0].MergedAt)
	assert.True(t, res.Merges[0].MergedAt.Equal(time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)))
	assert.Empty(t, rig.gh.puts())
}

func TestRunGateGitHubSteps_RetryAfterPartialMergeMergesOnlyTheRest(t *testing.T) {
	rig := newGateRig(t)
	done, rest := gatePR("acme/web", 1), gatePR("acme/api", 2)
	merged := openPR("head-one")
	merged.State, merged.Merged, merged.MergedAt = "closed", true, "2026-10-10T12:00:00Z"
	rig.gh.pr(done, merged)
	rig.gh.pr(rest, openPR("head-two"))
	rig.gh.merge(rest, mergeOK("merge-two"))

	res, err := rig.exec(t, nil, []GatePR{done, rest})

	require.NoError(t, err)
	require.Len(t, res.Merges, 2)
	assert.Equal(t, GatePRAlreadyMerged, res.Merges[0].Kind)
	assert.Equal(t, done.RowID, res.Merges[0].RowID)
	assert.Equal(t, GatePRMerged, res.Merges[1].Kind)
	assert.Equal(t, rest.RowID, res.Merges[1].RowID)
	assert.Equal(t, []string{"PUT " + mergePath(rest)}, putPaths(rig.gh))
}

func putPaths(g *gateGitHub) []string {
	var out []string
	for _, r := range g.puts() {
		out = append(out, r.method+" "+r.path)
	}
	return out
}

func TestRunGateGitHubSteps_ClosedPrIsSkipped(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 1)
	s := openPR("head-one")
	s.State = "closed"
	rig.gh.pr(pr, s)

	res, err := rig.exec(t, nil, []GatePR{pr})

	require.NoError(t, err)
	assert.Empty(t, res.Refused)
	require.Len(t, res.Merges, 1)
	assert.Equal(t, GatePRSkippedClosed, res.Merges[0].Kind)
	assert.Equal(t, pr.RowID, res.Merges[0].RowID)
	assert.Nil(t, res.Merges[0].MergedAt)
	assert.Empty(t, rig.gh.puts())
}

func TestRunGateGitHubSteps_DraftOrConflictRefusesBeforeMergingAnything(t *testing.T) {
	rig := newGateRig(t)
	ready, draft, conflicted := gatePR("acme/web", 1), gatePR("acme/web", 2), gatePR("acme/api", 3)
	closed := gatePR("acme/api", 4)
	rig.gh.pr(ready, openPR("head-one"))
	d := openPR("head-two")
	d.Draft = true
	rig.gh.pr(draft, d)
	c := openPR("head-three")
	no := false
	c.Mergeable, c.Base = &no, "release"
	rig.gh.pr(conflicted, c)
	cl := openPR("head-four")
	cl.State = "closed"
	rig.gh.pr(closed, cl)

	res, err := rig.exec(t, nil, []GatePR{ready, draft, conflicted, closed})

	require.NoError(t, err)
	assert.Contains(t, res.Refused, "nothing was merged")
	assert.Contains(t, res.Refused, "acme/web#2 is a draft")
	assert.Contains(t, res.Refused, "acme/api#3 has merge conflicts with release")
	assert.NotContains(t, res.Refused, "acme/web#1")
	assert.NotContains(t, res.Refused, "Already merged")
	assert.Equal(t, []GatePRResult{{RowID: closed.RowID, Kind: GatePRSkippedClosed}}, res.Merges)
	assert.Empty(t, rig.gh.puts())
}

func TestRunGateGitHubSteps_RefusalBeforeMergingKeepsWhatInspectionFoundAlreadyDone(t *testing.T) {
	rig := newGateRig(t)
	done, draft, closed := gatePR("acme/web", 1), gatePR("acme/web", 2), gatePR("acme/api", 3)
	m := openPR("head-one")
	m.State, m.Merged, m.MergedAt = "closed", true, "2026-10-10T12:00:00Z"
	rig.gh.pr(done, m)
	d := openPR("head-two")
	d.Draft = true
	rig.gh.pr(draft, d)
	c := openPR("head-three")
	c.State = "closed"
	rig.gh.pr(closed, c)

	res, err := rig.exec(t, nil, []GatePR{done, draft, closed})

	require.NoError(t, err)
	require.Len(t, res.Merges, 2)
	assert.Equal(t, GatePRAlreadyMerged, res.Merges[0].Kind)
	assert.Equal(t, done.RowID, res.Merges[0].RowID)
	require.NotNil(t, res.Merges[0].MergedAt)
	assert.True(t, res.Merges[0].MergedAt.Equal(time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)))
	assert.Equal(t, GatePRSkippedClosed, res.Merges[1].Kind)
	assert.Equal(t, closed.RowID, res.Merges[1].RowID)
	assert.Contains(t, res.Refused, "acme/web#2 is a draft")
	assert.Contains(t, res.Refused, "Already merged: acme/web#1")
	assert.NotContains(t, res.Refused, "nothing was merged", "one pull request is already merged")
	assert.Empty(t, rig.gh.puts())
}

func TestRunGateGitHubSteps_UnknownMergeabilityIsStillMerged(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 1)
	s := openPR("head-one")
	s.Mergeable = nil
	rig.gh.pr(pr, s)
	rig.gh.merge(pr, mergeOK("merge-one"))

	res, err := rig.exec(t, nil, []GatePR{pr})

	require.NoError(t, err)
	assert.Empty(t, res.Refused)
	require.Len(t, res.Merges, 1)
	assert.Equal(t, GatePRMerged, res.Merges[0].Kind)
}

func TestRunGateGitHubSteps_MergeRefusedReturnsRefusedWithScrubbedReason(t *testing.T) {
	rig := newGateRig(t)
	first, second := gatePR("acme/web", 1), gatePR("acme/api", 2)
	rig.gh.pr(first, openPR("head-one"))
	rig.gh.pr(second, openPR("head-two"))
	rig.gh.merge(first, mergeOK("merge-one"))
	rig.gh.merge(second, jsonReply(http.StatusMethodNotAllowed,
		map[string]any{"message": "Pull Request is not mergeable " + gateToken}))

	res, err := rig.exec(t, nil, []GatePR{first, second})

	require.NoError(t, err)
	require.Len(t, res.Merges, 1)
	assert.Equal(t, GatePRMerged, res.Merges[0].Kind)
	assert.Equal(t, first.RowID, res.Merges[0].RowID)
	assert.Contains(t, res.Refused, "Merged: acme/web#1")
	assert.Contains(t, res.Refused, "Could not merge acme/api#2: Pull Request is not mergeable")
	assert.NotContains(t, res.Refused, gateToken)
	assert.Equal(t, []string{"PUT " + mergePath(first), "PUT " + mergePath(second)}, putPaths(rig.gh))
}

func TestRunGateGitHubSteps_MergeConflictStatusMeansTheHeadMoved(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 1)
	rig.gh.pr(pr, openPR("head-one"))
	rig.gh.merge(pr, jsonReply(http.StatusConflict, map[string]any{"message": "Head branch was modified"}))

	res, err := rig.exec(t, nil, []GatePR{pr})

	require.NoError(t, err)
	assert.Empty(t, res.Merges)
	assert.Contains(t, res.Refused, "Merged: none")
	assert.Contains(t, res.Refused, "Could not merge acme/web#1: its head branch changed while merging")
}

func TestRunGateGitHubSteps_MergeForbiddenNamesTheMissingPermission(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 1)
	rig.gh.pr(pr, openPR("head-one"))
	rig.gh.merge(pr, statusReply(http.StatusForbidden))

	res, err := rig.exec(t, nil, []GatePR{pr})

	require.NoError(t, err)
	assert.Contains(t, res.Refused, "the GitHub credential cannot access acme/web")
	assert.Contains(t, res.Refused, "write access to contents and pull requests")
}

func TestRunGateGitHubSteps_MergeErrorThenReReadShowsMergedCountsAsMerged(t *testing.T) {
	rig := newGateRig(t)
	lost, next := gatePR("acme/web", 1), gatePR("acme/api", 2)
	var landed bool
	var mu sync.Mutex
	rig.gh.route(http.MethodGet, prPath(lost), func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		s := openPR("head-one")
		if landed {
			s.State, s.Merged, s.MergedAt = "closed", true, "2026-10-10T12:30:00Z"
		}
		mu.Unlock()
		jsonReply(http.StatusOK, s.wire())(w, r)
	})
	rig.gh.merge(lost, func(w http.ResponseWriter, _ *http.Request) {
		mu.Lock()
		landed = true
		mu.Unlock()
		w.WriteHeader(http.StatusInternalServerError)
	})
	rig.gh.pr(next, openPR("head-two"))
	rig.gh.merge(next, mergeOK("merge-two"))

	res, err := rig.exec(t, nil, []GatePR{lost, next})

	require.NoError(t, err)
	assert.Empty(t, res.Refused)
	require.Len(t, res.Merges, 2)
	assert.Equal(t, GatePRMerged, res.Merges[0].Kind)
	assert.Equal(t, lost.RowID, res.Merges[0].RowID)
	assert.Empty(t, res.Merges[0].MergeSha)
	require.NotNil(t, res.Merges[0].MergedAt)
	assert.True(t, res.Merges[0].MergedAt.Equal(time.Date(2026, 10, 10, 12, 30, 0, 0, time.UTC)))
	assert.Equal(t, GatePRMerged, res.Merges[1].Kind)
	assert.Equal(t, "merge-two", res.Merges[1].MergeSha)
}

func TestRunGateGitHubSteps_TransientGitHubErrorIsReturnedAsErrorForRetry(t *testing.T) {
	cases := []struct {
		name   string
		status int
		wire   func(rig *gateRig, web GateCompare, pr GatePR)
	}{
		{"502 on compare", http.StatusBadGateway, func(rig *gateRig, web GateCompare, pr GatePR) {
			rig.gh.route(http.MethodGet, comparePath(web), statusReply(http.StatusBadGateway))
		}},
		{"429 on compare", http.StatusTooManyRequests, func(rig *gateRig, web GateCompare, pr GatePR) {
			rig.gh.route(http.MethodGet, comparePath(web), statusReply(http.StatusTooManyRequests))
		}},
		{"503 reading a pull request", http.StatusServiceUnavailable, func(rig *gateRig, web GateCompare, pr GatePR) {
			rig.gh.compare(web, 0)
			rig.gh.route(http.MethodGet, prPath(pr), statusReply(http.StatusServiceUnavailable))
		}},
		{"502 merging, still unmerged on re-read", http.StatusBadGateway, func(rig *gateRig, web GateCompare, pr GatePR) {
			rig.gh.compare(web, 0)
			rig.gh.pr(pr, openPR("head-one"))
			rig.gh.merge(pr, statusReply(http.StatusBadGateway))
		}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rig := newGateRig(t)
			web, pr := gateCompare("acme/web"), gatePR("acme/web", 1)
			tc.wire(rig, web, pr)

			res, err := rig.exec(t, []GateCompare{web}, []GatePR{pr})

			require.Error(t, err)
			var api *github.APIError
			require.ErrorAs(t, err, &api)
			assert.Equal(t, tc.status, api.Status)
			var app *temporal.ApplicationError
			assert.False(t, errors.As(err, &app) && app.NonRetryable(), "Temporal must be free to retry")
			assert.Empty(t, res.Refused)
		})
	}
}

func TestRunGateGitHubSteps_NetworkErrorIsReturnedAsErrorForRetry(t *testing.T) {
	rig := newGateRig(t)
	web := gateCompare("acme/web")
	rig.gh.srv.Close()

	res, err := rig.exec(t, []GateCompare{web}, nil)

	require.Error(t, err)
	assert.Empty(t, res.Refused)
	assert.NotContains(t, err.Error(), gateToken)
}

func TestRunGateGitHubSteps_CredentialRefusedOnCompareIsRefusedNotAnError(t *testing.T) {
	for _, status := range []int{http.StatusUnauthorized, http.StatusForbidden} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			rig := newGateRig(t)
			web := gateCompare("acme/web")
			rig.gh.route(http.MethodGet, comparePath(web), statusReply(status))

			res, err := rig.exec(t, []GateCompare{web}, []GatePR{gatePR("acme/web", 1)})

			require.NoError(t, err)
			assert.Equal(t, "the GitHub credential cannot access acme/web", res.Refused)
			assert.False(t, res.Stale)
			assert.Empty(t, res.Merges)
			assert.Empty(t, rig.gh.puts())
		})
	}
}

func TestRunGateGitHubSteps_CredentialRefusedOnPrReadRefusesBeforeMerging(t *testing.T) {
	rig := newGateRig(t)
	first, second := gatePR("acme/web", 1), gatePR("acme/api", 2)
	rig.gh.pr(first, openPR("head-one"))
	rig.gh.route(http.MethodGet, prPath(second), statusReply(http.StatusForbidden))

	res, err := rig.exec(t, nil, []GatePR{first, second})

	require.NoError(t, err)
	assert.Contains(t, res.Refused, "the GitHub credential cannot access acme/api")
	assert.Empty(t, res.Merges)
	assert.Empty(t, rig.gh.puts())
}

func TestRunGateGitHubSteps_MissingPullRequestRefusesNamingIt(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 9)
	rig.gh.route(http.MethodGet, prPath(pr), statusReply(http.StatusNotFound))

	res, err := rig.exec(t, nil, []GatePR{pr})

	require.NoError(t, err)
	assert.Contains(t, res.Refused, "acme/web#9 not found")
	assert.Empty(t, rig.gh.puts())
}

func TestRunGateGitHubSteps_StopsBetweenPrsWhenCancelled(t *testing.T) {
	rig := newGateRig(t)
	first, second := gatePR("acme/web", 1), gatePR("acme/api", 2)
	rig.gh.pr(first, openPR("head-one"))
	rig.gh.pr(second, openPR("head-two"))
	rig.gh.merge(first, mergeOK("merge-one"))
	rig.gh.merge(second, mergeOK("merge-two"))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	// The SDK delivers a cancel through the heartbeat, so cancel on the beat after the first merge.
	rig.onBeat = func() {
		if len(rig.gh.puts()) == 1 {
			cancel()
		}
	}

	res, err := rig.acts.RunGateGitHubSteps(ctx, rig.params(nil, []GatePR{first, second}))

	require.Error(t, err)
	assert.ErrorIs(t, err, context.Canceled)
	assert.Equal(t, []string{"PUT " + mergePath(first)}, putPaths(rig.gh), "the second pull request is never merged")
	require.Len(t, res.Merges, 1)
	assert.Equal(t, first.RowID, res.Merges[0].RowID)
}

func TestRunGateGitHubSteps_CancelledMidMergeIsNotReReadOrRefused(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 1)
	rig.gh.pr(pr, openPR("head-one"))
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	rig.gh.merge(pr, func(_ http.ResponseWriter, r *http.Request) {
		cancel()
		<-r.Context().Done()
	})

	res, err := rig.acts.RunGateGitHubSteps(ctx, rig.params(nil, []GatePR{pr}))

	require.Error(t, err)
	assert.ErrorIs(t, err, context.Canceled)
	assert.Empty(t, res.Refused)
	assert.Equal(t, 1, rig.gh.count(http.MethodGet, prPath(pr)), "a cancelled call is not worth a re-read")
}

func TestRunGateGitHubSteps_RunIDMustMatchWorkflow(t *testing.T) {
	rig := newGateRig(t)
	web := gateCompare("acme/web")
	p := rig.params([]GateCompare{web}, nil)
	p.RunID = uuid.New()

	_, err := rig.acts.RunGateGitHubSteps(context.Background(), p)

	require.Error(t, err)
	var app *temporal.ApplicationError
	require.ErrorAs(t, err, &app)
	assert.True(t, app.NonRetryable(), "a mismatch never heals on retry")
	assert.Empty(t, rig.prov.calls, "no credential is fetched for another run")
	assert.Empty(t, rig.gh.sequence())
}

func TestRunGateGitHubSteps_CredentialProviderErrorIsReturned(t *testing.T) {
	rig := newGateRig(t)
	boom := errors.New("control plane unavailable")
	rig.prov.err = boom

	_, err := rig.exec(t, []GateCompare{gateCompare("acme/web")}, nil)

	require.Error(t, err)
	assert.ErrorIs(t, err, boom)
	var app *temporal.ApplicationError
	assert.False(t, errors.As(err, &app) && app.NonRetryable())
	assert.Equal(t, []uuid.UUID{rig.run}, rig.prov.calls)
	assert.Empty(t, rig.gh.sequence())
}

func TestRunGateGitHubSteps_FetchesTheTokenOnce(t *testing.T) {
	rig := newGateRig(t)
	web := gateCompare("acme/web")
	first, second := gatePR("acme/web", 1), gatePR("acme/web", 2)
	rig.gh.compare(web, 0)
	rig.gh.pr(first, openPR("head-one"))
	rig.gh.pr(second, openPR("head-two"))
	rig.gh.merge(first, mergeOK("merge-one"))
	rig.gh.merge(second, mergeOK("merge-two"))

	_, err := rig.exec(t, []GateCompare{web}, []GatePR{first, second})

	require.NoError(t, err)
	assert.Len(t, rig.prov.calls, 1)
}

func TestRunGateGitHubSteps_MergeMethodMustBeOneGitHubKnows(t *testing.T) {
	rig := newGateRig(t)
	p := rig.params(nil, []GatePR{gatePR("acme/web", 1)})
	p.MergeMethod = "fast-forward"

	_, err := rig.acts.RunGateGitHubSteps(context.Background(), p)

	require.Error(t, err)
	var app *temporal.ApplicationError
	require.ErrorAs(t, err, &app)
	assert.True(t, app.NonRetryable())
	assert.Empty(t, rig.gh.sequence())
}

func TestRunGateGitHubSteps_NothingToDoNeedsNoCredential(t *testing.T) {
	rig := newGateRig(t)

	res, err := rig.exec(t, nil, nil)

	require.NoError(t, err)
	assert.False(t, res.Stale)
	assert.NotNil(t, res.Compares)
	assert.NotNil(t, res.Merges)
	assert.Empty(t, rig.prov.calls)
}

func TestRunGateGitHubSteps_ErrorsWhenNoCredentialsOrClientAreConfigured(t *testing.T) {
	run := stubbedRun(t)

	_, err := (&Activities{}).RunGateGitHubSteps(context.Background(), GateGitHubParams{
		RunID:    run,
		Compares: []GateCompare{gateCompare("acme/web")},
	})

	require.Error(t, err)
}

func TestRunGateGitHubSteps_HeartbeatsEachCallWithItsPhaseAndIndex(t *testing.T) {
	rig := newGateRig(t)
	web := gateCompare("acme/web")
	first, second := gatePR("acme/web", 1), gatePR("acme/api", 2)
	rig.gh.compare(web, 0)
	rig.gh.pr(first, openPR("head-one"))
	rig.gh.pr(second, openPR("head-two"))
	rig.gh.merge(first, mergeOK("merge-one"))
	rig.gh.merge(second, mergeOK("merge-two"))

	_, err := rig.exec(t, []GateCompare{web}, []GatePR{first, second})

	require.NoError(t, err)
	assert.Equal(t, []string{"compare:0", "inspect:0", "inspect:1", "merge:0", "merge:1"}, rig.beatNames())
	assert.Equal(t, []GateCompareResult{{GitRepoID: web.GitRepoID}}, rig.beats[3].Compares,
		"the compares that passed ride along once merging has begun")
}

func TestRunGateGitHubSteps_HeartbeatsBeforeTheReReadSoASlowOneCannotTimeOutTheAttempt(t *testing.T) {
	rig := newGateRig(t)
	pr := gatePR("acme/web", 1)
	rig.gh.pr(pr, openPR("head-one"))
	rig.gh.merge(pr, statusReply(http.StatusBadGateway))
	var requestsAtBeat []int
	rig.onBeat = func() { requestsAtBeat = append(requestsAtBeat, len(rig.gh.sequence())) }

	_, err := rig.exec(t, nil, []GatePR{pr})

	require.Error(t, err)
	assert.Equal(t, []string{"inspect:0", "merge:0", "merge:0"}, rig.beatNames())
	assert.Equal(t, []int{0, 1, 2}, requestsAtBeat, "the last beat lands after the merge call and before the re-read")
}

func TestRunGateGitHubSteps_ResumedAttemptSkipsComparesAndKeepsTheMergePhaseInEveryBeat(t *testing.T) {
	rig := newGateRig(t)
	web := gateCompare("acme/web")
	pr := gatePR("acme/web", 1)
	earlier := []GateCompareResult{{GitRepoID: web.GitRepoID}}
	rig.resume = &gateProgress{Phase: gatePhaseMerge, Index: 1, Compares: earlier}
	rig.gh.pr(pr, openPR("head-one"))
	rig.gh.merge(pr, mergeOK("merge-one"))

	res, err := rig.exec(t, []GateCompare{web}, []GatePR{pr})

	require.NoError(t, err)
	assert.False(t, res.Stale)
	assert.Equal(t, earlier, res.Compares)
	assert.Equal(t, []string{"GET " + prPath(pr), "PUT " + mergePath(pr)}, rig.gh.sequence(), "no compare is made")
	assert.Equal(t, []string{"merge:0", "merge:0"}, rig.beatNames(),
		"a second failure before merging must still resume, so the phase never steps back")
}

func TestRunGateGitHubSteps_AttemptThatNeverReachedMergingStillRechecks(t *testing.T) {
	for _, phase := range []string{gatePhaseCompare, gatePhaseInspect} {
		t.Run(phase, func(t *testing.T) {
			rig := newGateRig(t)
			web := gateCompare("acme/web")
			rig.resume = &gateProgress{Phase: phase}
			rig.gh.compare(web, 2)

			res, err := rig.exec(t, []GateCompare{web}, []GatePR{gatePR("acme/web", 1)})

			require.NoError(t, err)
			assert.True(t, res.Stale)
			assert.Equal(t, []string{"GET " + comparePath(web)}, rig.gh.sequence())
		})
	}
}

func TestRunGateGitHubSteps_RetryAfterPartialMergeResumesMergingInsteadOfRechecking(t *testing.T) {
	run := stubbedRun(t)
	gh := newGateGitHub(t)
	acts := &Activities{}
	acts.SetCredentials(&fakeGateProvider{})
	acts.SetGitHub(github.New(gh.srv.URL))
	web := gateCompare("acme/web")
	first, second := gatePR("acme/web", 1), gatePR("acme/web", 2)

	// Once the first pull request lands, main contains it, so a compare now reads as drift.
	var mu sync.Mutex
	firstLanded, secondPuts := false, 0
	gh.route(http.MethodGet, comparePath(web), func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		ahead := 0
		if firstLanded {
			ahead = 1
		}
		mu.Unlock()
		jsonReply(http.StatusOK, map[string]any{"ahead_by": ahead})(w, r)
	})
	gh.route(http.MethodGet, prPath(first), func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		s := openPR("head-one")
		if firstLanded {
			s.State, s.Merged, s.MergedAt = "closed", true, "2026-10-10T12:00:00Z"
		}
		mu.Unlock()
		jsonReply(http.StatusOK, s.wire())(w, r)
	})
	gh.merge(first, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		firstLanded = true
		mu.Unlock()
		mergeOK("merge-one")(w, r)
	})
	gh.pr(second, openPR("head-two"))
	gh.merge(second, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		secondPuts++
		n := secondPuts
		mu.Unlock()
		if n == 1 {
			w.WriteHeader(http.StatusBadGateway)
			return
		}
		mergeOK("merge-two")(w, r)
	})
	params := GateGitHubParams{RunID: run, Compares: []GateCompare{web}, PRs: []GatePR{first, second}, MergeMethod: "squash"}
	var suite testsuite.WorkflowTestSuite

	attempt1 := suite.NewTestActivityEnvironment()
	Register(attempt1, acts)
	var lastBeat converter.EncodedValues
	attempt1.SetOnActivityHeartbeatListener(func(_ *temporalactivity.Info, details converter.EncodedValues) {
		lastBeat = details
	})
	_, err := attempt1.ExecuteActivity("RunGateGitHubSteps", params)
	require.Error(t, err, "the 502 on the second pull request fails the attempt")
	require.NotNil(t, lastBeat, "a failed attempt flushes its last heartbeat for the retry")
	var progress gateProgress
	require.NoError(t, lastBeat.Get(&progress))
	require.Equal(t, gatePhaseMerge, progress.Phase)

	attempt2 := suite.NewTestActivityEnvironment()
	Register(attempt2, acts)
	attempt2.SetHeartbeatDetails(progress)
	val, err := attempt2.ExecuteActivity("RunGateGitHubSteps", params)

	require.NoError(t, err)
	var res GateGitHubResult
	require.NoError(t, val.Get(&res))
	assert.False(t, res.Stale, "the merge the first attempt made is not drift")
	assert.Empty(t, res.Refused)
	assert.Equal(t, []GateCompareResult{{GitRepoID: web.GitRepoID}}, res.Compares)
	require.Len(t, res.Merges, 2)
	assert.Equal(t, GatePRAlreadyMerged, res.Merges[0].Kind)
	assert.Equal(t, first.RowID, res.Merges[0].RowID)
	assert.Equal(t, GatePRMerged, res.Merges[1].Kind)
	assert.Equal(t, second.RowID, res.Merges[1].RowID)
	assert.Equal(t, "merge-two", res.Merges[1].MergeSha)
	assert.Equal(t, 1, gh.count(http.MethodGet, comparePath(web)), "only the first attempt compares")
	assert.Equal(t, 1, gh.count(http.MethodPut, mergePath(first)), "the first pull request is never merged twice")
}

func TestGateTypesCarryTheWireNamesTheOrchestratorCopies(t *testing.T) {
	id := uuid.New()
	at := time.Date(2026, 10, 10, 12, 0, 0, 0, time.UTC)
	keys := func(v any) map[string]any {
		raw, err := json.Marshal(v)
		require.NoError(t, err)
		var out map[string]any
		require.NoError(t, json.Unmarshal(raw, &out))
		return out
	}
	names := func(m map[string]any) []string {
		var out []string
		for k := range m {
			out = append(out, k)
		}
		return out
	}

	assert.ElementsMatch(t, []string{"gitRepoId", "ownerRepo", "branch", "baseSha"},
		names(keys(GateCompare{GitRepoID: id})))
	assert.ElementsMatch(t, []string{"rowId", "ownerRepo", "number", "label", "prUrl"},
		names(keys(GatePR{RowID: id})))
	assert.ElementsMatch(t, []string{"runId", "compares", "prs", "mergeMethod"},
		names(keys(GateGitHubParams{RunID: id})))
	assert.ElementsMatch(t, []string{"gitRepoId", "aheadBy", "baseGone"},
		names(keys(GateCompareResult{GitRepoID: id})))
	assert.ElementsMatch(t, []string{"rowId", "kind", "mergedAt", "mergeSha"},
		names(keys(GatePRResult{RowID: id, MergedAt: &at, MergeSha: "abc"})))
	assert.ElementsMatch(t, []string{"rowId", "kind"},
		names(keys(GatePRResult{RowID: id})), "mergedAt and mergeSha are omitted when there are none")
	assert.ElementsMatch(t, []string{"compares", "stale", "merges", "refused"},
		names(keys(GateGitHubResult{})))
	assert.Equal(t, "MERGED", GatePRMerged)
	assert.Equal(t, "ALREADY_MERGED", GatePRAlreadyMerged)
	assert.Equal(t, "SKIPPED_CLOSED", GatePRSkippedClosed)
}

func TestRunGateGitHubSteps_IsRegisteredUnderItsNameAndSurvivesTheDataConverter(t *testing.T) {
	run := stubbedRun(t)
	gh := newGateGitHub(t)
	acts := &Activities{}
	acts.SetCredentials(&fakeGateProvider{})
	acts.SetGitHub(github.New(gh.srv.URL))
	web := gateCompare("acme/web")
	pr := gatePR("acme/web", 1)
	gh.compare(web, 0)
	gh.pr(pr, openPR("head-one"))
	gh.merge(pr, mergeOK("merge-one"))

	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	Register(env, acts)
	val, err := env.ExecuteActivity("RunGateGitHubSteps",
		GateGitHubParams{RunID: run, Compares: []GateCompare{web}, PRs: []GatePR{pr}, MergeMethod: "merge"})

	require.NoError(t, err)
	var res GateGitHubResult
	require.NoError(t, val.Get(&res))
	require.Len(t, res.Merges, 1)
	assert.Equal(t, GatePRMerged, res.Merges[0].Kind)
	assert.Equal(t, "merge-one", res.Merges[0].MergeSha)
	assert.Equal(t, pr.RowID, res.Merges[0].RowID)
}
