package worker

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

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

	reg, err := NewTokenFleetProvider(srv.URL, "ckf_secret", srv.Client()).Fleets(context.Background())
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

	p := NewTokenFleetProvider(srv.URL, "ckf_secret", srv.Client())
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

	_, err := NewTokenFleetProvider(srv.URL, "bad", srv.Client()).Fleets(context.Background())
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

	reg, err := NewTokenFleetProvider(srv.URL, "ckf_secret", srv.Client()).Fleets(context.Background())
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

	reg, err := NewTokenFleetProvider(srv.URL, "ckf_secret", srv.Client()).Fleets(context.Background())
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

	p := NewTokenFleetProvider(srv.URL, "ckf_secret", srv.Client())
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
