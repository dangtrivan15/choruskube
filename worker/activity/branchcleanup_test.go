package activity

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/worker/credential"
	"github.com/dangtrivan15/choruskube/worker/github"
)

// branchCleanupGitHub is a GitHub stand-in that fails the test on any request it has no route
// for, the same shape as gateGitHub, so an unexpected call shows up as a test failure, not a
// silent 404 reinterpreted as an outcome.
type branchCleanupGitHub struct {
	t      *testing.T
	srv    *httptest.Server
	mu     sync.Mutex
	routes map[string]http.HandlerFunc
	seen   []string
}

func newBranchCleanupGitHub(t *testing.T) *branchCleanupGitHub {
	t.Helper()
	g := &branchCleanupGitHub{t: t, routes: map[string]http.HandlerFunc{}}
	g.srv = httptest.NewServer(g)
	t.Cleanup(g.srv.Close)
	return g
}

func (g *branchCleanupGitHub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	g.mu.Lock()
	g.seen = append(g.seen, r.Method+" "+r.URL.Path)
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

func (g *branchCleanupGitHub) route(method, path string, h http.HandlerFunc) {
	g.mu.Lock()
	defer g.mu.Unlock()
	g.routes[method+" "+path] = h
}

func (g *branchCleanupGitHub) count(method, path string) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	n := 0
	for _, s := range g.seen {
		if s == method+" "+path {
			n++
		}
	}
	return n
}

func (g *branchCleanupGitHub) comparePath(ownerRepo, defaultBranch, runBranch string) string {
	return "/repos/" + ownerRepo + "/compare/" + defaultBranch + "..." + runBranch
}

func (g *branchCleanupGitHub) deletePath(ownerRepo, runBranch string) string {
	return "/repos/" + ownerRepo + "/git/refs/heads/" + runBranch
}

func (g *branchCleanupGitHub) compare(ownerRepo, defaultBranch, runBranch string, aheadBy int) {
	g.route(http.MethodGet, g.comparePath(ownerRepo, defaultBranch, runBranch),
		jsonReply(http.StatusOK, map[string]any{"ahead_by": aheadBy}))
}

func (g *branchCleanupGitHub) compareStatus(ownerRepo, defaultBranch, runBranch string, status int) {
	g.route(http.MethodGet, g.comparePath(ownerRepo, defaultBranch, runBranch), statusReply(status))
}

func (g *branchCleanupGitHub) compareMalformed(ownerRepo, defaultBranch, runBranch string, body string) {
	g.route(http.MethodGet, g.comparePath(ownerRepo, defaultBranch, runBranch), func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(body))
	})
}

// compareDropsConnection answers with no HTTP response at all, so the client sees a transport
// error rather than a status.
func (g *branchCleanupGitHub) compareDropsConnection(ownerRepo, defaultBranch, runBranch string) {
	g.route(http.MethodGet, g.comparePath(ownerRepo, defaultBranch, runBranch), func(w http.ResponseWriter, r *http.Request) {
		conn, _, err := http.NewResponseController(w).Hijack()
		if err != nil {
			g.t.Errorf("hijack: %v", err)
			return
		}
		_ = conn.Close()
	})
}

func (g *branchCleanupGitHub) deleteStatus(ownerRepo, runBranch string, status int) {
	g.route(http.MethodDelete, g.deletePath(ownerRepo, runBranch), statusReply(status))
}

type branchCleanupRig struct {
	acts       *Activities
	gh         *branchCleanupGitHub
	prov       *fakeGateProvider
	run        uuid.UUID
	heartbeats int
}

// newBranchCleanupRig stubs the SDK's heartbeat calls, which panic outside a running activity.
// It only counts them: the gate rig's own stub rejects any detail that is not a gateProgress,
// which this activity never sends.
func newBranchCleanupRig(t *testing.T) *branchCleanupRig {
	t.Helper()
	run := stubbedRun(t)
	gh := newBranchCleanupGitHub(t)
	prov := &fakeGateProvider{}
	acts := &Activities{}
	acts.SetGitHub(github.New(gh.srv.URL))
	acts.SetCredentials(prov)
	rig := &branchCleanupRig{acts: acts, gh: gh, prov: prov, run: run}

	original := recordHeartbeat
	recordHeartbeat = func(_ context.Context, _ ...interface{}) { rig.heartbeats++ }
	t.Cleanup(func() { recordHeartbeat = original })
	return rig
}

func (r *branchCleanupRig) branch() string { return runBranchPrefix + r.run.String() }

func (r *branchCleanupRig) repo(ownerRepo, defaultBranch string) BranchCleanupRepo {
	return BranchCleanupRepo{GitRepoID: uuid.New(), OwnerRepo: ownerRepo, DefaultBranch: defaultBranch}
}

