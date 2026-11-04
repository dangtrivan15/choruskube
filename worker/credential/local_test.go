package credential

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// envNames is every variable Local may fall back to; a test clears them all so the developer's
// own shell cannot decide the outcome.
var envNames = []string{
	"GITHUB_PAT", "GITHUB_APP_ID", "GITHUB_APP_INSTALLATION_ID", "GITHUB_APP_PRIVATE_KEY_PATH",
	"CLAUDE_CODE_OAUTH_TOKEN", "REGISTRY_HOST", "REGISTRY_USERNAME", "REGISTRY_PASSWORD",
}

func clearEnv(t *testing.T) {
	t.Helper()
	for _, n := range envNames {
		t.Setenv(n, "")
	}
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	require.NoError(t, os.MkdirAll(filepath.Dir(path), 0o755))
	require.NoError(t, os.WriteFile(path, []byte(content), 0o600))
}

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

func newClock() *fakeClock {
	return &fakeClock{t: time.Date(2026, 10, 11, 12, 0, 0, 0, time.UTC)}
}

// defaultInstallationPermissions grants every run permission at "admin" — the highest rank — so
// narrow() never drops one by default; a test that needs a narrower grant sets fakeMinter.installation itself.
func defaultInstallationPermissions() map[string]string {
	perms := make(map[string]string, len(runPermissions))
	for k := range runPermissions {
		perms[k] = "admin"
	}
	return perms
}

// fakeMinter stands in for *github.Client; ttl 0 yields a mint response with no expires_at.
type fakeMinter struct {
	mu sync.Mutex

	installation    github.Installation
	installationSet bool
	installationErr error
	installations   int

	ttl               time.Duration
	err               error
	mints             int
	gotAppID          string
	gotInstallationID string
	gotPEM            []byte
	gotNow            time.Time
	gotReq            github.TokenRequest
	allReqs           []github.TokenRequest
}

func (m *fakeMinter) Installation(_ context.Context, appID, installationID string, pem []byte, now time.Time) (github.Installation, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.installations++
	if m.installationErr != nil {
		return github.Installation{}, m.installationErr
	}
	if m.installationSet {
		return m.installation, nil
	}
	return github.Installation{Account: "acme", Permissions: defaultInstallationPermissions()}, nil
}

func (m *fakeMinter) InstallationToken(_ context.Context, appID, installationID string, pem []byte, now time.Time, req github.TokenRequest) (github.InstallationToken, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.mints++
	m.gotAppID, m.gotInstallationID, m.gotPEM, m.gotNow, m.gotReq = appID, installationID, pem, now, req
	m.allReqs = append(m.allReqs, req)
	if m.err != nil {
		return github.InstallationToken{}, m.err
	}
	out := github.InstallationToken{Value: fmt.Sprintf("ghs_minted_%d", m.mints)}
	if m.ttl > 0 {
		out.ExpiresAt = now.Add(m.ttl)
	}
	return out, nil
}

func (m *fakeMinter) calls() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.mints
}

func (m *fakeMinter) installationCalls() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.installations
}

// fakeScopes stands in for a ScopeClient — the Worker's workload client in production. byRun,
// when set, answers per run; any other run gets repos.
type fakeScopes struct {
	mu    sync.Mutex
	repos []string
	byRun map[uuid.UUID][]string
	perms map[string]string
	err   error
	calls int
}

func (s *fakeScopes) GitHubTokenScope(_ context.Context, runID uuid.UUID) ([]string, map[string]string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	if s.err != nil {
		return nil, nil, s.err
	}
	if repos, ok := s.byRun[runID]; ok {
		return repos, s.perms, nil
	}
	return s.repos, s.perms, nil
}

func (s *fakeScopes) callCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.calls
}

// scopesOf is a fakeScopes answering with repos (owned by "acme" in the default installation) and
// the full run permission list — enough for mint() to succeed end to end in most tests.
func scopesOf(repos ...string) *fakeScopes {
	return &fakeScopes{repos: repos, perms: runPermissions}
}

func writeApp(t *testing.T, dir, appID, installationID, pem string) {
	t.Helper()
	writeFile(t, filepath.Join(dir, "github-app", "app-id"), appID)
	writeFile(t, filepath.Join(dir, "github-app", "installation-id"), installationID)
	writeFile(t, filepath.Join(dir, "github-app", "private-key.pem"), pem)
}

