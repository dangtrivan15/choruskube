// Command worker is the default entrypoint for a ChorusKube Worker process: it reads its
// configuration from the environment, resolves which Fleet it serves, and runs agent-step
// activities until terminated.
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/dangtrivan15/choruskube/worker"
)

func main() {
	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()

	cfg := worker.Config{
		TemporalAddress: os.Getenv("TEMPORAL_ADDRESS"),
		APIServerURL:    os.Getenv("API_SERVER_URL"),
		CallbackURL:     os.Getenv("CALLBACK_URL"),
		// Opt-in, so a deployment against a TLS Temporal cannot lose TLS by omission.
		TemporalTLSDisabled: os.Getenv("TEMPORAL_TLS_DISABLED") == "true",
	}

	capabilities, err := worker.ParseCapabilitiesEnv(os.Getenv("WORKER_CAPABILITIES"))
	if err != nil {
		log.Fatalf("invalid WORKER_CAPABILITIES: %v", err)
	}

	// Resolved here, not in Config: which Fleet this process serves is the one setting with two
	// legitimate answers, and Config.Validate can only see that a Provider was supplied.
	// LookupEnv, not Getenv: an exported-but-empty FLEET_TOKEN means "I meant to register and my
	// credential did not arrive", which must fail rather than fall through to the static path.
	fleetToken, fleetTokenSet := os.LookupEnv("FLEET_TOKEN")
	provider, err := worker.FleetSource{
		APIServerURL:  cfg.APIServerURL,
		FleetToken:    fleetToken,
		FleetTokenSet: fleetTokenSet,
		Namespace:     os.Getenv("TEMPORAL_NAMESPACE"),
		TaskQueue:     os.Getenv("TEMPORAL_TASK_QUEUE"),
		// Only the static path needs this: the registration path is handed a credential, or
		// falls back to the Fleet token it registered with.
		InternalToken: os.Getenv("WORKER_INTERNAL_TOKEN"),
		Capabilities:  capabilities,
	}.Provider(nil)
	if err != nil {
		log.Fatalf("%v: set FLEET_TOKEN to register with the API server, "+
			"or TEMPORAL_NAMESPACE, TEMPORAL_TASK_QUEUE and WORKER_INTERNAL_TOKEN to serve one "+
			"Fleet without registering", err)
	}
	cfg.Provider = provider

	// Fail on a missing required setting here, before Run ever reaches the network: a bad
	// config would otherwise surface first as an opaque registration HTTP error.
	if err := cfg.Validate(); err != nil {
		log.Fatalf("invalid worker config: %v", err)
	}

	if err := worker.Run(ctx, cfg); err != nil {
		log.Fatalf("worker exited: %v", err)
	}
}