func outcomeFor(res BranchCleanupResult, gitRepoID uuid.UUID) string {
	for _, o := range res.Outcomes {
		if o.GitRepoID == gitRepoID {
			return o.Outcome
		}
	}
	return ""
}

func TestCleanupRunBranches_DeletesABranchNotAhead(t *testing.T) {
	r := newBranchCleanupRig(t)
	repo := r.repo("acme/app", "main")
	r.gh.compare(repo.OwnerRepo, repo.DefaultBranch, r.branch(), 0)
	r.gh.deleteStatus(repo.OwnerRepo, r.branch(), http.StatusNoContent)

	res, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

	require.NoError(t, err)
	assert.Equal(t, BranchCleanupDeleted, outcomeFor(res, repo.GitRepoID))
	assert.Equal(t, 1, r.gh.count(http.MethodDelete, r.gh.deletePath(repo.OwnerRepo, r.branch())))
}

func TestCleanupRunBranches_KeepsABranchAhead(t *testing.T) {
	r := newBranchCleanupRig(t)
	repo := r.repo("acme/app", "main")
	r.gh.compare(repo.OwnerRepo, repo.DefaultBranch, r.branch(), 2)

	res, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

	require.NoError(t, err)
	assert.Equal(t, BranchCleanupKeptAhead, outcomeFor(res, repo.GitRepoID))
	assert.Zero(t, r.gh.count(http.MethodDelete, r.gh.deletePath(repo.OwnerRepo, r.branch())), "an ahead branch must never be deleted")
}

func TestCleanupRunBranches_CompareNotFoundIsNotFound(t *testing.T) {
	r := newBranchCleanupRig(t)
	repo := r.repo("acme/app", "main")
	r.gh.compareStatus(repo.OwnerRepo, repo.DefaultBranch, r.branch(), http.StatusNotFound)

	res, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

	require.NoError(t, err)
	assert.Equal(t, BranchCleanupNotFound, outcomeFor(res, repo.GitRepoID))
	assert.Zero(t, r.gh.count(http.MethodDelete, r.gh.deletePath(repo.OwnerRepo, r.branch())))
}

func TestCleanupRunBranches_CompareFaultsAreSkippedAndTheNextRepoStillRuns(t *testing.T) {
	for name, tc := range map[string]struct {
		setup func(g *branchCleanupGitHub, ownerRepo, defaultBranch, branch string)
	}{
		"500":               {func(g *branchCleanupGitHub, o, d, b string) { g.compareStatus(o, d, b, http.StatusInternalServerError) }},
		"403":               {func(g *branchCleanupGitHub, o, d, b string) { g.compareStatus(o, d, b, http.StatusForbidden) }},
		"no ahead_by":       {func(g *branchCleanupGitHub, o, d, b string) { g.compareMalformed(o, d, b, `{"status":"ahead"}`) }},
		"negative ahead_by": {func(g *branchCleanupGitHub, o, d, b string) { g.compareMalformed(o, d, b, `{"ahead_by":-1}`) }},
		"transport error":   {func(g *branchCleanupGitHub, o, d, b string) { g.compareDropsConnection(o, d, b) }},
	} {
		t.Run(name, func(t *testing.T) {
			r := newBranchCleanupRig(t)
			failing := r.repo("acme/app", "main")
			healthy := r.repo("acme/web", "main")
			tc.setup(r.gh, failing.OwnerRepo, failing.DefaultBranch, r.branch())
			r.gh.compare(healthy.OwnerRepo, healthy.DefaultBranch, r.branch(), 0)
			r.gh.deleteStatus(healthy.OwnerRepo, r.branch(), http.StatusNoContent)

			res, err := r.acts.CleanupRunBranches(context.Background(),
				BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{failing, healthy}})

			require.NoError(t, err)
			assert.Equal(t, BranchCleanupSkippedError, outcomeFor(res, failing.GitRepoID))
			assert.Zero(t, r.gh.count(http.MethodDelete, r.gh.deletePath(failing.OwnerRepo, r.branch())))
			assert.Equal(t, BranchCleanupDeleted, outcomeFor(res, healthy.GitRepoID))
		})
	}
}

func TestCleanupRunBranches_DeleteNotFoundIsNotFound(t *testing.T) {
	r := newBranchCleanupRig(t)
	repo := r.repo("acme/app", "main")
	r.gh.compare(repo.OwnerRepo, repo.DefaultBranch, r.branch(), 0)
	r.gh.deleteStatus(repo.OwnerRepo, r.branch(), http.StatusNotFound)

	res, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

	require.NoError(t, err)
	assert.Equal(t, BranchCleanupNotFound, outcomeFor(res, repo.GitRepoID))
}

