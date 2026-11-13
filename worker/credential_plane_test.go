package worker

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	credprov "github.com/dangtrivan15/choruskube/worker/credential"
)

// stubScopeClient stands in for the Worker's own API-server client, the thing a plane's Provider
// consults for a run's GitHub token scope. It never mints or forwards a token -- it only answers
// what repositories and permissions the run's scope names.
type stubScopeClient struct {
	repos      []string
	perms      map[string]string
	scopeCalls int
}

func (s *stubScopeClient) GitHubTokenScope(context.Context, uuid.UUID) ([]string, map[string]string, error) {
	s.scopeCalls++
	return s.repos, s.perms, nil
}

// stubCredentialPlane builds a CredentialPlane whose Provider holds nothing, for a test that
// cares about plumbing (Source reaching the wire, Run's wiring order) rather than any particular
// credential source's behaviour.
func stubCredentialPlane(source string) CredentialPlane {
	return CredentialPlane{
		Source: source,
		New: func(PlaneDeps) credprov.Provider {
			return &fakeHealthProvider{results: credprov.NotHeldReport(time.Now())}
		},
	}
}

func TestLocalCredentialPlane_DeclaresLocal(t *testing.T) {
	plane := LocalCredentialPlane(t.TempDir(), "", "")

	assert.Equal(t, "local", plane.Source)
}

func TestLocalCredentialPlane_ReadsTheMountedFolder_NeverCallsATokenEndpoint(t *testing.T) {
	for _, n := range []string{"GITHUB_PAT", "GITHUB_APP_ID", "CLAUDE_CODE_OAUTH_TOKEN"} {
		t.Setenv(n, "")
	}
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "github-pat"), []byte("ghp_local\n"), 0o600))
	scope := &stubScopeClient{}

	provider := LocalCredentialPlane(dir, "", "").New(PlaneDeps{API: scope, Credential: func() string { return "" }})
	tok, err := provider.GitHubToken(context.Background(), uuid.New())

	require.NoError(t, err)
	assert.Equal(t, "ghp_local", tok.Value)
	// The scope call here is the run's-first-token reachability check, not a minting input -- a
	// PAT never mints, so the only outbound call this makes is to the Worker's own API server,
	// never a GitHub token-issuing endpoint.
	assert.Equal(t, 1, scope.scopeCalls)
}

func TestLocalCredentialPlane_CallsScopeOnceForAnAppCredential(t *testing.T) {
	clearLocalCredentialEnv(t)
	dir := t.TempDir()
	require.NoError(t, os.MkdirAll(filepath.Join(dir, "github-app"), 0o755))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "github-app", "app-id"), []byte("1"), 0o600))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "github-app", "installation-id"), []byte("2"), 0o600))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "github-app", "private-key.pem"), []byte("not-a-real-key"), 0o600))
	scope := &stubScopeClient{repos: []string{"acme/widgets"}, perms: map[string]string{"contents": "write"}}

	provider := LocalCredentialPlane(dir, "", "").New(PlaneDeps{API: scope, Credential: func() string { return "" }})
	// The mint itself fails against a fake PEM and no real GitHub endpoint; this test only cares
	// that the scope lookup -- the one call this Worker makes to its own API server -- happened.
	_, _ = provider.GitHubToken(context.Background(), uuid.New())

	assert.Equal(t, 1, scope.scopeCalls)
}

func TestLocalPlaneDefaults_FillsBlanks(t *testing.T) {
	dir, gh, anthropicURL := localPlaneDefaults("", "", "")

	assert.Equal(t, "/etc/choruskube/credentials", dir)
	assert.Equal(t, "https://api.github.com", gh)
	assert.Equal(t, "https://api.anthropic.com", anthropicURL)
}

func TestLocalPlaneDefaults_KeepsExplicitValues(t *testing.T) {
	dir, gh, anthropicURL := localPlaneDefaults("/creds", "http://ghe.example.test/api/v3", "http://claude.example.test")

	assert.Equal(t, "/creds", dir)
	assert.Equal(t, "http://ghe.example.test/api/v3", gh)
	assert.Equal(t, "http://claude.example.test", anthropicURL)
}

// A blank credentials folder still names the default folder in the error, so an operator reads
// where to put a credential rather than an empty string.
func TestLocalCredentialPlane_NoClaudeTokenErrorNamesTheDefaultFolder(t *testing.T) {
	clearLocalCredentialEnv(t)
	provider := LocalCredentialPlane("", "", "").New(PlaneDeps{API: &stubScopeClient{}, Credential: func() string { return "" }})

	_, err := provider.AgentCredentials(context.Background(), uuid.New(), credprov.Agent{}, true)

	require.Error(t, err)
	assert.ErrorIs(t, err, credprov.ErrNoClaudeToken)
	assert.Contains(t, err.Error(), "/etc/choruskube/credentials")
}

func validConfig() Config {
	return Config{
		TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb",
		Provider: stubProvider{}, Executor: nopExecutor{},
	}
}

func TestConfigValidate_RejectsAZeroValuePlane(t *testing.T) {
	cfg := validConfig()
	cfg.CredentialPlane = CredentialPlane{}

	err := cfg.Validate()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "CredentialPlane")
}

func TestConfigValidate_RejectsAPlaneWithNoNew(t *testing.T) {
	cfg := validConfig()
	cfg.CredentialPlane = CredentialPlane{Source: "local"}

	err := cfg.Validate()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "CredentialPlane")
}

func TestConfigValidate_RejectsAPlaneWithNoSource(t *testing.T) {
	cfg := validConfig()
	cfg.CredentialPlane = CredentialPlane{New: func(PlaneDeps) credprov.Provider { return &fakeHealthProvider{} }}

	err := cfg.Validate()

	require.Error(t, err)
	assert.Contains(t, err.Error(), "CredentialPlane")
}
