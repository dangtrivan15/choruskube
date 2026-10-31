package worker

import (
	"context"
	"os"
	"path/filepath"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

type stubTokenClient struct {
	token      string
	calls      int
	scopeCalls int
}

func (s *stubTokenClient) GitHubToken(context.Context, uuid.UUID) (string, error) {
	s.calls++
	return s.token, nil
}

func (s *stubTokenClient) GitHubTokenScope(context.Context, uuid.UUID) ([]string, map[string]string, error) {
	s.scopeCalls++
	return nil, nil, nil
}

func validConfig() Config {
	return Config{
		TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb",
		Provider: stubProvider{}, Executor: nopExecutor{},
	}
}

func TestNewCredentialProviderControlPlaneAsksTheAPI(t *testing.T) {
	client := &stubTokenClient{token: "ghs_fromapi"}

	tok, err := newCredentialProvider(Config{CredentialSource: CredentialSourceControlPlane}.withDefaults(), client).
		GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghs_fromapi", tok.Value)
	assert.Equal(t, 1, client.calls)
}

func TestNewCredentialProviderLocalReadsTheMountedFolderAndSkipsTheAPI(t *testing.T) {
	for _, n := range []string{"GITHUB_PAT", "GITHUB_APP_ID", "CLAUDE_CODE_OAUTH_TOKEN"} {
		t.Setenv(n, "")
	}
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "github-pat"), []byte("ghp_local\n"), 0o600))
	client := &stubTokenClient{token: "ghs_fromapi"}
	cfg := Config{CredentialSource: CredentialSourceLocal, CredentialsDir: dir}.withDefaults()

	provider := newCredentialProvider(cfg, client)
	tok, err := provider.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_local", tok.Value)
	assert.Zero(t, client.calls, "local mode must never ask the API server for a GitHub token")
	assert.Zero(t, client.scopeCalls, "a PAT credential must never trigger a scope call")
}

func TestConfigValidateRejectsAnUnknownCredentialSource(t *testing.T) {
	for _, ok := range []string{CredentialSourceControlPlane, CredentialSourceLocal} {
		cfg := validConfig()
		cfg.CredentialSource = ok
		assert.NoError(t, cfg.Validate(), "%q", ok)
	}
	cfg := validConfig()
	cfg.CredentialSource = "vault"
	err := cfg.Validate()
	require.Error(t, err)
	assert.Contains(t, err.Error(), "CredentialSource")
}

// There is no tolerant default for a blank CredentialSource: guessing wrong would send a Worker
// meant to hold its own credentials to the API server for them, or the reverse.
func TestConfigValidateRequiresACredentialSource(t *testing.T) {
	cfg := validConfig()
	cfg.CredentialSource = ""

	err := cfg.Validate()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "CredentialSource")
}

func TestConfigWithDefaults_FillsCredentialSettings(t *testing.T) {
	got := Config{CredentialSource: CredentialSourceLocal}.withDefaults()

	assert.Equal(t, "/etc/choruskube/credentials", got.CredentialsDir)
	assert.Equal(t, "https://api.github.com", got.GitHubAPIURL)
}

func TestConfigWithDefaults_LeavesExplicitCredentialSettingsAlone(t *testing.T) {
	got := Config{CredentialSource: CredentialSourceLocal, CredentialsDir: "/creds", GitHubAPIURL: "http://ghe.example.test/api/v3"}.withDefaults()

	assert.Equal(t, CredentialSourceLocal, got.CredentialSource)
	assert.Equal(t, "/creds", got.CredentialsDir)
	assert.Equal(t, "http://ghe.example.test/api/v3", got.GitHubAPIURL)
}
