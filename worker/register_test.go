package worker

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	credprov "github.com/dangtrivan15/choruskube/worker/credential"
)

// noHealthReport is the health source most registration tests attach: a provider's registration
// behaviour, not the report's content, is what they test.
func noHealthReport() []credprov.HealthResult {
	return credprov.NotHeldReport(time.Now())
}

// registeringProvider builds a *TokenFleetProvider with a health source already attached, so a
// test not about the report itself can call Fleets without thinking about it.
func registeringProvider(apiServerURL, fleetToken string, capabilities map[string]string, hc *http.Client) *TokenFleetProvider {
	p := NewTokenFleetProvider(apiServerURL, fleetToken, capabilities, hc)
	p.attachHealth(noHealthReport)
	return p
}

func TestTokenFleetProviderReturnsOneFleet(t *testing.T) {
	var gotAuth, gotPath string
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		gotPath = r.URL.Path
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt","expiresInSeconds":3600,"endpoint":"gw:7233"}`))
	}))
	defer srv.Close()

	reg, err := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client()).Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(reg.Fleets) != 1 {
		t.Fatalf("want 1 fleet, got %d", len(reg.Fleets))
	}
	f := reg.Fleets[0]
	if f.Namespace != "ns" || f.TaskQueue != "q" || f.Token != "jwt" || f.WorkerID != "w-1" || f.Endpoint != "gw:7233" || f.ExpiresInSeconds != 3600 {
		t.Fatalf("unexpected fleet: %+v", f)
	}
	if gotAuth != "Bearer ckf_secret" {
		t.Fatalf("want bearer auth, got %q", gotAuth)
	}
	if gotPath != "/worker/register" {
		t.Fatalf("want /worker/register, got %q", gotPath)
	}
	if _, ok := gotBody["hostname"]; !ok {
		t.Fatalf("registration must send a hostname, got %v", gotBody)
	}
	if _, ok := gotBody["instanceId"]; !ok {
		t.Fatalf("registration must send an instanceId, got %v", gotBody)
	}
}

func TestTokenFleetProviderReusesOneInstanceIDPerProcess(t *testing.T) {
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		_ = json.NewDecoder(r.Body).Decode(&body)
		id, ok := body["instanceId"].(string)
		if !ok {
			t.Fatalf("instanceId must be a string, got %T", body["instanceId"])
		}
		seen = append(seen, id)
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	p := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client())
	_, _ = p.Fleets(context.Background())
	_, _ = p.Fleets(context.Background())

	// The id identifies this process. A fresh one per call would create a new worker row
	// on every token renewal, which is the accounting this field exists to prevent.
	if len(seen) != 2 || seen[0] != seen[1] {
		t.Fatalf("want one stable instanceId, got %v", seen)
	}
}

func TestTokenFleetProviderRejectsNon2xx(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	}))
	defer srv.Close()

	_, err := registeringProvider(srv.URL, "bad", nil, srv.Client()).Fleets(context.Background())
	if err == nil {
		t.Fatal("want an error for 403, got nil")
	}
}

func TestTokenFleetProviderReturnsTheMintedInternalToken(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt","expiresInSeconds":3600,"endpoint":"","internalToken":"ckw_minted"}`))
	}))
	defer srv.Close()

	reg, err := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client()).Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if reg.InternalToken != "ckw_minted" {
		t.Fatalf("InternalToken = %q, want the minted one", reg.InternalToken)
	}
}

// Before any mint, a blank is the only signal a server that mints nothing ever sends, so the
// Worker stays on the credential it registered with. Falling back to anything else -- or to
// nothing -- would leave it unable to make application calls.
func TestTokenFleetProviderFallsBackToTheFleetTokenBeforeAnyMint(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"","expiresInSeconds":0,"endpoint":"","internalToken":""}`))
	}))
	defer srv.Close()

	reg, err := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client()).Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if reg.InternalToken != "ckf_secret" {
		t.Fatalf("InternalToken = %q, want the fleet token", reg.InternalToken)
	}
}

// Once a server has minted, a blank means the live credential is still fresh and must reach the
// caller as a blank: substituting the Fleet token would overwrite a working credential with one
// whose hash lives in another table, refusing every workload call until the next mint.
func TestTokenFleetProviderPropagatesABlankAfterAMint(t *testing.T) {
	bodies := []string{
		`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt","internalToken":"ckw_A"}`,
		`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt","internalToken":""}`,
	}
	var call int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if call < len(bodies) {
			_, _ = w.Write([]byte(bodies[call]))
		}
		call++
	}))
	defer srv.Close()

	p := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client())
	first, err := p.Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error on the first registration: %v", err)
	}
	if first.InternalToken != "ckw_A" {
		t.Fatalf("first InternalToken = %q, want the minted one", first.InternalToken)
	}

	second, err := p.Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error on the second registration: %v", err)
	}
	if second.InternalToken != "" {
		t.Fatalf("second InternalToken = %q, want a blank so the cache keeps ckw_A", second.InternalToken)
	}
}

