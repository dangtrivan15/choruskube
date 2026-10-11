package credential

import (
	"context"
	"errors"
	"fmt"
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

// fakeMinter stands in for github.Client; ttl 0 yields a response with no expires_at.
type fakeMinter struct {
	mu                sync.Mutex
	calls             int
	ttl               time.Duration
	err               error
	gotAppID          string
	gotInstallationID string
	gotPEM            []byte
	gotNow            time.Time
}

func (m *fakeMinter) InstallationToken(_ context.Context, appID, installationID string, pem []byte, now time.Time) (github.InstallationToken, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.calls++
	m.gotAppID, m.gotInstallationID, m.gotPEM, m.gotNow = appID, installationID, pem, now
	if m.err != nil {
		return github.InstallationToken{}, m.err
	}
	out := github.InstallationToken{Value: fmt.Sprintf("ghs_minted_%d", m.calls)}
	if m.ttl > 0 {
		out.ExpiresAt = now.Add(m.ttl)
	}
	return out, nil
}

func writeApp(t *testing.T, dir, appID, installationID, pem string) {
	t.Helper()
	writeFile(t, filepath.Join(dir, "github-app", "app-id"), appID)
	writeFile(t, filepath.Join(dir, "github-app", "installation-id"), installationID)
	writeFile(t, filepath.Join(dir, "github-app", "private-key.pem"), pem)
}

func TestLocalPATFile(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-pat"), "ghp_filetoken\n")

	p := NewLocal(dir, &fakeMinter{}, newClock().now)
	tok, err := p.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_filetoken", tok.Value, "surrounding whitespace must not reach an Authorization header")
	assert.True(t, tok.ExpiresAt.IsZero(), "a PAT has no expiry the Provider knows of")
}

func TestLocalAppMintsAndCaches(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "123456\n", "987654\n", "-----BEGIN RSA PRIVATE KEY-----\nabc\n-----END RSA PRIVATE KEY-----\n")
	clock := newClock()
	minter := &fakeMinter{ttl: 60 * time.Minute}
	p := NewLocal(dir, minter, clock.now)

	first, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	second, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 1, minter.calls, "two calls inside the token's life must mint once")
	assert.Equal(t, first, second)
	assert.Equal(t, "123456", minter.gotAppID)
	assert.Equal(t, "987654", minter.gotInstallationID)
	assert.Contains(t, string(minter.gotPEM), "BEGIN RSA PRIVATE KEY")
	assert.Equal(t, clock.now(), minter.gotNow)
	assert.Equal(t, clock.now().Add(60*time.Minute), first.ExpiresAt)

	clock.advance(56 * time.Minute) // inside the 5-minute refresh margin of a 60-minute token
	third, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	assert.Equal(t, 2, minter.calls, "a token within 5 minutes of expiry must be re-minted")
	assert.NotEqual(t, first.Value, third.Value)
}

func TestLocalAppTokenStillCachedBeforeTheMargin(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	clock := newClock()
	minter := &fakeMinter{ttl: 60 * time.Minute}
	p := NewLocal(dir, minter, clock.now)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	clock.advance(54 * time.Minute)
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 1, minter.calls)
}

func TestLocalAppTokenWithoutExpiryIsNotCached(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{} // zero ttl: the response carries no expires_at
	p := NewLocal(dir, minter, newClock().now)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 2, minter.calls, "a token of unknown lifetime must never be served from cache")
}

func TestLocalAppRotatedCredentialsInvalidateTheCache(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem-one")
	minter := &fakeMinter{ttl: 60 * time.Minute}
	p := NewLocal(dir, minter, newClock().now)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	writeApp(t, dir, "1", "3", "pem-one") // moved to another installation
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	writeApp(t, dir, "1", "3", "pem-two") // key rotated
	_, err = p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)

	assert.Equal(t, 3, minter.calls, "a token minted for an old installation or key must not outlive it")
}

func TestLocalAppMintFailureIsReturnedAndNotCached(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "SECRET-PEM-BODY")
	boom := errors.New("GitHub API returned HTTP 502")
	minter := &fakeMinter{ttl: time.Hour, err: boom}
	p := NewLocal(dir, minter, newClock().now)

	_, err := p.GitHubToken(context.Background(), uuid.New())
	require.Error(t, err)
	assert.ErrorIs(t, err, boom)
	assert.NotContains(t, err.Error(), "SECRET-PEM-BODY")

	minter.mu.Lock()
	minter.err = nil
	minter.mu.Unlock()
	tok, err := p.GitHubToken(context.Background(), uuid.New())
	require.NoError(t, err)
	assert.NotEmpty(t, tok.Value)
	assert.Equal(t, 2, minter.calls, "a failed mint must not leave a cached result behind")
}