// -----------------------------------------------------------------------------------
// Health-check fakes. Most tests below are not about health checking, so NewLocal is handed a
// default checker (200/no scopes header/success) that a test overrides only when it is
// specifically about a health-check outcome.
// -----------------------------------------------------------------------------------

// fakeGitHubChecker stands in for *github.Client's User and Repository health-check methods.
type fakeGitHubChecker struct {
	mu sync.Mutex

	userStatus        int
	userScopes        string
	userScopesPresent bool
	userErr           error
	userCalls         int

	// repoErr maps an ownerRepo to the error Repository returns for it; absent means success.
	repoErr   map[string]error
	repoCalls []string
}

func (f *fakeGitHubChecker) User(context.Context, string) (github.UserCheck, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.userCalls++
	if f.userErr != nil {
		return github.UserCheck{}, f.userErr
	}
	return github.UserCheck{Status: f.userStatus, Scopes: f.userScopes, ScopesPresent: f.userScopesPresent}, nil
}

func (f *fakeGitHubChecker) Repository(_ context.Context, _ string, ownerRepo string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.repoCalls = append(f.repoCalls, ownerRepo)
	if err, ok := f.repoErr[ownerRepo]; ok {
		return err
	}
	return nil
}

func (f *fakeGitHubChecker) repositoryCallCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.repoCalls)
}

// defaultGHChecker answers every User call as a valid, fine-grained-shaped PAT (200, no scopes
// header) and every Repository call as reachable -- the shape a test not about health checking
// needs so it never has to think about the checker at all.
func defaultGHChecker() *fakeGitHubChecker {
	return &fakeGitHubChecker{userStatus: http.StatusOK}
}

// fakeAnthropicChecker stands in for *anthropic.Client.
type fakeAnthropicChecker struct {
	mu        sync.Mutex
	status    Status
	detail    string
	calls     int
	lastToken string
}

func (f *fakeAnthropicChecker) Check(_ context.Context, token string) (Status, string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	f.lastToken = token
	return f.status, f.detail
}

func defaultAnthropicChecker() *fakeAnthropicChecker {
	return &fakeAnthropicChecker{status: StatusValid}
}

// defaultRegistryChecker answers every call as valid -- a test not about registry health
// checking never has to think about it.
var defaultRegistryChecker RegistryChecker = func(context.Context, string, string, string) (Status, string) {
	return StatusValid, ""
}

func TestLocalPATFile(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken\n")
	scopes := scopesOf("acme/widgets")

	p := NewLocal(dir, &fakeMinter{}, scopes, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	tok, err := p.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_filetoken", tok.Value, "surrounding whitespace must not reach an Authorization header")
	assert.True(t, tok.ExpiresAt.IsZero(), "a PAT has no expiry the Provider knows of")
	// A PAT never mints, so the scope call here is solely the run's-first-token reachability
	// check, not a narrowing input -- it must never fire a second time for a repeat call.
	assert.Equal(t, 1, scopes.callCount(), "the first call checks the run's repositories are reachable")
}

func TestLocalAppMintsAndCaches(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "123456\n", "987654\n", "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----\n")
	clock := newClock()
	minter := &fakeMinter{ttl: 60 * time.Minute}
	scopes := scopesOf("acme/widgets")
	p := NewLocal(dir, minter, scopes, clock.now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	runID := uuid.New()

	first, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	second, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)

	assert.Equal(t, 1, minter.calls(), "two calls inside the token's life must mint once")
	assert.Equal(t, first, second)
	assert.Equal(t, "123456", minter.gotAppID)
	assert.Equal(t, "987654", minter.gotInstallationID)
	assert.Contains(t, string(minter.gotPEM), "BEGIN RSA PRIVATE KEY")
	assert.Equal(t, clock.now(), minter.gotNow)
	assert.Equal(t, clock.now().Add(60*time.Minute), first.ExpiresAt)
	assert.Equal(t, []string{"widgets"}, minter.gotReq.Repositories, "the narrowed bare name must be passed")

	clock.advance(56 * time.Minute) // inside the 5-minute refresh margin of a 60-minute token
	third, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, 2, minter.calls(), "a token within 5 minutes of expiry must be re-minted")
	assert.NotEqual(t, first.Value, third.Value)
}

