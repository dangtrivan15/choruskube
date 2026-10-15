package activity

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"go.temporal.io/sdk/temporal"
	"go.temporal.io/sdk/testsuite"

	"github.com/dangtrivan15/choruskube/worker/credential"
	"github.com/dangtrivan15/choruskube/worker/github"
)

const issueToken = "ghs_issue_test_token"

type issueProvider struct {
	err   error
	calls []uuid.UUID
}

func (p *issueProvider) GitHubToken(_ context.Context, runID uuid.UUID) (credential.Token, error) {
	p.calls = append(p.calls, runID)
	if p.err != nil {
		return credential.Token{}, p.err
	}
	return credential.Token{Value: issueToken}, nil
}

func (p *issueProvider) AgentCredentials(context.Context, uuid.UUID) (credential.Agent, bool, error) {
	return credential.Agent{}, false, nil
}

// issueStub is a stateful GitHub stand-in for the issue endpoints. It remembers what has been
// created across calls, so a listing made after a create finds it -- the only way a retry can be
// proven to find what an earlier attempt already filed instead of filing it again.
type issueStub struct {
	t   *testing.T
	srv *httptest.Server

	mu        sync.Mutex
	issues    map[string][]github.Issue // repo -> issues a listing returns
	listErr   map[string]int            // repo -> status every GET answers with, instead of the list
	created   map[string]int            // repo -> POST attempts so far, success or not
	createErr map[string]map[int]int    // repo -> 1-based POST attempt -> status override
	lastTitle map[string]string         // repo -> title of the last POST, whatever its outcome
}

func newIssueStub(t *testing.T) *issueStub {
	t.Helper()
	s := &issueStub{
		issues:    map[string][]github.Issue{},
		listErr:   map[string]int{},
		created:   map[string]int{},
		createErr: map[string]map[int]int{},
		lastTitle: map[string]string{},
	}
	s.t = t
	s.srv = httptest.NewServer(http.HandlerFunc(s.handle))
	t.Cleanup(s.srv.Close)
	return s
}