func TestLocalAppConcurrentCallsMintOnce(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeApp(t, dir, "1", "2", "pem")
	minter := &fakeMinter{ttl: time.Hour}
	p := NewLocal(dir, minter, newClock().now)

	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := p.GitHubToken(context.Background(), uuid.New())
			assert.NoError(t, err)
		}()
	}
	wg.Wait()

	assert.Equal(t, 1, minter.calls)
}

func TestLocalEnvFallback(t *testing.T) {
	clearEnv(t)
	t.Setenv("GITHUB_PAT", "ghp_envtoken")

	p := NewLocal(t.TempDir(), &fakeMinter{}, newClock().now) // dir exists, holds nothing
	tok, err := p.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_envtoken", tok.Value)
}

func TestLocalEnvFallbackWithNoDirectoryConfigured(t *testing.T) {
	clearEnv(t)
	t.Setenv("GITHUB_PAT", "ghp_envtoken")

	p := NewLocal("", &fakeMinter{}, newClock().now)
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

	p := NewLocal(t.TempDir(), minter, newClock().now)
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

	tok, err := NewLocal(dir, &fakeMinter{}, newClock().now).GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_filetoken", tok.Value, "the mounted folder is the deliberate setting; env is only a fallback")
}

func TestLocalRereadsRotatedFile(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	path := filepath.Join(dir, "github-pat")
	writeFile(t, path, "ghp_old")
	p := NewLocal(dir, &fakeMinter{}, newClock().now)

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

	tok, err := NewLocal(dir, &fakeMinter{}, newClock().now).GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_envtoken", tok.Value)
}

func TestLocalPartialAppNamesTheMissingFile(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "github-app", "app-id"), "1")
	writeFile(t, filepath.Join(dir, "github-app", "installation-id"), "2")

	_, err := NewLocal(dir, &fakeMinter{}, newClock().now).GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "private-key.pem")
}

func TestLocalNothingConfigured(t *testing.T) {
	clearEnv(t)

	_, err := NewLocal(t.TempDir(), &fakeMinter{}, newClock().now).GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "CREDENTIALS_DIR")
	assert.Contains(t, err.Error(), "GITHUB_PAT")
}

func TestLocalUnreadableFileNamesThePathNotTheContent(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	// A directory where the file should be: ReadFile fails with something other than not-exist.
	require.NoError(t, os.MkdirAll(filepath.Join(dir, "github-pat"), 0o755))

	_, err := NewLocal(dir, &fakeMinter{}, newClock().now).GitHubToken(context.Background(), uuid.New())

	require.Error(t, err)
	assert.Contains(t, err.Error(), "github-pat")
}

func TestLocalAgentCredentials(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "claude-oauth-token"), "sk-ant-oat-file\n")
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test\n")
	writeFile(t, filepath.Join(dir, "registry", "username"), "puller\n")
	writeFile(t, filepath.Join(dir, "registry", "password"), "pull-secret\n")

	agent, ok, err := NewLocal(dir, &fakeMinter{}, newClock().now).AgentCredentials(context.Background(), uuid.New())

	require.NoError(t, err)
	require.True(t, ok)
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

	agent, ok, err := NewLocal(t.TempDir(), &fakeMinter{}, newClock().now).AgentCredentials(context.Background(), uuid.New())

	require.NoError(t, err)
	require.True(t, ok)
	assert.Equal(t, "sk-ant-oat-env", agent.ClaudeOAuthToken)
	require.NotNil(t, agent.Registry)
	assert.Equal(t, "registry.example.test", agent.Registry.Host)
}

func TestLocalAgentCredentialsTokenOnlyLeavesRegistryNil(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "claude-oauth-token"), "sk-ant-oat-file")

	agent, ok, err := NewLocal(dir, &fakeMinter{}, newClock().now).AgentCredentials(context.Background(), uuid.New())

	require.NoError(t, err)
	require.True(t, ok)
	assert.Nil(t, agent.Registry)
}

func TestLocalAgentCredentialsNoneConfiguredSuppliesNothing(t *testing.T) {
	clearEnv(t)

	_, ok, err := NewLocal(t.TempDir(), &fakeMinter{}, newClock().now).AgentCredentials(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.False(t, ok, "with nothing configured the credentials prepare returned must stand")
}

func TestLocalAgentCredentialsPartialRegistryIsAnError(t *testing.T) {
	clearEnv(t)
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "registry", "host"), "registry.example.test")

	_, _, err := NewLocal(dir, &fakeMinter{}, newClock().now).AgentCredentials(context.Background(), uuid.New())

	require.Error(t, err)
	assert.True(t, strings.Contains(err.Error(), "registry/username") && strings.Contains(err.Error(), "registry/password"),
		"the error must name what is missing: %v", err)
}
