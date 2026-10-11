package callback

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/worker/credential"
	"github.com/dangtrivan15/choruskube/worker/executor"
)

type fakeRunBinding struct {
	runs       map[uuid.UUID]uuid.UUID // execution -> run the Worker launched it for
	inRunErr   error
	inRunCalls int
	gotRun     uuid.UUID
	gotExec    uuid.UUID
}

func (f *fakeRunBinding) RunOf(execID uuid.UUID) (uuid.UUID, bool) {
	run, ok := f.runs[execID]
	return run, ok
}

func (f *fakeRunBinding) ExecutionInRun(_ context.Context, runID, execID uuid.UUID) error {
	f.inRunCalls++
	f.gotRun, f.gotExec = runID, execID
	return f.inRunErr
}

type fakeTokenSource struct {
	token  string
	err    error
	calls  int
	gotRun uuid.UUID
}

func (f *fakeTokenSource) GitHubToken(_ context.Context, runID uuid.UUID) (credential.Token, error) {
	f.calls++
	f.gotRun = runID
	return credential.Token{Value: f.token}, f.err
}

func tokenReq(runID, execID, secret string) *http.Request {
	q := url.Values{}
	if runID != "" {
		q.Set("run_id", runID)
	}
	if execID != "" {
		q.Set("execution_id", execID)
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/github-token?"+q.Encode(), nil)
	if secret != "" {
		req.Header.Set("Authorization", "Bearer "+secret)
	}
	return req
}

func serveToken(h http.Handler, req *http.Request) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	return w
}

func TestTokenRoute_validSecret_boundRun_returnsToken(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	runs := &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execID: runID}}
	tokens := &fakeTokenSource{token: "ghs_podtoken"}

	w := serveToken(NewGitHubTokenHandler(cache, nil, runs, tokens), tokenReq(runID.String(), execID.String(), "pod-secret"))

	require.Equal(t, http.StatusOK, w.Code)
	var body map[string]string
	require.NoError(t, json.Unmarshal(w.Body.Bytes(), &body))
	assert.Equal(t, map[string]string{"token": "ghs_podtoken"}, body, "the agent's fetch-github-token script reads .token")
	assert.Contains(t, w.Header().Get("Content-Type"), "application/json")
	assert.Equal(t, "no-store", w.Header().Get("Cache-Control"))
	assert.Equal(t, runID, tokens.gotRun)
	assert.Zero(t, runs.inRunCalls, "a cached binding must not cost an API round trip")
}

func TestTokenRoute_wrongSecret_401(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	runs := &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execID: runID}}
	tokens := &fakeTokenSource{token: "ghs_podtoken"}

	w := serveToken(NewGitHubTokenHandler(cache, nil, runs, tokens), tokenReq(runID.String(), execID.String(), "not-the-secret"))

	assert.Equal(t, http.StatusUnauthorized, w.Code)
	assert.Zero(t, tokens.calls)
	assert.NotContains(t, w.Body.String(), "ghs_podtoken")
}

func TestTokenRoute_missingBearer_401(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	tokens := &fakeTokenSource{token: "ghs_podtoken"}

	w := serveToken(NewGitHubTokenHandler(cache, nil, &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execID: runID}}, tokens),
		tokenReq(runID.String(), execID.String(), ""))

	assert.Equal(t, http.StatusUnauthorized, w.Code)
	assert.Zero(t, tokens.calls)
}

func TestTokenRoute_runNotBoundToExecution_403(t *testing.T) {
	boundRun, otherRun, execID := uuid.New(), uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	runs := &fakeRunBinding{
		runs:     map[uuid.UUID]uuid.UUID{execID: boundRun},
		inRunErr: errors.New("api error 404"),
	}
	tokens := &fakeTokenSource{token: "ghs_podtoken"}

	w := serveToken(NewGitHubTokenHandler(cache, nil, runs, tokens), tokenReq(otherRun.String(), execID.String(), "pod-secret"))

	assert.Equal(t, http.StatusForbidden, w.Code)
	assert.Zero(t, tokens.calls, "a token must never be minted for a run the execution does not belong to")
	assert.Zero(t, runs.inRunCalls, "a known binding is final; the API must not be asked to overrule it")
	assert.NotContains(t, w.Body.String(), "ghs_podtoken")
}