func TestLocalAppTokenStillCachedBeforeTheMargin(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	clock := newClock()
	minter := &fakeMinter{ttl: 60 * time.Minute}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), clock.now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	runID := uuid.New()

	_, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	clock.advance(54 * time.Minute)
	_, err = p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)

	assert.Equal(t, 1, minter.calls())
}

func TestLocalAppTokenWithoutExpiryIsNotCached(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{} // zero ttl: the response carries no expires_at
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	runID := uuid.New()

	_, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	_, err = p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)

	assert.Equal(t, 2, minter.calls(), "a token of unknown lifetime must never be served from cache")
}

func TestLocalAppRotatedCredentialsInvalidateTheCache(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem-one")
	minter := &fakeMinter{ttl: 60 * time.Minute}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	runID := uuid.New()

	_, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	writeApp(t, dir, "1", "3", "pem-one") // moved to another installation
	_, err = p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	writeApp(t, dir, "1", "3", "pem-two") // key rotated
	_, err = p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)

	assert.Equal(t, 3, minter.calls(), "a token minted for an old installation or key must not outlive it")
}

func TestLocalAppMintFailureIsReturnedAndNotCached(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "SECRET-PEM-BODY")
	boom := errors.New("GitHub API returned HTTP 502")
	minter := &fakeMinter{ttl: time.Hour, err: boom}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	runID := uuid.New()

	_, err := p.GitHubToken(context.Background(), runID)
	require.Error(t, err)
	assert.ErrorIs(t, err, boom)
	assert.NotContains(t, err.Error(), "SECRET-PEM-BODY")

	minter.mu.Lock()
	minter.err = nil
	minter.mu.Unlock()
	tok, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	assert.NotEmpty(t, tok.Value)
	assert.Equal(t, 2, minter.calls(), "a failed mint must not leave a cached result behind")
}

func TestLocalAppConcurrentCallsMintOnce(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	runID := uuid.New()
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := p.GitHubToken(context.Background(), runID)
			assert.NoError(t, err)
		}()
	}
	wg.Wait()

	assert.Equal(t, 1, minter.calls())
}

func TestLocalEnvFallback(t *testing.T) {
	clearEnv(t)
	t.Setenv("GITHUB_PAT", "ghp_envtoken")

	p := NewLocal(t.TempDir(), &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker) // dir exists, holds nothing
	tok, err := p.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_envtoken", tok.Value)
}

func TestLocalEnvFallbackWithNoDirectoryConfigured(t *testing.T) {
	clearEnv(t)
	t.Setenv("GITHUB_PAT", "ghp_envtoken")

	p := NewLocal("", &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	tok, err := p.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_envtoken", tok.Value)
}

func TestLocalEnvFallbackMintsFromAnAppKeyFile(t *testing.T) {
	clearEnv(t)
	keyDir := t.TempDir()
	keyPath := filepath.Join(keyDir, "app.pem")
	writeFile(t, keyPath, "-----BEGIN RSA PRIVATE KEY-----\nxyz\n-----END RSA PRIVATE KEY-----\n")
	t.Setenv("GITHUB_APP_ID", "42")
	t.Setenv("GITHUB_APP_INSTALLATION_ID", "4242")
	t.Setenv("GITHUB_APP_PRIVATE_KEY_PATH", keyPath)
	minter := &fakeMinter{ttl: time.Hour}

	p := NewLocal(t.TempDir(), minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)
	_, err := p.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "42", minter.gotAppID)
	assert.Equal(t, "4242", minter.gotInstallationID)
	assert.Contains(t, string(minter.gotPEM), "BEGIN RSA PRIVATE KEY")
}

func TestLocalMountedFileBeatsEnv(t *testing.T) {
	clearEnv(t)
	t.Setenv("GITHUB_PAT", "ghp_envtoken")
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")

	tok, err := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_filetoken", tok.Value, "the mounted folder is the deliberate setting; env is only a fallback")
}

func TestLocalRereadsRotatedFile(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	path := filepath.Join(dir, "github-pat")
	writeFile(t, path, "ghp_old")
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	first, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	writeFile(t, path, "ghp_new")
	second, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, "ghp_old", first.Value)
	assert.Equal(t, "ghp_new", second.Value, "a rotated mounted secret must take effect without a restart")
}

func TestLocalBlankFileFallsThroughToEnv(t *testing.T) {
	clearEnv(t)
	t.Setenv("GITHUB_PAT", "ghp_envtoken")
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), " \n")

	tok, err := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_envtoken", tok.Value)
}

