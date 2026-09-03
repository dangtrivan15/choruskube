package worker

import "testing"

func TestParseCapabilities(t *testing.T) {
	got, err := parseCapabilities("docker=true, sysbox=false")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(got) != 2 || got["docker"] != "true" || got["sysbox"] != "false" {
		t.Fatalf("got %v", got)
	}
}

// Empty must yield an empty map, never nil: the server compares what a Fleet requires against
// what the Worker reported, and a nil map would encode "unknown" where the Worker means "none".
func TestParseCapabilitiesEmptyYieldsEmptyMap(t *testing.T) {
	got, err := parseCapabilities("")
	if err != nil || got == nil || len(got) != 0 {
		t.Fatalf("got (%v, %v), want an empty non-nil map", got, err)
	}
}

func TestParseCapabilitiesRejectsMalformed(t *testing.T) {
	if _, err := parseCapabilities("docker"); err == nil {
		t.Fatal("a bare key must be an error, not a silently dropped capability")
	}
	if _, err := parseCapabilities("=true"); err == nil {
		t.Fatal("an empty key must be an error")
	}
}