// A pod holding its own valid secret must not be able to name a different run -- that would hand
// it the credential resolved for another run's repository.
func TestTokenRoute_podNamingAnotherRunWithItsOwnSecret_403(t *testing.T) {
	runA, runB := uuid.New(), uuid.New()
	execA, execB := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execA, executor.HashSecret("secret-a"))
	cache.Put(execB, executor.HashSecret("secret-b"))
	runs := &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execA: runA, execB: runB}}
	tokens := &fakeTokenSource{token: "ghs_podtoken"}
	h := NewGitHubTokenHandler(cache, nil, runs, tokens)

	assert.Equal(t, http.StatusForbidden, serveToken(h, tokenReq(runB.String(), execA.String(), "secret-a")).Code)
	assert.Equal(t, http.StatusForbidden, serveToken(h, tokenReq(runA.String(), execB.String(), "secret-b")).Code)
	assert.Zero(t, tokens.calls)

	// Each pod still gets a token for its own run.
	assert.Equal(t, http.StatusOK, serveToken(h, tokenReq(runA.String(), execA.String(), "secret-a")).Code)
	assert.Equal(t, runA, tokens.gotRun)
}

func TestTokenRoute_cacheMiss_fallsBackToAPI(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	runs := &fakeRunBinding{} // restarted Worker: no in-memory binding
	tokens := &fakeTokenSource{token: "ghs_podtoken"}

	w := serveToken(NewGitHubTokenHandler(cache, nil, runs, tokens), tokenReq(runID.String(), execID.String(), "pod-secret"))

	require.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, 1, runs.inRunCalls)
	assert.Equal(t, runID, runs.gotRun)
	assert.Equal(t, execID, runs.gotExec)
	assert.Equal(t, runID, tokens.gotRun)
	assert.Contains(t, w.Body.String(), "ghs_podtoken")
}

func TestTokenRoute_cacheMiss_APIDeniesTheBinding_403(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	runs := &fakeRunBinding{inRunErr: errors.New("api error 404")}
	tokens := &fakeTokenSource{token: "ghs_podtoken"}

	w := serveToken(NewGitHubTokenHandler(cache, nil, runs, tokens), tokenReq(runID.String(), execID.String(), "pod-secret"))

	assert.Equal(t, http.StatusForbidden, w.Code)
	assert.Zero(t, tokens.calls, "an unverifiable binding fails closed")
}

func TestTokenRoute_providerError_503_noLeak(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	runs := &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execID: runID}}
	tokens := &fakeTokenSource{err: errors.New("read /etc/choruskube/credentials/github-pat: permission denied")}

	w := serveToken(NewGitHubTokenHandler(cache, nil, runs, tokens), tokenReq(runID.String(), execID.String(), "pod-secret"))

	assert.Equal(t, http.StatusServiceUnavailable, w.Code)
	assert.JSONEq(t, `{"error":"no usable GitHub credential"}`, w.Body.String())
	assert.NotContains(t, w.Body.String(), "credentials/github-pat", "the Worker's file layout is not the pod's business")
}

func TestTokenRoute_badInput_400(t *testing.T) {
	execID := uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	tokens := &fakeTokenSource{token: "ghs_podtoken"}
	h := NewGitHubTokenHandler(cache, nil, &fakeRunBinding{}, tokens)

	for name, req := range map[string]*http.Request{
		"missing run_id":       tokenReq("", execID.String(), "pod-secret"),
		"missing execution_id": tokenReq(uuid.NewString(), "", "pod-secret"),
		"run_id not a uuid":    tokenReq("nope", execID.String(), "pod-secret"),
		"execution_id not one": tokenReq(uuid.NewString(), "nope", "pod-secret"),
	} {
		assert.Equal(t, http.StatusBadRequest, serveToken(h, req).Code, name)
	}
	assert.Zero(t, tokens.calls)
}