func TestLocalPartialAppNamesTheMissingFile(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-app", "app-id"), "1")
	writeFile(t, filepath.Join(dir, "github-app", "installation-id"), "2")

	_, err := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "private-key.pem")
}

func TestLocalNothingConfigured(t *testing.T) {
	clearEnv(t)

	_, err := NewLocal(t.TempDir(), &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "CREDENTIALS_DIR")
	assert.Contains(t, err.Error(), "GITHUB_PAT")
}

func TestLocalUnreadableFileNamesThePathNotTheContent(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	// A directory where the file should be: ReadFile fails with something other than not-exist.
	require.NoError(t, os.MkdirAll(filepath.Join(dir, "github-pat"), 0o755))

	_, err := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "github-pat")
}

// -----------------------------------------------------------------------------------
// Scope-driven mint and cache behaviour
// -----------------------------------------------------------------------------------

func TestLocalApp_ScopeFetchedOnEveryAppTokenRequest_NeverForAPat(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	scopes := scopesOf("acme/widgets")
	p := NewLocal(dir, &fakeMinter{ttl: time.Hour}, scopes, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 2, scopes.callCount(), "the scope is fetched on every App token request, cached token or not")
}

func TestLocalApp_TwoRunsWithDifferentScopes_MintTwiceAndEachCachesItsOwn(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	runA, runB := uuid.New(), uuid.New()
	scopes := &fakeScopes{
		byRun: map[uuid.UUID][]string{runA: {"acme/api"}, runB: {"acme/web"}},
		perms: runPermissions,
	}
	// One provider for both runs: interleaved requests must not evict each other's token.
	p := NewLocal(dir, minter, scopes, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	tokA1, err := p.GitHubToken(context.Background(), runA)
	require.NoError(t, err)
	tokB1, err := p.GitHubToken(context.Background(), runB)
	require.NoError(t, err)
	tokA2, err := p.GitHubToken(context.Background(), runA)
	require.NoError(t, err)
	tokB2, err := p.GitHubToken(context.Background(), runB)
	require.NoError(t, err)

	assert.Equal(t, 2, minter.calls(), "each distinct repository set mints once; the repeat is cached")
	assert.Equal(t, tokA1, tokA2)
	assert.Equal(t, tokB1, tokB2)
	assert.NotEqual(t, tokA1.Value, tokB1.Value)
	assert.ElementsMatch(t, [][]string{{"api"}, {"web"}},
		[][]string{minter.allReqs[0].Repositories, minter.allReqs[1].Repositories})
}

func TestLocalApp_SameScopeAcrossRuns_MintsOnce(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 1, minter.calls())
}

func TestLocalApp_ScopeCallError_ReturnsErrorAndMintsNothing(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	scopeErr := errors.New("scope route unreachable")
	p := NewLocal(dir, minter, &fakeScopes{err: scopeErr}, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.ErrorIs(t, err, scopeErr)
	assert.Zero(t, minter.calls())
	assert.Zero(t, minter.installationCalls())
}

func TestLocalApp_NilScopes_IsAnError(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	p := NewLocal(dir, minter, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Zero(t, minter.calls())
}

func TestLocalApp_EmptyScope_IsAnError_ZeroInstallationAndZeroMint(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	p := NewLocal(dir, minter, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Zero(t, minter.installationCalls())
	assert.Zero(t, minter.calls())
}

func TestLocalApp_AllForeignScope_IsErrNoReachableRepository_ZeroMints(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	p := NewLocal(dir, minter, scopesOf("other/tools"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.True(t, errors.Is(err, ErrNoReachableRepository))
	assert.Zero(t, minter.calls())
}

func TestLocalApp_TwoMintsWithinTTL_OneInstallationLookup(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 1, minter.installationCalls())
}

func TestLocalApp_InstallationLookupExpiresAfterItsTTL(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	clock := newClock()
	minter := &fakeMinter{ttl: time.Hour}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), clock.now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	clock.advance(installationTTL + time.Second)
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 2, minter.installationCalls(), "a lookup older than installationTTL must be re-read")
}

func TestLocalApp_422_NamesRequestedReposAndAccount_NextCallRefetchesInstallation(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour, err: &github.APIError{Status: 422}}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "[acme/widgets]")
	assert.Contains(t, err.Error(), `"acme"`)
	var apiErr *github.APIError
	require.True(t, errors.As(err, &apiErr))
	assert.Equal(t, 422, apiErr.Status)

	minter.mu.Lock()
	minter.err = nil
	minter.mu.Unlock()
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 2, minter.installationCalls(), "a 422 must drop the cached installation lookup")
}

