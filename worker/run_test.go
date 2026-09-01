package worker

import (
	"context"
	"errors"
	"testing"
)

type errProvider struct{ err error }

func (p errProvider) Fleets(context.Context) ([]Fleet, error) { return nil, p.err }

type fixedProvider struct{ fleets []Fleet }

func (p fixedProvider) Fleets(context.Context) ([]Fleet, error) { return p.fleets, nil }

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
	})
	if !errors.Is(err, boom) {
		t.Fatalf("want the provider's error wrapped, got %v", err)
	}
}

func TestRunRejectsEmptyFleetList(t *testing.T) {
	err := Run(context.Background(), Config{
		TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb", Provider: fixedProvider{},
	})
	if err == nil {
		t.Fatal("want an error when no Fleet is served, got nil")
	}
}
