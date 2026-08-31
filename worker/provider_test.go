package worker

import (
	"context"
	"errors"
	"testing"
)

func TestStaticFleetProviderServesExactlyOneFleet(t *testing.T) {
	fleets, err := NewStaticFleetProvider("ns", "q").Fleets(context.Background())
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(fleets) != 1 {
		t.Fatalf("want exactly one fleet, got %d", len(fleets))
	}
	if fleets[0].Namespace != "ns" || fleets[0].TaskQueue != "q" {
		t.Fatalf("fleet not threaded: %+v", fleets[0])
	}
	// No token, so Run omits credentials and the dial stays plaintext -- see clientOptions.
	if fleets[0].Token != "" {
		t.Fatalf("static provider must carry no token, got %q", fleets[0].Token)
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