func TestCleanupRunBranches_DeleteRefusalIsSkipped(t *testing.T) {
	for _, status := range []int{http.StatusUnprocessableEntity, http.StatusInternalServerError} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			r := newBranchCleanupRig(t)
			repo := r.repo("acme/app", "main")
			r.gh.compare(repo.OwnerRepo, repo.DefaultBranch, r.branch(), 0)
			r.gh.deleteStatus(repo.OwnerRepo, r.branch(), status)

			res, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

			require.NoError(t, err)
			assert.Equal(t, BranchCleanupSkippedError, outcomeFor(res, repo.GitRepoID))
		})
	}
}

func TestCleanupRunBranches_RefusesUnusableRepoEntriesWithoutCallingGitHub(t *testing.T) {
	r := newBranchCleanupRig(t)
	blank := r.repo("acme/app", "")
	sameAsRunBranch := r.repo("acme/web", r.branch())
	slugless := r.repo("acme", "main")

	res, err := r.acts.CleanupRunBranches(context.Background(),
		BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{blank, sameAsRunBranch, slugless}})

	require.NoError(t, err)
	assert.Equal(t, BranchCleanupSkippedError, outcomeFor(res, blank.GitRepoID))
	assert.Equal(t, BranchCleanupSkippedError, outcomeFor(res, sameAsRunBranch.GitRepoID))
	assert.Equal(t, BranchCleanupSkippedError, outcomeFor(res, slugless.GitRepoID))
	assert.Empty(t, r.gh.seen, "an unusable repo entry must never reach GitHub")
}

func TestCleanupRunBranches_RefusesAnotherRunsID(t *testing.T) {
	r := newBranchCleanupRig(t)
	repo := r.repo("acme/app", "main")

	_, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: uuid.New(), Repos: []BranchCleanupRepo{repo}})

	require.Error(t, err)
	var appErr *temporal.ApplicationError
	require.ErrorAs(t, err, &appErr)
	assert.Equal(t, "BranchCleanupRunBinding", appErr.Type())
	assert.True(t, appErr.NonRetryable(), "a retry cannot make another run's id this workflow's own")
	assert.Contains(t, err.Error(), "requested by the workflow of run")
	assert.Zero(t, len(r.prov.calls), "a run's workflow must not fetch another run's token")
	assert.Empty(t, r.gh.seen)
}

func TestCleanupRunBranches_EmptyPlanFetchesNoToken(t *testing.T) {
	r := newBranchCleanupRig(t)

	res, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: nil})

	require.NoError(t, err)
	assert.Empty(t, res.Outcomes)
	assert.Zero(t, len(r.prov.calls))
}

func TestCleanupRunBranches_TokenFailureIsRetryableAndCallsNoGitHub(t *testing.T) {
	r := newBranchCleanupRig(t)
	r.prov.err = errors.New("no credential configured")
	repo := r.repo("acme/app", "main")

	_, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

	require.Error(t, err)
	var appErr *temporal.ApplicationError
	if errors.As(err, &appErr) {
		assert.False(t, appErr.NonRetryable(), "a token failure may heal, so Temporal must retry it")
	}
	assert.ErrorIs(t, err, r.prov.err)
	assert.Empty(t, r.gh.seen)
}

// blankTokenProvider stands in for a credential source that answers with no error but no usable
// token either — distinct from fakeGateProvider, which always returns gateToken.
type blankTokenProvider struct{}

func (blankTokenProvider) GitHubToken(context.Context, uuid.UUID) (credential.Token, error) {
	return credential.Token{Value: "  "}, nil
}

func (blankTokenProvider) AgentCredentials(context.Context, uuid.UUID, credential.Agent, bool) (credential.Agent, error) {
	return credential.Agent{}, nil
}

func TestCleanupRunBranches_BlankTokenFails(t *testing.T) {
	r := newBranchCleanupRig(t)
	r.acts.SetCredentials(blankTokenProvider{})
	repo := r.repo("acme/app", "main")

	_, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

	require.Error(t, err)
	assert.Empty(t, r.gh.seen)
}

func TestCleanupRunBranches_HeartbeatsBeforeEachGitHubCall(t *testing.T) {
	r := newBranchCleanupRig(t)
	deleted := r.repo("acme/app", "main")
	ahead := r.repo("acme/web", "main")
	r.gh.compare(deleted.OwnerRepo, deleted.DefaultBranch, r.branch(), 0)
	r.gh.deleteStatus(deleted.OwnerRepo, r.branch(), http.StatusNoContent)
	r.gh.compare(ahead.OwnerRepo, ahead.DefaultBranch, r.branch(), 1)

	_, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{deleted, ahead}})

	require.NoError(t, err)
	// deleted: compare + delete heartbeats; ahead: compare heartbeat only.
	assert.Equal(t, 3, r.heartbeats)
}