func TestLocalAgentCredentials(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "claude-oauth-token"), "sk-ant-oat-file\n")
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test\n")
	writeFile(t, filepath.Join(dir, "registry", "username"), "puller\n")
	writeFile(t, filepath.Join(dir, "registry", "password"), "pull-secret\n")

	agent, err := NewLocal(dir, &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, true)

	require.NoError(t, err)
	assert.Equal(t, "sk-ant-oat-file", agent.ClaudeOAuthToken)
	require.NotNil(t, agent.Registry)
	assert.Equal(t, Registry{Host: "registry.example.test", Username: "puller", Password: "pull-secret"}, *agent.Registry)
}

func TestLocalAgentCredentialsEnvFallback(t *testing.T) {
	clearEnv(t)
	t.Setenv("CLAUDE_CODE_OAUTH_TOKEN", "sk-ant-oat-env")
	t.Setenv("REGISTRY_HOST", "registry.example.test")
	t.Setenv("REGISTRY_USERNAME", "puller")
	t.Setenv("REGISTRY_PASSWORD", "pull-secret")

	agent, err := NewLocal(t.TempDir(), &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, true)

	require.NoError(t, err)
	assert.Equal(t, "sk-ant-oat-env", agent.ClaudeOAuthToken)
	require.NotNil(t, agent.Registry)
	assert.Equal(t, "registry.example.test", agent.Registry.Host)
}

func TestLocalAgentCredentialsTokenOnlyLeavesRegistryNil(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "claude-oauth-token"), "sk-ant-oat-file")

	agent, err := NewLocal(dir, &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, true)

	require.NoError(t, err)
	assert.Nil(t, agent.Registry)
}

// This is the done criterion: a local Worker with no Claude token of its own must fail a
// Claude-running node, non-retryably, naming where the token belongs — never fall back to
// whatever prepare attached.
func TestLocalAgentCredentialsNoClaudeTokenForAClaudeNodeIsAnError(t *testing.T) {
	clearEnv(t)

	_, err := NewLocal(t.TempDir(), &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, true)

	require.Error(t, err)
	assert.True(t, errors.Is(err, ErrNoClaudeToken))
	assert.True(t, strings.Contains(err.Error(), "CREDENTIALS_DIR") && strings.Contains(err.Error(), "CLAUDE_CODE_OAUTH_TOKEN"),
		"the error must name both places the token could come from: %v", err)
}

func TestLocalAgentCredentialsIgnoresWhatPrepareAttached(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "claude-oauth-token"), "sk-ant-own")

	attached := Agent{
		ClaudeOAuthToken: "sk-ant-attached",
		Registry:         &Registry{Host: "attached.example.test", Username: "a", Password: "b"},
	}
	agent, err := NewLocal(dir, &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), attached, true)

	require.NoError(t, err)
	assert.Equal(t, "sk-ant-own", agent.ClaudeOAuthToken)
	assert.Nil(t, agent.Registry)
}

func TestLocalAgentCredentialsRegistryOnlyStillNeedsAClaudeToken(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test")
	writeFile(t, filepath.Join(dir, "registry", "username"), "puller")
	writeFile(t, filepath.Join(dir, "registry", "password"), "pull-secret")

	_, err := NewLocal(dir, &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, true)

	require.Error(t, err)
	assert.True(t, errors.Is(err, ErrNoClaudeToken))
}

func TestLocalAgentCredentialsScriptNodeGetsNoClaudeToken(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "claude-oauth-token"), "sk-ant-own")
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test")
	writeFile(t, filepath.Join(dir, "registry", "username"), "puller")
	writeFile(t, filepath.Join(dir, "registry", "password"), "pull-secret")

	agent, err := NewLocal(dir, &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, false)

	require.NoError(t, err)
	assert.Empty(t, agent.ClaudeOAuthToken)
	require.NotNil(t, agent.Registry)
	assert.Equal(t, "registry.example.test", agent.Registry.Host)
}

func TestLocalAgentCredentialsScriptNodeNeedsNoToken(t *testing.T) {
	clearEnv(t)

	agent, err := NewLocal(t.TempDir(), &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, false)

	require.NoError(t, err)
	assert.Empty(t, agent.ClaudeOAuthToken)
	assert.Nil(t, agent.Registry)
}

