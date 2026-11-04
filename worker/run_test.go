package worker

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/google/uuid"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	credprov "github.com/dangtrivan15/choruskube/worker/credential"
)

type errProvider struct{ err error }

func (p errProvider) Fleets(context.Context) (Registration, error) { return Registration{}, p.err }

type fixedProvider struct{ reg Registration }

func (p fixedProvider) Fleets(context.Context) (Registration, error) { return p.reg, nil }

func TestResolveAddressPrefersFleetEndpoint(t *testing.T) {
	cfg := Config{TemporalAddress: "temporal:7233"}
	if got := resolveAddress(Fleet{Endpoint: "gw:7233"}, cfg); got != "gw:7233" {
		t.Fatalf("want the Fleet's endpoint, got %q", got)
	}
	if got := resolveAddress(Fleet{}, cfg); got != "temporal:7233" {
		t.Fatalf("want the configured address, got %q", got)
	}
}

func TestRunRejectsInvalidConfig(t *testing.T) {
	if err := Run(context.Background(), Config{}); err == nil {
		t.Fatal("want a validation error, got nil")
	}
}

func TestRunSurfacesProviderFailure(t *testing.T) {
	boom := errors.New("boom")
	err := Run(context.Background(), Config{
		TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb", Provider: errProvider{boom},
		CredentialSource: CredentialSourceControlPlane, Executor: nopExecutor{},
	})
	if !errors.Is(err, boom) {
		t.Fatalf("want the provider's error wrapped, got %v", err)
	}
}

func TestRunRejectsEmptyFleetList(t *testing.T) {
	err := Run(context.Background(), Config{
		TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb", Provider: fixedProvider{},
		CredentialSource: CredentialSourceControlPlane, Executor: nopExecutor{},
	})
	if err == nil {
		t.Fatal("want an error when no Fleet is served, got nil")
	}
}

// TestRunFirstRegistrationCarriesAFullReport_ControlPlane proves Run wires the startup health
// check into a *TokenFleetProvider before its first Fleets call, for a Worker holding no
// credentials of its own. TemporalAddress names a port nothing listens on, so Run fails fast
// once it tries to dial Temporal -- after the registration this test cares about has already
// happened, which is why the returned error is never asserted.
func TestRunFirstRegistrationCarriesAFullReport_ControlPlane(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	cfg := Config{
		TemporalAddress:  "127.0.0.1:1", // connection refused: fails Run fast, after registration
		APIServerURL:     srv.URL,
		CallbackURL:      "http://cb",
		CallbackPort:     19191,
		Provider:         NewTokenFleetProvider(srv.URL, "ckf_secret", nil, "", srv.Client()),
		CredentialSource: CredentialSourceControlPlane,
		Executor:         nopExecutor{},
	}

	_ = Run(context.Background(), cfg)

	require.NotNil(t, gotBody, "Run must have registered before failing to dial Temporal")
	health, ok := gotBody["credentialHealth"].([]any)
	require.True(t, ok, "credentialHealth = %v", gotBody["credentialHealth"])
	require.Len(t, health, 3)
	for _, e := range health {
		entry, ok := e.(map[string]any)
		require.True(t, ok)
		assert.Equal(t, "NOT_HELD", entry["status"], "a control-plane Worker holds nothing")
	}
}

// TestRunFirstRegistrationCarriesAFullReport_Local proves the same wiring for the local source,
// reaching local.CheckHealth rather than controlPlane.CheckHealth. An empty credentials
// directory means every kind is still reported NOT_HELD, but by a different code path -- and,
// critically, with no outbound network call, since nothing is configured to check.
// clearLocalCredentialEnv unsets every variable the local source falls back to, so a credential
// in the developer's own shell can neither change these outcomes nor be sent anywhere.
func clearLocalCredentialEnv(t *testing.T) {
	t.Helper()
	for _, n := range []string{
		"GITHUB_PAT", "GITHUB_APP_ID", "GITHUB_APP_INSTALLATION_ID", "GITHUB_APP_PRIVATE_KEY_PATH",
		"CLAUDE_CODE_OAUTH_TOKEN", "REGISTRY_HOST", "REGISTRY_USERNAME", "REGISTRY_PASSWORD",
	} {
		t.Setenv(n, "")
	}
}

