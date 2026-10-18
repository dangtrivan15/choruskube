package worker

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestStaticFleetProviderServesExactlyOneFleet(t *testing.T) {
	reg, err := NewStaticFleetProvider("ns", "q", "internal").Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(reg.Fleets) != 1 {
		t.Fatalf("want exactly one fleet, got %d", len(reg.Fleets))
	}
	if reg.Fleets[0].Namespace != "ns" || reg.Fleets[0].TaskQueue != "q" {
		t.Fatalf("fleet not threaded: %+v", reg.Fleets[0])
	}
	// No Temporal token, so Run omits credentials and the dial stays plaintext -- see
	// clientOptions. The API server credential is a different field and must not land here.
	if reg.Fleets[0].Token != "" {
		t.Fatalf("static provider must carry no token, got %q", reg.Fleets[0].Token)
	}
}

func TestFleetSourceSelectsRegistrationWhenATokenIsPresent(t *testing.T) {
	provider, err := FleetSource{APIServerURL: "http://api:8080", FleetToken: "ckf_x", FleetTokenSet: true}.Provider(nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, ok := provider.(*TokenFleetProvider); !ok {
		t.Fatalf("want the registering provider, got %T", provider)
	}
}

func TestFleetSourceSelectsStaticWhenNamespaceAndQueueAreSupplied(t *testing.T) {
	provider, err := FleetSource{APIServerURL: "http://api:8080", Namespace: "ns", TaskQueue: "q"}.Provider(nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, ok := provider.(*StaticFleetProvider); !ok {
		t.Fatalf("want the static provider, got %T", provider)
	}
}

// A token names a Fleet the server picks, and locally configured coordinates could name a
// different one. Registration has to win, or a stale env var would silently override the server.
func TestFleetSourceRegistrationWinsOverStaticCoordinates(t *testing.T) {
	provider, err := FleetSource{
		APIServerURL: "http://api:8080",
		FleetToken:   "ckf_x", FleetTokenSet: true,
		Namespace: "ns",
		TaskQueue: "q",
	}.Provider(nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, ok := provider.(*TokenFleetProvider); !ok {
		t.Fatalf("want the registering provider, got %T", provider)
	}
}

func TestFleetSourceRejectsAHalfConfiguredStaticFleet(t *testing.T) {
	for _, s := range []FleetSource{
		{APIServerURL: "http://api:8080", Namespace: "ns"},
		{APIServerURL: "http://api:8080", TaskQueue: "q"},
		{APIServerURL: "http://api:8080"},
	} {
		provider, err := s.Provider(nil)
		if !errors.Is(err, ErrNoFleetConfigured) {
			t.Fatalf("source %+v: want ErrNoFleetConfigured, got %v", s, err)
		}
		if provider != nil {
			t.Fatalf("source %+v: want no provider alongside the error, got %T", s, provider)
		}
	}
}

// The static path registers with nobody, so no server can mint it a credential. Without a
// configured one it would present an empty bearer on every application call.
func TestStaticFleetProviderCarriesItsConfiguredInternalToken(t *testing.T) {
	reg, err := NewStaticFleetProvider("ns", "q", "configured").Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(reg.Fleets) != 1 || reg.Fleets[0].Namespace != "ns" || reg.Fleets[0].TaskQueue != "q" {
		t.Fatalf("unexpected fleets: %+v", reg.Fleets)
	}
	if reg.InternalToken != "configured" {
		t.Fatalf("InternalToken = %q, want the configured one", reg.InternalToken)
	}
}

func TestFleetSourcePassesTheCredentialSourceToRegistration(t *testing.T) {
	var gotBody map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewDecoder(r.Body).Decode(&gotBody)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"workerId":"w-1","temporalNamespace":"ns","taskQueue":"q","token":"jwt"}`))
	}))
	defer srv.Close()

	provider, err := FleetSource{
		APIServerURL:     srv.URL,
		FleetToken:       "ckf_x",
		FleetTokenSet:    true,
		CredentialSource: "local",
	}.Provider(srv.Client())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, err := provider.Fleets(context.Background()); err != nil {
		t.Fatalf("unexpected error registering: %v", err)
	}
	if gotBody["credentialSource"] != "local" {
		t.Fatalf("credentialSource = %v, want %q", gotBody["credentialSource"], "local")
	}
}

// The static path never calls the API server, so there is nothing left for CredentialSource to
// report there -- with no record of it anywhere, refusing Local on this path protected nothing.
func TestFleetSourceStaticPathAcceptsAnyCredentialSource(t *testing.T) {
	for _, credentialSource := range []string{"", CredentialSourceControlPlane, CredentialSourceLocal} {
		provider, err := FleetSource{
			Namespace:        "ns",
			TaskQueue:        "q",
			CredentialSource: credentialSource,
		}.Provider(nil)
		if err != nil {
			t.Fatalf("credentialSource %q: unexpected error: %v", credentialSource, err)
		}
		if _, ok := provider.(*StaticFleetProvider); !ok {
			t.Fatalf("credentialSource %q: want the static provider, got %T", credentialSource, provider)
		}
	}
}
