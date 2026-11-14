package worker

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

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
		CredentialPlane: stubCredentialPlane("stub"), Executor: nopExecutor{},
	})
	if !errors.Is(err, boom) {
		t.Fatalf("want the provider's error wrapped, got %v", err)
	}
}

func TestRunRejectsEmptyFleetList(t *testing.T) {
	err := Run(context.Background(), Config{
		TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb", Provider: fixedProvider{},
		CredentialPlane: stubCredentialPlane("stub"), Executor: nopExecutor{},
	})
	if err == nil {
		t.Fatal("want an error when no Fleet is served, got nil")
	}
}

// TestRunFirstRegistrationCarriesAFullReport_StubPlane proves Run wires the startup health check
// into a *TokenFleetProvider before its first Fleets call, and declares the attached plane's
// Source on that same call. TemporalAddress names a port nothing listens on, so Run fails fast
// once it tries to dial Temporal -- after the registration this test cares about has already
// happened, which is why the returned error is never asserted.
func TestRunFirstRegistrationCarriesAFullReport_StubPlane(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	plane := stubCredentialPlane("stub-source")
	cfg := Config{
		TemporalAddress: "127.0.0.1:1", // connection refused: fails Run fast, after registration
		APIServerURL:    srv.URL,
		CallbackURL:     "http://cb",
		CallbackPort:    19191,
		Provider:        NewTokenFleetProvider(srv.URL, "ckf_secret", nil, srv.Client()),
		CredentialPlane: plane,
		Executor:        nopExecutor{},
	}

	_ = Run(context.Background(), cfg)

	require.NotNil(t, gotBody, "Run must have registered before failing to dial Temporal")
	assert.Equal(t, plane.Source, gotBody["credentialSource"])
	health, ok := gotBody["credentialHealth"].([]any)
	require.True(t, ok, "credentialHealth = %v", gotBody["credentialHealth"])
	require.Len(t, health, 3)
	for _, e := range health {
		entry, ok := e.(map[string]any)
		require.True(t, ok)
		assert.Equal(t, "NOT_HELD", entry["status"], "the stub plane's provider holds nothing")
	}
}

// TestRunFirstRegistrationCarriesAFullReport_Local proves the same wiring for the local plane,
// reaching local.CheckHealth. An empty credentials directory means every kind is still reported
// NOT_HELD, but by a different code path -- and, critically, with no outbound network call, since
// nothing is configured to check. clearLocalCredentialEnv unsets every variable the local plane
// falls back to, so a credential in the developer's own shell can neither change these outcomes
// nor be sent anywhere.
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
		TemporalAddress: "127.0.0.1:1",
		APIServerURL:    srv.URL,
		CallbackURL:     "http://cb",
		CallbackPort:    19192,
		Provider:        NewTokenFleetProvider(srv.URL, "ckf_secret", nil, srv.Client()),
		CredentialPlane: LocalCredentialPlane(t.TempDir(), "", ""),
		Executor:        nopExecutor{},
	}

	_ = Run(context.Background(), cfg)

	require.NotNil(t, gotBody)
	assert.Equal(t, "local", gotBody["credentialSource"])
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
		TemporalAddress: "127.0.0.1:1",
		APIServerURL:    srv.URL,
		CallbackURL:     "http://cb",
		CallbackPort:    19193,
		Provider:        NewStaticFleetProvider("ns", "q", "internal-token"),
		CredentialPlane: stubCredentialPlane("stub"),
		Executor:        nopExecutor{},
	}

	_ = Run(context.Background(), cfg)

	assert.False(t, hit, "the static path must never call the API server to register")
}

// TestRunDialsTheRegisteredEndpointWhenNoTemporalAddressIsConfigured proves a registration
// path with no TemporalAddress still gets past validation and dials the Fleet's own Endpoint.
// The refusal must name 127.0.0.1:1: the SDK's empty-address default fails the same way, but
// against localhost:7233.
func TestRunDialsTheRegisteredEndpointWhenNoTemporalAddressIsConfigured(t *testing.T) {
	err := Run(context.Background(), Config{
		APIServerURL: "http://a",
		CallbackURL:  "http://cb",
		CallbackPort: 19195,
		Provider: fixedProvider{reg: Registration{
			Fleets:        []Fleet{{Namespace: "ns", TaskQueue: "q", Endpoint: "127.0.0.1:1"}},
			InternalToken: "internal-tok",
		}},
		CredentialPlane: stubCredentialPlane("stub"),
		Executor:        nopExecutor{},
	})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "dial temporal for fleet", "want a dial failure, got %v", err)
	assert.Contains(t, err.Error(), "127.0.0.1:1", "want the Fleet's own endpoint dialled, got %v", err)
}

