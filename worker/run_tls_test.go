package worker

import "testing"

// The Temporal SDK auto-enables TLS whenever API-key credentials are set, which is correct for
// Temporal Cloud and fatal against a plaintext frontend: the dial fails with "first record does
// not look like a TLS handshake". These assert the escape hatch is wired and stays off by default.

func TestClientOptionsDefaultsToTLSEnabled(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "tok"}
	opts := clientOptions(f, Config{TemporalAddress: "temporal:7233"}, newTokenCache([]Fleet{f}), fleetKey(f))
	if opts.ConnectionOptions.TLSDisabled {
		t.Fatal("TLS must stay on by default: a Fleet credential is a bearer token")
	}
}

func TestClientOptionsHonoursTLSDisabled(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "tok"}
	cfg := Config{TemporalAddress: "temporal:7233", TemporalTLSDisabled: true}
	opts := clientOptions(f, cfg, newTokenCache([]Fleet{f}), fleetKey(f))
	if !opts.ConnectionOptions.TLSDisabled {
		t.Fatal("TemporalTLSDisabled must reach ConnectionOptions or a plaintext Temporal is unreachable")
	}
	if opts.HostPort != "temporal:7233" || opts.Namespace != "ns" {
		t.Fatalf("address/namespace not threaded: %q %q", opts.HostPort, opts.Namespace)
	}
}