func TestCleanupRunBranches_CancelledContextReturnsTheContextError(t *testing.T) {
	r := newBranchCleanupRig(t)
	repo1 := r.repo("acme/app", "main")
	repo2 := r.repo("acme/web", "main")
	ctx, cancel := context.WithCancel(context.Background())
	r.gh.route(http.MethodGet, r.gh.comparePath(repo1.OwnerRepo, repo1.DefaultBranch, r.branch()), func(w http.ResponseWriter, req *http.Request) {
		cancel()
		w.WriteHeader(http.StatusInternalServerError)
	})

	_, err := r.acts.CleanupRunBranches(ctx, BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo1, repo2}})

	require.Error(t, err)
	assert.ErrorIs(t, err, context.Canceled)
	assert.Zero(t, r.gh.count(http.MethodGet, r.gh.comparePath(repo2.OwnerRepo, repo2.DefaultBranch, r.branch())),
		"a cancelled context must stop before the next repo")
}

func TestBranchCleanupTypesCarryTheWireNamesTheOrchestratorCopies(t *testing.T) {
	repo := uuid.MustParse("11111111-1111-1111-1111-111111111111")
	run := uuid.MustParse("22222222-2222-2222-2222-222222222222")

	for name, tc := range map[string]struct {
		value any
		want  string
	}{
		"BranchCleanupRepo": {
			BranchCleanupRepo{GitRepoID: repo, OwnerRepo: "acme/api", DefaultBranch: "main"},
			`{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","defaultBranch":"main"}`,
		},
		"BranchCleanupParams": {
			BranchCleanupParams{RunID: run, Repos: []BranchCleanupRepo{{GitRepoID: repo, OwnerRepo: "acme/api", DefaultBranch: "main"}}},
			`{"runId":"22222222-2222-2222-2222-222222222222",
			  "repos":[{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","defaultBranch":"main"}]}`,
		},
		"BranchCleanupOutcome": {
			BranchCleanupOutcome{GitRepoID: repo, OwnerRepo: "acme/api", Outcome: BranchCleanupDeleted},
			`{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","outcome":"DELETED"}`,
		},
		"BranchCleanupResult": {
			BranchCleanupResult{Outcomes: []BranchCleanupOutcome{{GitRepoID: repo, OwnerRepo: "acme/api", Outcome: BranchCleanupKeptAhead}}},
			`{"outcomes":[{"gitRepoId":"11111111-1111-1111-1111-111111111111","ownerRepo":"acme/api","outcome":"KEPT_AHEAD"}]}`,
		},
	} {
		t.Run(name, func(t *testing.T) {
			got, err := json.Marshal(tc.value)
			require.NoError(t, err)
			assert.JSONEq(t, tc.want, string(got))
		})
	}
}

func TestCleanupRunBranches_IsRegisteredByName(t *testing.T) {
	r := newBranchCleanupRig(t)
	r.gh.compare("acme/app", "main", r.branch(), 0)
	r.gh.deleteStatus("acme/app", r.branch(), http.StatusNoContent)

	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	Register(env, r.acts)

	repo := BranchCleanupRepo{GitRepoID: uuid.New(), OwnerRepo: "acme/app", DefaultBranch: "main"}
	val, err := env.ExecuteActivity("CleanupRunBranches", BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})
	require.NoError(t, err)
	var res BranchCleanupResult
	require.NoError(t, val.Get(&res))
	assert.Equal(t, BranchCleanupDeleted, outcomeFor(res, repo.GitRepoID))
}

// captureLogs points the default slog logger at a buffer for the rest of the test.
func captureLogs(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	original := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	t.Cleanup(func() { slog.SetDefault(original) })
	return &buf
}

func TestCleanupRunBranches_SkipWarningNamesTheCauseButNeverTheTokenOrBody(t *testing.T) {
	r := newBranchCleanupRig(t)
	logs := captureLogs(t)
	repo := r.repo("acme/app", "main")
	r.gh.route(http.MethodGet, r.gh.comparePath(repo.OwnerRepo, repo.DefaultBranch, r.branch()),
		func(w http.ResponseWriter, _ *http.Request) {
			w.WriteHeader(http.StatusBadGateway)
			_, _ = io.WriteString(w, `{"message":"upstream boom","echo":"Bearer `+gateToken+`"}`)
		})

	res, err := r.acts.CleanupRunBranches(context.Background(), BranchCleanupParams{RunID: r.run, Repos: []BranchCleanupRepo{repo}})

	require.NoError(t, err)
	assert.Equal(t, BranchCleanupSkippedError, outcomeFor(res, repo.GitRepoID))
	out := logs.String()
	assert.Contains(t, out, "run branch cleanup skipped a repo")
	assert.Contains(t, out, "repo=acme/app")
	assert.Contains(t, out, "status=502")
	assert.NotContains(t, out, gateToken)
	assert.NotContains(t, out, "upstream boom")
}