func (s *issueStub) handle(w http.ResponseWriter, r *http.Request) {
	if got := r.Header.Get("Authorization"); got != "Bearer "+issueToken {
		s.t.Errorf("request %s %s carried Authorization %q", r.Method, r.URL.Path, got)
	}
	parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/repos/"), "/issues", 2)
	if len(parts) != 2 {
		s.t.Errorf("unexpected request %s %s", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	repo := parts[0]
	switch r.Method {
	case http.MethodGet:
		s.serveList(w, repo)
	case http.MethodPost:
		s.serveCreate(w, r, repo)
	default:
		s.t.Errorf("unexpected method %s %s", r.Method, r.URL.Path)
		w.WriteHeader(http.StatusInternalServerError)
	}
}

func (s *issueStub) serveList(w http.ResponseWriter, repo string) {
	s.mu.Lock()
	status := s.listErr[repo]
	issues := append([]github.Issue(nil), s.issues[repo]...)
	s.mu.Unlock()
	if status != 0 {
		w.WriteHeader(status)
		return
	}
	type wireIssue struct {
		Number  int    `json:"number"`
		HTMLURL string `json:"html_url"`
		Body    string `json:"body"`
	}
	out := make([]wireIssue, 0, len(issues))
	for _, iss := range issues {
		out = append(out, wireIssue{Number: iss.Number, HTMLURL: iss.HTMLURL, Body: iss.Body})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(out)
}

func (s *issueStub) serveCreate(w http.ResponseWriter, r *http.Request, repo string) {
	var body struct {
		Title string `json:"title"`
		Body  string `json:"body"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	s.mu.Lock()
	s.created[repo]++
	n := s.created[repo]
	status := s.createErr[repo][n]
	s.lastTitle[repo] = body.Title
	s.mu.Unlock()

	if status != 0 {
		w.WriteHeader(status)
		return
	}

	s.mu.Lock()
	number := 1000 + len(s.issues[repo])
	iss := github.Issue{
		Number:  number,
		HTMLURL: fmt.Sprintf("https://github.com/%s/issues/%d", repo, number),
		Body:    body.Body,
	}
	s.issues[repo] = append(s.issues[repo], iss)
	s.mu.Unlock()

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"number": iss.Number, "html_url": iss.HTMLURL})
}

func (s *issueStub) seedIssue(repo string, iss github.Issue) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.issues[repo] = append(s.issues[repo], iss)
}

func (s *issueStub) failList(repo string, status int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.listErr[repo] = status
}

func (s *issueStub) failCreate(repo string, n, status int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.createErr[repo] == nil {
		s.createErr[repo] = map[int]int{}
	}
	s.createErr[repo][n] = status
}

func (s *issueStub) issuesFor(repo string) []github.Issue {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]github.Issue(nil), s.issues[repo]...)
}

func (s *issueStub) lastTitleFor(repo string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.lastTitle[repo]
}

func newIssueActivities(t *testing.T) (*Activities, *issueStub, *issueProvider, uuid.UUID) {
	t.Helper()
	run := stubbedRun(t)
	stub := newIssueStub(t)
	prov := &issueProvider{}
	acts := &Activities{}
	acts.SetCredentials(prov)
	acts.SetGitHub(github.New(stub.srv.URL))
	return acts, stub, prov, run
}

// stubIssueHeartbeats swaps the SDK's RecordHeartbeat seam, which panics outside a running
// activity, for one that just records what was sent.
func stubIssueHeartbeats(t *testing.T) *[]issueHeartbeat {
	t.Helper()
	var beats []issueHeartbeat
	original := recordHeartbeat
	recordHeartbeat = func(_ context.Context, details ...interface{}) {
		if p, ok := details[0].(issueHeartbeat); ok {
			beats = append(beats, p)
		}
	}
	t.Cleanup(func() { recordHeartbeat = original })
	return &beats
}

func TestFileGateIssues_FilesEachMissingIssueWithItsKey(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	a, b := uuid.New(), uuid.New()
	p := GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{
			{TaskID: a, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "Task A", Body: "do A"},
			{TaskID: b, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "Task B", Body: "do B"},
		},
	}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 2)
	assert.Equal(t, a, res.Issues[0].TaskID)
	assert.Positive(t, res.Issues[0].Number)
	assert.NotEmpty(t, res.Issues[0].URL)
	assert.Empty(t, res.Issues[0].Failure)
	assert.Equal(t, b, res.Issues[1].TaskID)
	assert.Positive(t, res.Issues[1].Number)
	created := stub.issuesFor("acme/app")
	require.Len(t, created, 2)
	assert.Contains(t, created[0].Body, issueKey(a))
	assert.Contains(t, created[1].Body, issueKey(b))
}

func TestFileGateIssues_AFoundKeyFilesNothing(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	taskID := uuid.New()
	stub.seedIssue("acme/app", github.Issue{
		Number: 7, HTMLURL: "https://github.com/acme/app/issues/7", Body: "stuff\n\n" + issueKey(taskID),
	})
	p := GateIssueParams{RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "t", Body: "b"}}}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 1)
	assert.Equal(t, 7, res.Issues[0].Number)
	assert.Equal(t, "https://github.com/acme/app/issues/7", res.Issues[0].URL)
	assert.Empty(t, res.Issues[0].Failure)
	assert.Zero(t, stub.created["acme/app"], "nothing already found is created")
}

func TestFileGateIssues_RetryAfterPartialCreateFindsFirstAndCreatesOnlyTheRest(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	a, b := uuid.New(), uuid.New()
	// The 2nd POST this repo ever receives is task B's create in the first attempt.
	stub.failCreate("acme/app", 2, http.StatusBadGateway)
	p := GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{
			{TaskID: a, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "A", Body: "a"},
			{TaskID: b, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "B", Body: "b"},
		},
	}

	_, err := acts.FileGateIssues(context.Background(), p)
	require.Error(t, err, "the 502 creating B fails the attempt")
	assert.Len(t, stub.issuesFor("acme/app"), 1, "only A was created before the failure")

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 2)
	assert.Equal(t, a, res.Issues[0].TaskID)
	assert.Positive(t, res.Issues[0].Number, "A is found by its key, not re-created")
	assert.Equal(t, b, res.Issues[1].TaskID)
	assert.Positive(t, res.Issues[1].Number)
	assert.Len(t, stub.issuesFor("acme/app"), 2, "exactly one issue exists per task, despite the retry")
}

func TestFileGateIssues_LastAttemptReturnsPartialResultsWithANilError(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	original := lastAttempt
	lastAttempt = func(context.Context) bool { return true }
	t.Cleanup(func() { lastAttempt = original })
	a, b := uuid.New(), uuid.New()
	stub.failCreate("acme/app", 2, http.StatusInternalServerError)
	p := GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{
			{TaskID: a, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "A", Body: "a"},
			{TaskID: b, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "B", Body: "b"},
		},
	}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err, "the last attempt reports best-effort results instead of erroring")
	require.Len(t, res.Issues, 2)
	assert.Positive(t, res.Issues[0].Number)
	assert.Equal(t, "GitHub kept failing", res.Issues[1].Failure)
}

func TestFileGateIssues_LastAttemptLookupFailureStillReportsIssuesAlreadyFound(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	original := lastAttempt
	lastAttempt = func(context.Context) bool { return true }
	t.Cleanup(func() { lastAttempt = original })
	a, b := uuid.New(), uuid.New()
	stub.seedIssue("acme/app", github.Issue{
		Number: 7, HTMLURL: "https://github.com/acme/app/issues/7", Body: "a\n\n" + issueKey(a),
	})
	stub.failList("acme/other", http.StatusBadGateway)
	p := GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{
			{TaskID: a, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "A", Body: "a"},
			{TaskID: b, GitRepoID: uuid.New(), OwnerRepo: "acme/other", Title: "B", Body: "b"},
		},
	}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 2)
	assert.Equal(t, 7, res.Issues[0].Number, "an issue an earlier attempt filed is still reported, not marked failed")
	assert.Empty(t, res.Issues[0].Failure)
	assert.Equal(t, "GitHub kept failing", res.Issues[1].Failure)
	assert.Zero(t, stub.created["acme/app"])
}

func TestFileGateIssues_AKeyQuotedInAnotherReposIssueIsNotMatched(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	a, b := uuid.New(), uuid.New()
	stub.seedIssue("acme/other", github.Issue{
		Number: 3, HTMLURL: "https://github.com/acme/other/issues/3", Body: "quoting " + issueKey(a),
	})
	p := GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{
			{TaskID: a, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "A", Body: "a"},
			{TaskID: b, GitRepoID: uuid.New(), OwnerRepo: "acme/other", Title: "B", Body: "b"},
		},
	}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 2)
	assert.Equal(t, a, res.Issues[0].TaskID)
	assert.Contains(t, res.Issues[0].URL, "/acme/app/", "A's issue is filed in its own repo")
	assert.NotEqual(t, 3, res.Issues[0].Number)
	assert.Equal(t, 1, stub.created["acme/app"])
}

func TestFileGateIssues_FailureTextsContainNeitherTheTokenNorAnyURL(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	stub.failList("acme/app", http.StatusGone)
	taskID := uuid.New()
	p := GateIssueParams{RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "t", Body: "b"}}}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 1)
	assert.NotEmpty(t, res.Issues[0].Failure)
	assert.NotContains(t, res.Issues[0].Failure, issueToken)
	assert.NotContains(t, res.Issues[0].Failure, "http://")
	assert.NotContains(t, res.Issues[0].Failure, "https://")
}

func TestFileGateIssues_A410MarksOnlyThatRepo(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	stub.failList("acme/gone", http.StatusGone)
	a, b := uuid.New(), uuid.New()
	p := GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{
			{TaskID: a, GitRepoID: uuid.New(), OwnerRepo: "acme/gone", Title: "A", Body: "a"},
			{TaskID: b, GitRepoID: uuid.New(), OwnerRepo: "acme/ok", Title: "B", Body: "b"},
		},
	}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 2)
	assert.Equal(t, a, res.Issues[0].TaskID)
	assert.Contains(t, res.Issues[0].Failure, "acme/gone")
	assert.Equal(t, b, res.Issues[1].TaskID)
	assert.Empty(t, res.Issues[1].Failure)
	assert.Positive(t, res.Issues[1].Number)
}

func TestFileGateIssues_InvalidRepositoryFailsWithoutAGitHubCall(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	taskID := uuid.New()
	p := GateIssueParams{RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "not-a-slug", Title: "t", Body: "b"}}}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 1)
	assert.Equal(t, "invalid repository", res.Issues[0].Failure)
	assert.Empty(t, stub.created)
}

func TestFileGateIssues_DuplicateTaskIdsKeepTheFirstOccurrence(t *testing.T) {
	acts, _, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	taskID := uuid.New()
	p := GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{
			{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "first", Body: "a"},
			{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "second", Body: "b"},
		},
	}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Len(t, res.Issues, 1, "one result per deduped task")
	assert.Equal(t, taskID, res.Issues[0].TaskID)
}

func TestFileGateIssues_RunIDMismatchIsNonRetryableWithNoTokenFetch(t *testing.T) {
	acts, _, prov, _ := newIssueActivities(t)
	p := GateIssueParams{RunID: uuid.New(), NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: uuid.New(), GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "t", Body: "b"}}}

	_, err := acts.FileGateIssues(context.Background(), p)

	require.Error(t, err)
	var app *temporal.ApplicationError
	require.ErrorAs(t, err, &app)
	assert.True(t, app.NonRetryable())
	assert.Empty(t, prov.calls, "no token is fetched for a run id the workflow does not own")
}

func TestFileGateIssues_ZeroSinceIsNonRetryable(t *testing.T) {
	acts, _, prov, run := newIssueActivities(t)
	p := GateIssueParams{RunID: run, NodeExecutionID: uuid.New(),
		Issues: []GateIssue{{TaskID: uuid.New(), GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "t", Body: "b"}}}

	_, err := acts.FileGateIssues(context.Background(), p)

	require.Error(t, err)
	var app *temporal.ApplicationError
	require.ErrorAs(t, err, &app)
	assert.True(t, app.NonRetryable())
	assert.Contains(t, err.Error(), "GateIssueParams")
	assert.Empty(t, prov.calls)
}

func TestFileGateIssues_EmptyInputMakesNoCalls(t *testing.T) {
	run := stubbedRun(t)
	prov := &issueProvider{}
	acts := &Activities{}
	acts.SetCredentials(prov)

	res, err := acts.FileGateIssues(context.Background(),
		GateIssueParams{RunID: run, NodeExecutionID: uuid.New(), Since: time.Now()})

	require.NoError(t, err)
	assert.NotNil(t, res.Issues)
	assert.Empty(t, res.Issues)
	assert.Empty(t, prov.calls, "no credential is fetched when there is nothing to file")
}

func TestFileGateIssues_70kRuneDescriptionKeepsTheKeyAfterTruncation(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	taskID := uuid.New()
	description := strings.Repeat("x", 70000)
	p := GateIssueParams{RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "t", Body: description}}}

	res, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	require.Empty(t, res.Issues[0].Failure)
	created := stub.issuesFor("acme/app")
	require.Len(t, created, 1)
	assert.True(t, strings.HasSuffix(created[0].Body, issueKey(taskID)), "the key survives the cut")
	assert.LessOrEqual(t, utf8.RuneCountInString(created[0].Body), maxIssueBodyRunes+2+len(issueKey(taskID)))
}

func TestFileGateIssues_300RuneTitleIsCutTo256(t *testing.T) {
	acts, stub, _, run := newIssueActivities(t)
	stubIssueHeartbeats(t)
	taskID := uuid.New()
	p := GateIssueParams{RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: strings.Repeat("y", 300), Body: "b"}}}

	_, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	assert.Equal(t, 256, utf8.RuneCountInString(stub.lastTitleFor("acme/app")))
}

func TestFileGateIssues_HeartbeatsAreRecorded(t *testing.T) {
	acts, _, _, run := newIssueActivities(t)
	beats := stubIssueHeartbeats(t)
	taskID := uuid.New()
	p := GateIssueParams{RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "t", Body: "b"}}}

	_, err := acts.FileGateIssues(context.Background(), p)

	require.NoError(t, err)
	assert.NotEmpty(t, *beats)
}

func TestGateIssueTypesCarryTheWireNamesTheOrchestratorCopies(t *testing.T) {
	id := uuid.New()
	keys := func(v any) []string {
		raw, err := json.Marshal(v)
		require.NoError(t, err)
		var out map[string]any
		require.NoError(t, json.Unmarshal(raw, &out))
		names := make([]string, 0, len(out))
		for k := range out {
			names = append(names, k)
		}
		return names
	}

	assert.ElementsMatch(t, []string{"taskId", "gitRepoId", "ownerRepo", "title", "body"},
		keys(GateIssue{TaskID: id, GitRepoID: id}))
	assert.ElementsMatch(t, []string{"runId", "nodeExecutionId", "since", "issues"},
		keys(GateIssueParams{RunID: id, NodeExecutionID: id}))
	assert.ElementsMatch(t, []string{"taskId", "number", "url", "failure"},
		keys(GateIssueResult{TaskID: id, Number: 1, URL: "https://x", Failure: "f"}))
	assert.ElementsMatch(t, []string{"taskId"}, keys(GateIssueResult{TaskID: id}),
		"number, url and failure are omitted when there are none")
	assert.ElementsMatch(t, []string{"issues"}, keys(GateIssuesResult{Issues: []GateIssueResult{}}))
}

func TestFileGateIssues_IsRegisteredUnderItsNameAndSurvivesTheDataConverter(t *testing.T) {
	run := stubbedRun(t)
	stub := newIssueStub(t)
	acts := &Activities{}
	acts.SetCredentials(&issueProvider{})
	acts.SetGitHub(github.New(stub.srv.URL))
	taskID := uuid.New()

	var suite testsuite.WorkflowTestSuite
	env := suite.NewTestActivityEnvironment()
	Register(env, acts)
	val, err := env.ExecuteActivity("FileGateIssues", GateIssueParams{
		RunID: run, NodeExecutionID: uuid.New(), Since: time.Now(),
		Issues: []GateIssue{{TaskID: taskID, GitRepoID: uuid.New(), OwnerRepo: "acme/app", Title: "t", Body: "b"}},
	})

	require.NoError(t, err)
	var res GateIssuesResult
	require.NoError(t, val.Get(&res))
	require.Len(t, res.Issues, 1)
	assert.Equal(t, taskID, res.Issues[0].TaskID)
	assert.Positive(t, res.Issues[0].Number)
}
