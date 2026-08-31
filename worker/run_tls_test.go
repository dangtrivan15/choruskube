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

// TestClientOptionsOmitsCredentialsWhenTheFleetHasNoToken is the whole reason clientOptions is a
// function. sdk@v1.41.0 internal/client.go's apiKeyCredentials.applyToOptions sets opts.TLS to a
// non-nil config whenever credentials are present -- it never looks at the key -- so an empty
// credential dials TLS at a Temporal that speaks plaintext and dies with "first record does not
// look like a TLS handshake". A single-Fleet deployment issues no token, so this is its normal
// state, not an edge case.
func TestClientOptionsOmitsCredentialsWhenTheFleetHasNoToken(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q"}
	opts := clientOptions(f, Config{TemporalAddress: "temporal:7233"}, newTokenCache([]Fleet{f}), fleetKey(f))

	if opts.Credentials != nil {
		t.Fatal("an empty token must produce no credentials at all: their presence is what turns TLS on")
	}
	if opts.ConnectionOptions.TLS != nil {
		t.Fatalf("TLS must not be forced on: %#v", opts.ConnectionOptions.TLS)
	}
	if opts.ConnectionOptions.TLSDisabled {
		t.Fatal("TLSDisabled must stay off: omitting credentials is what leaves the dial plaintext")
	}
	if opts.HostPort != "temporal:7233" || opts.Namespace != "ns" {
		t.Fatalf("address/namespace not threaded: %q %q", opts.HostPort, opts.Namespace)
	}
}

// A Fleet that does carry a token must still get credentials -- the omission above is keyed on the
// token being empty, not on some mode the deployment opts into.
func TestClientOptionsSetsCredentialsWhenTheFleetHasAToken(t *testing.T) {
	f := Fleet{Namespace: "ns", TaskQueue: "q", Token: "tok"}
	opts := clientOptions(f, Config{TemporalAddress: "temporal:7233"}, newTokenCache([]Fleet{f}), fleetKey(f))

	if opts.Credentials == nil {
		t.Fatal("a Fleet with a token must present it")
	}
}
