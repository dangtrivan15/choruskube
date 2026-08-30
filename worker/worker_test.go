package worker

import (
	"context"
	"testing"
)

type stubProvider struct{}

func (stubProvider) Fleets(context.Context) ([]Fleet, error) { return nil, nil }

func TestConfigValidate(t *testing.T) {
	cases := []struct {
		name    string
		cfg     Config
		wantErr string
	}{
		{"ok", Config{TemporalAddress: "t:7233", APIServerURL: "http://a", Provider: stubProvider{}}, ""},
		{"no temporal", Config{APIServerURL: "http://a", Provider: stubProvider{}}, "TemporalAddress is required"},
		{"no api server", Config{TemporalAddress: "t:7233", Provider: stubProvider{}}, "APIServerURL is required"},
		{"no provider", Config{TemporalAddress: "t:7233", APIServerURL: "http://a"}, "Provider is required"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			err := tc.cfg.Validate()
			if tc.wantErr == "" {
				if err != nil {
					t.Fatalf("want nil, got %v", err)
				}
				return
			}
			if err == nil || err.Error() != tc.wantErr {
				t.Fatalf("want %q, got %v", tc.wantErr, err)
			}
		})
	}
}
