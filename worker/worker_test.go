package worker

import (
	"context"
	"testing"
)

type stubProvider struct{}

func (stubProvider) Fleets(context.Context) (Registration, error) { return Registration{}, nil }

func TestConfigValidate(t *testing.T) {
	cases := []struct {
		name    string
		cfg     Config
		wantErr string
	}{
		{"ok", Config{TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb", Provider: stubProvider{}}, ""},
		{"no temporal", Config{APIServerURL: "http://a", CallbackURL: "http://cb", Provider: stubProvider{}}, "TemporalAddress is required"},
		{"no api server", Config{TemporalAddress: "t:7233", CallbackURL: "http://cb", Provider: stubProvider{}}, "APIServerURL is required"},
		{"no callback", Config{TemporalAddress: "t:7233", APIServerURL: "http://a", Provider: stubProvider{}}, "CallbackURL is required"},
		{"no provider", Config{TemporalAddress: "t:7233", APIServerURL: "http://a", CallbackURL: "http://cb"}, "Provider is required"},
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

func TestConfigWithDefaults_FillsCallbackPortWhenZero(t *testing.T) {
	got := Config{}.withDefaults()
	if got.CallbackPort != defaultCallbackPort {
		t.Fatalf("CallbackPort = %d, want the default %d", got.CallbackPort, defaultCallbackPort)
	}
}

func TestConfigWithDefaults_LeavesAnExplicitCallbackPortAlone(t *testing.T) {
	got := Config{CallbackPort: 9999}.withDefaults()
	if got.CallbackPort != 9999 {
		t.Fatalf("CallbackPort = %d, want the configured 9999 left untouched", got.CallbackPort)
	}
}