// TestRunFailsBeforeDialingWhenNeitherSourceNamesAnAddress proves the case a registration
// response with a genuinely blank Endpoint creates: no TemporalAddress fallback either means
// Run must fail before ever calling Dial, naming both sources so the operator knows which to set.
func TestRunFailsBeforeDialingWhenNeitherSourceNamesAnAddress(t *testing.T) {
	err := Run(context.Background(), Config{
		APIServerURL: "http://a",
		CallbackURL:  "http://cb",
		CallbackPort: 19196,
		Provider: fixedProvider{reg: Registration{
			Fleets:        []Fleet{{Namespace: "ns", TaskQueue: "q", Endpoint: ""}},
			InternalToken: "internal-tok",
		}},
		CredentialPlane: stubCredentialPlane("stub"),
		Executor:        nopExecutor{},
	})
	require.Error(t, err)
	assert.Contains(t, err.Error(), "registration returned no endpoint")
	assert.Contains(t, err.Error(), "TEMPORAL_ADDRESS")
	assert.NotContains(t, err.Error(), "dial temporal for fleet", "must fail before ever attempting to dial")
}

// TestRunHandsThePlaneALiveCredentialAndALateBoundAPI proves what an embedding binary's plane
// relies on: New runs before registration, so the Credential getter reads "" and a scope lookup
// through API is refused there, then the same getter reads the minted credential and the same
// API presents it, with no rebuild of the Provider.
func TestRunHandsThePlaneALiveCredentialAndALateBoundAPI(t *testing.T) {
	const minted = "ckw_minted"
	scopeAuth := make(chan string, 1)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/worker/register" {
			_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt","internalToken":"` + minted + `"}`))
			return
		}
		scopeAuth <- r.Header.Get("Authorization")
		_, _ = w.Write([]byte(`{"repositories":["acme/widgets"],"permissions":{"contents":"write"}}`))
	}))
	defer srv.Close()

	var deps PlaneDeps
	var credentialAtNew string
	var scopeErrAtNew error
	plane := CredentialPlane{
		Source: "stub",
		New: func(d PlaneDeps) credprov.Provider {
			deps = d
			credentialAtNew = d.Credential()
			_, _, scopeErrAtNew = d.API.GitHubTokenScope(context.Background(), uuid.New())
			return &fakeHealthProvider{results: credprov.NotHeldReport(time.Now())}
		},
	}
	cfg := Config{
		TemporalAddress: "127.0.0.1:1", // connection refused: fails Run fast, after registration
		APIServerURL:    srv.URL,
		CallbackURL:     "http://cb",
		CallbackPort:    19194,
		Provider:        NewTokenFleetProvider(srv.URL, "ckf_secret", nil, srv.Client()),
		CredentialPlane: plane,
		Executor:        nopExecutor{},
	}

	_ = Run(context.Background(), cfg)

	require.NotNil(t, deps.Credential, "Run must have built the plane's Provider")
	assert.Empty(t, credentialAtNew)
	require.Error(t, scopeErrAtNew, "a scope lookup before registration must be refused, not sent unauthenticated")
	assert.Empty(t, scopeAuth, "the refused lookup must not have reached the API server")

	assert.Equal(t, minted, deps.Credential())
	repos, _, err := deps.API.GitHubTokenScope(context.Background(), uuid.New())
	require.NoError(t, err)
	assert.Equal(t, []string{"acme/widgets"}, repos)
	assert.Equal(t, "Bearer "+minted, <-scopeAuth)
}

// -----------------------------------------------------------------------------------
// lateBoundAPI
// -----------------------------------------------------------------------------------

func TestLateBoundAPIRefusesBeforeBind(t *testing.T) {
	b := &lateBoundAPI{}

	_, _, err := b.GitHubTokenScope(context.Background(), uuid.New())
	require.Error(t, err)
}

type fakeLateBoundTarget struct {
	repos  []string
	perms  map[string]string
	gotRun uuid.UUID
}

func (f *fakeLateBoundTarget) GitHubTokenScope(_ context.Context, runID uuid.UUID) ([]string, map[string]string, error) {
	f.gotRun = runID
	return f.repos, f.perms, nil
}

func TestLateBoundAPIDelegatesAfterBind(t *testing.T) {
	b := &lateBoundAPI{}
	target := &fakeLateBoundTarget{repos: []string{"acme/widgets"}, perms: map[string]string{"contents": "read"}}
	b.bind(target)
	runID := uuid.New()

	repos, perms, err := b.GitHubTokenScope(context.Background(), runID)
	require.NoError(t, err)
	assert.Equal(t, []string{"acme/widgets"}, repos)
	assert.Equal(t, map[string]string{"contents": "read"}, perms)
	assert.Equal(t, runID, target.gotRun)
}

// -----------------------------------------------------------------------------------
// LocalCredentialPlane's CheckHealth wiring
// -----------------------------------------------------------------------------------

func TestLocalCredentialPlane_CheckHealthWorksWithNothingConfigured(t *testing.T) {
	clearLocalCredentialEnv(t)
	provider := LocalCredentialPlane(t.TempDir(), "", "").New(PlaneDeps{API: &stubScopeClient{}, Credential: func() string { return "" }})

	results := provider.CheckHealth(context.Background())

	require.Len(t, results, 3)
	for _, r := range results {
		assert.Equal(t, credprov.StatusNotHeld, r.Status, "%s", r.Kind)
		assert.False(t, r.CheckedAt.IsZero())
	}
}
