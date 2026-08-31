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
		InternalSecret:  os.Getenv("ORCHESTRATOR_SECRET"),
		CallbackURL:     os.Getenv("CALLBACK_URL"),
		// Opt-in, so a deployment against a TLS Temporal cannot lose TLS by omission.
		TemporalTLSDisabled: os.Getenv("TEMPORAL_TLS_DISABLED") == "true",
	}

	// Resolved here, not in Config: which Fleet this process serves is the one setting with two
	// legitimate answers, and Config.Validate can only see that a Provider was supplied.
	provider, err := worker.FleetSource{
		APIServerURL: cfg.APIServerURL,
		FleetToken:   os.Getenv("FLEET_TOKEN"),
		Namespace:    os.Getenv("TEMPORAL_NAMESPACE"),
		TaskQueue:    os.Getenv("TEMPORAL_TASK_QUEUE"),
	}.Provider(nil)
	if err != nil {
		log.Fatalf("%v: set FLEET_TOKEN to register with the API server, "+
			"or TEMPORAL_NAMESPACE and TEMPORAL_TASK_QUEUE to serve one Fleet without registering", err)
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