func TestLocalAgentCredentialsPartialRegistryIsAnError(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test")

	_, err := NewLocal(dir, &fakeMinter{}, nil, newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).AgentCredentials(context.Background(), uuid.New(), Agent{}, true)

	require.Error(t, err)
	assert.True(t, strings.Contains(err.Error(), "registry/username") && strings.Contains(err.Error(), "registry/password"),
		"the error must name what is missing: %v", err)
}

// -----------------------------------------------------------------------------------
// CheckHealth: GitHub App
// -----------------------------------------------------------------------------------

func githubResult(t *testing.T, results []HealthResult) HealthResult {
	t.Helper()
	for _, r := range results {
		if r.Kind == KindGitHub {
			return r
		}
	}
	t.Fatal("no github result in CheckHealth's report")
	return HealthResult{}
}

func TestCheckHealthApp_Valid(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{installationSet: true, installation: github.Installation{Account: "acme"}}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusValid, got.Status)
	assert.Zero(t, minter.calls(), "a health check must never mint a token")
	assert.Equal(t, 1, minter.installationCalls())
}

func TestCheckHealthApp_SuspendedIsExpired(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{installationSet: true, installation: github.Installation{Account: "acme", Suspended: true}}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusExpired, got.Status)
	assert.NotEmpty(t, got.Detail)
	assert.Zero(t, minter.calls())
}

func TestCheckHealthApp_401And404AreExpired(t *testing.T) {
	for _, status := range []int{401, 404} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			clearEnv(t)
			dir := t.TempDir()
			writeApp(t, dir, "1", "2", "pem")
			minter := &fakeMinter{installationErr: &github.APIError{Status: status}}
			p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

			got := githubResult(t, p.CheckHealth(context.Background()))

			assert.Equal(t, StatusExpired, got.Status)
			assert.Zero(t, minter.calls())
		})
	}
}

func TestCheckHealthApp_403IsInsufficientPermissions(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{installationErr: &github.APIError{Status: 403}}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusInsufficientPermissions, got.Status)
}

func TestCheckHealthApp_NetworkErrorOrTimeoutIsUnreachable(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{installationErr: errors.New("dial tcp: i/o timeout")}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusUnreachable, got.Status)
}

func TestCheckHealthApp_5xxIsUnreachable(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{installationErr: &github.APIError{Status: 500}}
	p := NewLocal(dir, minter, scopesOf("acme/widgets"), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusUnreachable, got.Status)
}

// -----------------------------------------------------------------------------------
// CheckHealth: PAT
// -----------------------------------------------------------------------------------

func TestCheckHealthPAT_ValidWithNoScopesHeader(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusOK}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusValid, got.Status)
}

func TestCheckHealthPAT_ValidWithRepoScope(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusOK, userScopes: "repo, read:org", userScopesPresent: true}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusValid, got.Status)
}

func TestCheckHealthPAT_401IsExpired(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusUnauthorized}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusExpired, got.Status)
}

func TestCheckHealthPAT_403IsInsufficientPermissions(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusForbidden}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusInsufficientPermissions, got.Status)
}

func TestCheckHealthPAT_MissingRepoScopeIsInsufficientPermissions(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusOK, userScopes: "read:org, gist", userScopesPresent: true}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusInsufficientPermissions, got.Status)
}

func TestCheckHealthPAT_5xxIsUnreachable(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusInternalServerError}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusUnreachable, got.Status)
}

func TestCheckHealthPAT_NetworkErrorIsUnreachable(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userErr: errors.New("network error")}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	got := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusUnreachable, got.Status)
}

func TestCheckHealthGitHub_NotHeldWhenNothingConfigured(t *testing.T) {
	clearEnv(t)

	got := githubResult(t, NewLocal(t.TempDir(), &fakeMinter{}, scopesOf(), newClock().now,
		defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).CheckHealth(context.Background()))

	assert.Equal(t, StatusNotHeld, got.Status)
}

// -----------------------------------------------------------------------------------
// CheckHealth: Claude
// -----------------------------------------------------------------------------------

func claudeResult(t *testing.T, results []HealthResult) HealthResult {
	t.Helper()
	for _, r := range results {
		if r.Kind == KindClaude {
			return r
		}
	}
	t.Fatal("no claude result in CheckHealth's report")
	return HealthResult{}
}