func TestRunFirstRegistrationCarriesAFullReport_Local(t *testing.T) {
	clearLocalCredentialEnv(t)
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	cfg := Config{
		TemporalAddress:  "127.0.0.1:1",
		APIServerURL:     srv.URL,
		CallbackURL:      "http://cb",
		CallbackPort:     19192,
		Provider:         NewTokenFleetProvider(srv.URL, "ckf_secret", nil, CredentialSourceLocal, srv.Client()),
		CredentialSource: CredentialSourceLocal,
		CredentialsDir:   t.TempDir(),
		Executor:         nopExecutor{},
	}

	_ = Run(context.Background(), cfg)

	require.NotNil(t, gotBody)
	health, ok := gotBody["credentialHealth"].([]any)
	require.True(t, ok)
	require.Len(t, health, 3)
	for _, e := range health {
		entry := e.(map[string]any)
		assert.Equal(t, "NOT_HELD", entry["status"])
	}
}

// TestRunStaticPathSendsNoRegistration confirms the unaffected path: a Worker configured with
// coordinates rather than a Fleet token never calls the API server to learn its Fleet, so it
// has nothing to attach a health report to and sends none.
func TestRunStaticPathSendsNoRegistration(t *testing.T) {
	hit := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
	}))
	defer srv.Close()

	cfg := Config{
		TemporalAddress:  "127.0.0.1:1",
		APIServerURL:     srv.URL,
		CallbackURL:      "http://cb",
		CallbackPort:     19193,
		Provider:         NewStaticFleetProvider("ns", "q", "internal-token"),
		CredentialSource: CredentialSourceControlPlane,
		Executor:         nopExecutor{},
	}

	_ = Run(context.Background(), cfg)

	assert.False(t, hit, "the static path must never call the API server to register")
}

// -----------------------------------------------------------------------------------
// lateBoundAPI
// -----------------------------------------------------------------------------------

func TestLateBoundAPIRefusesBeforeBind(t *testing.T) {
	b := &lateBoundAPI{}

	_, err := b.GitHubToken(context.Background(), uuid.New())
	require.Error(t, err)

	_, _, err = b.GitHubTokenScope(context.Background(), uuid.New())
	require.Error(t, err)
}

type fakeLateBoundTarget struct {
	token  string
	repos  []string
	perms  map[string]string
	gotRun uuid.UUID
}

func (f *fakeLateBoundTarget) GitHubToken(_ context.Context, runID uuid.UUID) (string, error) {
	f.gotRun = runID
	return f.token, nil
}

func (f *fakeLateBoundTarget) GitHubTokenScope(_ context.Context, runID uuid.UUID) ([]string, map[string]string, error) {
	f.gotRun = runID
	return f.repos, f.perms, nil
}

func TestLateBoundAPIDelegatesAfterBind(t *testing.T) {
	b := &lateBoundAPI{}
	target := &fakeLateBoundTarget{token: "ghs_x", repos: []string{"acme/widgets"}, perms: map[string]string{"contents": "read"}}
	b.bind(target)
	runID := uuid.New()

	tok, err := b.GitHubToken(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, "ghs_x", tok)
	assert.Equal(t, runID, target.gotRun)

	repos, perms, err := b.GitHubTokenScope(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, []string{"acme/widgets"}, repos)
	assert.Equal(t, map[string]string{"contents": "read"}, perms)
}

// -----------------------------------------------------------------------------------
// newCredentialProvider's CheckHealth wiring
// -----------------------------------------------------------------------------------

func TestNewCredentialProviderControlPlane_CheckHealthReportsAllNotHeld(t *testing.T) {
	provider := newCredentialProvider(Config{CredentialSource: CredentialSourceControlPlane}.withDefaults(), &stubTokenClient{})

	results := provider.CheckHealth(context.Background())

	require.Len(t, results, 3)
	for _, r := range results {
		assert.Equal(t, credprov.StatusNotHeld, r.Status)
	}
}

func TestNewCredentialProviderLocal_CheckHealthWorksWithNothingConfigured(t *testing.T) {
	clearLocalCredentialEnv(t)
	cfg := Config{CredentialSource: CredentialSourceLocal, CredentialsDir: t.TempDir()}.withDefaults()
	provider := newCredentialProvider(cfg, &stubTokenClient{})

	results := provider.CheckHealth(context.Background())

	require.Len(t, results, 3)
	for _, r := range results {
		assert.Equal(t, credprov.StatusNotHeld, r.Status, "%s", r.Kind)
		assert.False(t, r.CheckedAt.IsZero())
	}
}