// A server that mints nothing sends a blank on every registration, not only the first. The
// fallback is gated on whether any mint has happened, so a second blank must substitute the Fleet
// token again -- reading it as "your live credential is still fresh" would leave a Worker that
// never had one holding nothing at all.
func TestTokenFleetProviderFallsBackOnEveryBlankFromAServerThatNeverMints(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"","expiresInSeconds":0,"endpoint":"","internalToken":""}`))
	}))
	defer srv.Close()

	p := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client())
	for call := 1; call <= 2; call++ {
		reg, err := p.Fleets(context.Background())
		if err != nil {
			t.Fatalf("call %d: unexpected error: %v", call, err)
		}
		if reg.InternalToken != "ckf_secret" {
			t.Fatalf("call %d: InternalToken = %q, want the fleet token", call, reg.InternalToken)
		}
	}
}

func TestTokenFleetProviderSendsConfiguredCapabilities(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"","expiresInSeconds":0,"endpoint":"","internalToken":""}`))
	}))
	defer srv.Close()

	caps := map[string]string{"docker": "true"}
	if _, err := registeringProvider(srv.URL, "ckf_secret", caps, srv.Client()).Fleets(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	sent, ok := gotBody["capabilities"].(map[string]any)
	if !ok || sent["docker"] != "true" {
		t.Fatalf("capabilities sent = %v, want docker=true", gotBody["capabilities"])
	}
}

func TestTokenFleetProviderSendsTheAttachedCredentialSource(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	p := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client())
	p.attachCredentialSource("local")
	if _, err := p.Fleets(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotBody["credentialSource"] != "local" {
		t.Fatalf("credentialSource = %v, want %q", gotBody["credentialSource"], "local")
	}

	// A renewal must declare the same source as the initial registration.
	if _, err := p.Fleets(context.Background()); err != nil {
		t.Fatalf("unexpected error on renewal: %v", err)
	}
	if gotBody["credentialSource"] != "local" {
		t.Fatalf("renewal credentialSource = %v, want %q", gotBody["credentialSource"], "local")
	}
}

// There is no tolerant default here: a provider nobody has called attachCredentialSource on sends
// it exactly as blank, not silently upgraded to any mode.
func TestTokenFleetProviderSendsBlankCredentialSourceWhenNeverAttached(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	if _, err := registeringProvider(srv.URL, "ckf_secret", nil, srv.Client()).Fleets(context.Background()); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if gotBody["credentialSource"] != "" {
		t.Fatalf("credentialSource = %v, want a blank -- no silent default", gotBody["credentialSource"])
	}
}

// The server now requires a credentialHealth report on every registration call, so the body must
// carry one -- every kind, not just some -- on both the first call and a renewal.
func TestTokenFleetProviderSendsCredentialHealthOnFirstCallAndRenewal(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	now := time.Date(2026, 11, 4, 9, 0, 0, 0, time.UTC)
	results := []credprov.HealthResult{
		{Kind: credprov.KindGitHub, Status: credprov.StatusValid, CheckedAt: now},
		{Kind: credprov.KindClaude, Status: credprov.StatusExpired, Detail: "token expired", CheckedAt: now},
		{Kind: credprov.KindRegistry, Status: credprov.StatusNotHeld, CheckedAt: now},
	}
	p := NewTokenFleetProvider(srv.URL, "ckf_secret", nil, srv.Client())
	p.attachHealth(func() []credprov.HealthResult { return results })

	for _, label := range []string{"first call", "renewal"} {
		if _, err := p.Fleets(context.Background()); err != nil {
			t.Fatalf("%s: unexpected error: %v", label, err)
		}
		health, ok := gotBody["credentialHealth"].([]any)
		if !ok || len(health) != 3 {
			t.Fatalf("%s: credentialHealth = %v, want 3 entries", label, gotBody["credentialHealth"])
		}
		entry, ok := health[0].(map[string]any)
		if !ok {
			t.Fatalf("%s: credentialHealth[0] = %v, want an object", label, health[0])
		}
		if entry["kind"] != "github" || entry["status"] != "VALID" {
			t.Fatalf("%s: credentialHealth[0] = %v, want kind=github status=VALID", label, entry)
		}
		if entry["checkedAt"] != "2026-11-04T09:00:00Z" {
			t.Fatalf("%s: checkedAt = %v, want an RFC3339 UTC timestamp", label, entry["checkedAt"])
		}
		claude, ok := health[1].(map[string]any)
		if !ok || claude["detail"] != "token expired" {
			t.Fatalf("%s: credentialHealth[1] = %v, want detail=token expired", label, health[1])
		}
	}
}

// A TokenFleetProvider with no health source attached refuses to register rather than send an
// empty report: the server now requires one on every call.
func TestTokenFleetProviderWithoutAHealthSourceRefusesToRegister(t *testing.T) {
	hit := false
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	p := NewTokenFleetProvider(srv.URL, "ckf_secret", nil, srv.Client())
	_, err := p.Fleets(context.Background())

	if err == nil {
		t.Fatal("want an error when no health source is attached, got nil")
	}
	if hit {
		t.Fatal("want no HTTP request made when no health source is attached")
	}
}