func TestCheckHealthClaude_NotHeldWhenAbsent(t *testing.T) {
	clearEnv(t)

	got := claudeResult(t, NewLocal(t.TempDir(), &fakeMinter{}, scopesOf(), newClock().now,
		defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).CheckHealth(context.Background()))

	assert.Equal(t, StatusNotHeld, got.Status)
}

func TestCheckHealthClaude_FollowsTheCheckersOutcome(t *testing.T) {
	for _, status := range []Status{StatusValid, StatusExpired, StatusInsufficientPermissions, StatusUnreachable} {
		t.Run(string(status), func(t *testing.T) {
			clearEnv(t)
			dir := t.TempDir()
			writeFile(t, filepath.Join(dir, "claude-oauth-token"), "sk-ant-oat-file")
			anthropicChecker := &fakeAnthropicChecker{status: status, detail: "detail"}
			p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), anthropicChecker, defaultRegistryChecker)

			got := claudeResult(t, p.CheckHealth(context.Background()))

			assert.Equal(t, status, got.Status)
			assert.Equal(t, 1, anthropicChecker.calls)
			assert.Equal(t, "sk-ant-oat-file", anthropicChecker.lastToken)
		})
	}
}

// -----------------------------------------------------------------------------------
// CheckHealth: registry
// -----------------------------------------------------------------------------------

func registryResult(t *testing.T, results []HealthResult) HealthResult {
	t.Helper()
	for _, r := range results {
		if r.Kind == KindRegistry {
			return r
		}
	}
	t.Fatal("no registry result in CheckHealth's report")
	return HealthResult{}
}

func TestCheckHealthRegistry_NotHeldWhenAbsent(t *testing.T) {
	clearEnv(t)

	got := registryResult(t, NewLocal(t.TempDir(), &fakeMinter{}, scopesOf(), newClock().now,
		defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).CheckHealth(context.Background()))

	assert.Equal(t, StatusNotHeld, got.Status)
}

func TestCheckHealthRegistry_IncompleteCredentialIsExpiredNamingWhatIsMissing(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test")

	got := registryResult(t, NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now,
		defaultGHChecker(), defaultAnthropicChecker(), defaultRegistryChecker).CheckHealth(context.Background()))

	assert.Equal(t, StatusExpired, got.Status)
	assert.Contains(t, got.Detail, "registry/username")
}

func TestCheckHealthRegistry_FollowsTheCheckersOutcome(t *testing.T) {
	for _, status := range []Status{StatusValid, StatusExpired, StatusUnreachable} {
		t.Run(string(status), func(t *testing.T) {
			clearEnv(t)
			dir := t.TempDir()
			writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test")
			writeFile(t, filepath.Join(dir, "registry", "username"), "puller")
			writeFile(t, filepath.Join(dir, "registry", "password"), "pull-secret")
			var gotHost, gotUser, gotPass string
			checker := RegistryChecker(func(_ context.Context, host, username, password string) (Status, string) {
				gotHost, gotUser, gotPass = host, username, password
				return status, "detail"
			})
			p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, defaultGHChecker(), defaultAnthropicChecker(), checker)

			got := registryResult(t, p.CheckHealth(context.Background()))

			assert.Equal(t, status, got.Status)
			assert.Equal(t, "registry.example.test", gotHost)
			assert.Equal(t, "puller", gotUser)
			assert.Equal(t, "pull-secret", gotPass)
		})
	}
}

// -----------------------------------------------------------------------------------
// CheckHealth: rotation
// -----------------------------------------------------------------------------------

func TestCheckHealthPAT_RotatedCredentialIsPickedUpByTheNextCall(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	path := filepath.Join(dir, "github-pat")
	writeFile(t, path, "ghp_old")
	gh := &fakeGitHubChecker{userStatus: http.StatusUnauthorized}
	p := NewLocal(dir, &fakeMinter{}, scopesOf(), newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	first := githubResult(t, p.CheckHealth(context.Background()))
	require.Equal(t, StatusExpired, first.Status)

	gh.mu.Lock()
	gh.userStatus = http.StatusOK
	gh.mu.Unlock()
	writeFile(t, path, "ghp_new")
	second := githubResult(t, p.CheckHealth(context.Background()))

	assert.Equal(t, StatusValid, second.Status)
}

// -----------------------------------------------------------------------------------
// PAT reachability on a run's first token
// -----------------------------------------------------------------------------------

func TestLocalPAT_FirstTokenFailsNamingTheUnreachableRepository(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusOK, repoErr: map[string]error{
		"acme/missing": &github.APIError{Status: 404, OwnerRepo: "acme/missing"},
	}}
	scopes := scopesOf("acme/widgets", "acme/missing")
	p := NewLocal(dir, &fakeMinter{}, scopes, newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "acme/missing")
}