func TestTokenRoute_onlyGET(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	h := NewGitHubTokenHandler(cache, nil, &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execID: runID}}, &fakeTokenSource{token: "t"})

	req := tokenReq(runID.String(), execID.String(), "pod-secret")
	req.Method = http.MethodPost
	assert.Equal(t, http.StatusMethodNotAllowed, serveToken(h, req).Code)
}

// The route authenticates exactly as the completion callback does: a restarted Worker recovers the
// hash through the resolver, handing it the run the request names.
func TestTokenRoute_cacheMissSecret_recoveredThroughResolver(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	var gotRun uuid.UUID
	resolver := &mockExecutor{resolveJobSecretHashFn: func(_ context.Context, rid, id uuid.UUID) (string, error) {
		gotRun = rid
		assert.Equal(t, execID, id)
		return executor.HashSecret("pod-secret"), nil
	}}
	cache := NewHashCache()
	tokens := &fakeTokenSource{token: "ghs_podtoken"}
	h := NewGitHubTokenHandler(cache, resolver, &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execID: runID}}, tokens)

	w := serveToken(h, tokenReq(runID.String(), execID.String(), "pod-secret"))

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, runID, gotRun)
	_, cached := cache.Get(execID)
	assert.True(t, cached)
}

func TestTokenRoute_resolverFailure_401(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	resolver := &mockExecutor{resolveJobSecretHashFn: func(context.Context, uuid.UUID, uuid.UUID) (string, error) {
		return "", errors.New("no job-secret found")
	}}
	tokens := &fakeTokenSource{token: "ghs_podtoken"}
	h := NewGitHubTokenHandler(NewHashCache(), resolver, &fakeRunBinding{}, tokens)

	w := serveToken(h, tokenReq(runID.String(), execID.String(), "pod-secret"))

	assert.Equal(t, http.StatusUnauthorized, w.Code)
	assert.Zero(t, tokens.calls)
}

func startTestServer(t *testing.T, githubToken http.Handler) string {
	t.Helper()
	s := NewServer(0, NewHandler(NewHashCache(), nil, &mockCompleter{}, nil), NewHeartbeatHandler(NewHashCache(), nil, &mockHeartbeater{}), githubToken)
	require.NoError(t, s.Listen())
	go func() { _ = s.Serve() }()
	t.Cleanup(func() { _ = s.Shutdown(context.Background()) })
	return "http://" + s.ln.Addr().String()
}

func TestServer_routesTheGitHubTokenPath(t *testing.T) {
	runID, execID := uuid.New(), uuid.New()
	cache := NewHashCache()
	cache.Put(execID, executor.HashSecret("pod-secret"))
	h := NewGitHubTokenHandler(cache, nil, &fakeRunBinding{runs: map[uuid.UUID]uuid.UUID{execID: runID}}, &fakeTokenSource{token: "ghs_podtoken"})
	base := startTestServer(t, h)

	req, err := http.NewRequest(http.MethodGet, base+"/api/v1/github-token?run_id="+runID.String()+"&execution_id="+execID.String(), nil)
	require.NoError(t, err)
	req.Header.Set("Authorization", "Bearer pod-secret")
	resp, err := http.DefaultClient.Do(req)
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusOK, resp.StatusCode)
}

func TestServer_withoutATokenHandlerDoesNotServeThePath(t *testing.T) {
	base := startTestServer(t, nil)

	resp, err := http.Get(base + "/api/v1/github-token")
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusNotFound, resp.StatusCode)
}