func TestLocalPAT_SecondCallForTheSameRunAfterSuccessMakesNoRepositoryRead(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := defaultGHChecker()
	scopes := scopesOf("acme/widgets")
	p := NewLocal(dir, &fakeMinter{}, scopes, newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)
	runID := uuid.New()

	_, err := p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	require.Equal(t, 1, gh.repositoryCallCount())
	require.Equal(t, 1, scopes.callCount())

	_, err = p.GitHubToken(context.Background(), runID)
	require.NoError(t, err)

	assert.Equal(t, 1, gh.repositoryCallCount(), "a run already proven reachable must not be re-read")
	assert.Equal(t, 1, scopes.callCount(), "a verified run must not re-fetch its scope either")
}

func TestLocalPAT_AFailureIsNotCached(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := &fakeGitHubChecker{userStatus: http.StatusOK, repoErr: map[string]error{
		"acme/widgets": errors.New("unreachable"),
	}}
	scopes := scopesOf("acme/widgets")
	p := NewLocal(dir, &fakeMinter{}, scopes, newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)
	runID := uuid.New()

	_, err := p.GitHubToken(context.Background(), runID)
	require.Error(t, err)

	gh.mu.Lock()
	gh.repoErr = nil
	gh.mu.Unlock()
	_, err = p.GitHubToken(context.Background(), runID)

	require.NoError(t, err, "a run that previously failed must be re-checked, not permanently refused")
	assert.Equal(t, 2, gh.repositoryCallCount())
}

func TestLocalPAT_DifferentRunsEachVerifyIndependently(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken")
	gh := defaultGHChecker()
	scopes := scopesOf("acme/widgets")
	p := NewLocal(dir, &fakeMinter{}, scopes, newClock().now, gh, defaultAnthropicChecker(), defaultRegistryChecker)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 2, gh.repositoryCallCount(), "each run checks its own reachability")
	assert.Equal(t, 2, scopes.callCount())
}

// -----------------------------------------------------------------------------------
// No leaks: a health result, and GitHubToken's own errors, must never contain a credential.
// -----------------------------------------------------------------------------------

func TestCheckHealthAndGitHubTokenNeverLeakACredential(t *testing.T) {
	const pat = "ghp_SUPER_SECRET_PAT_VALUE"
	const claude = "sk-ant-oat-SUPER-SECRET-CLAUDE-VALUE"
	const registryPassword = "SUPER-SECRET-REGISTRY-PASSWORD"
	secrets := []string{pat, claude, registryPassword}

	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), pat)
	writeFile(t, filepath.Join(dir, "claude-oauth-token"), claude)
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test")
	writeFile(t, filepath.Join(dir, "registry", "username"), "puller")
	writeFile(t, filepath.Join(dir, "registry", "password"), registryPassword)

	gh := &fakeGitHubChecker{userStatus: http.StatusUnauthorized, repoErr: map[string]error{"acme/widgets": errors.New("unreachable")}}
	anthropicChecker := &fakeAnthropicChecker{status: StatusExpired, detail: "Anthropic API returned HTTP 401"}
	registryChecker := RegistryChecker(func(context.Context, string, string, string) (Status, string) {
		return StatusExpired, "registry rejected the credential (401)"
	})
	scopes := scopesOf("acme/widgets")
	p := NewLocal(dir, &fakeMinter{}, scopes, newClock().now, gh, anthropicChecker, registryChecker)

	results := p.CheckHealth(context.Background())
	for _, r := range results {
		for _, secret := range secrets {
			assert.NotContains(t, r.Detail, secret, "%s detail must never contain a credential", r.Kind)
		}
	}

	_, tokenErr := p.GitHubToken(context.Background(), uuid.New())
	if tokenErr != nil {
		for _, secret := range secrets {
			assert.NotContains(t, tokenErr.Error(), secret, "GitHubToken's error must never contain a credential")
		}
	}
}
