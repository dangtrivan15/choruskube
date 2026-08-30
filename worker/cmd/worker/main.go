// Command worker is the default entrypoint for a ChorusKube Worker process: it reads its
// configuration from the environment, registers with its Fleet, and serves agent-step
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

	// Checked here, not in Config: the credential is provider-specific, and
	// NewTokenFleetProvider accepts an empty token without complaint — surfacing only as an
	// opaque registration HTTP error otherwise.
	fleetToken := os.Getenv("FLEET_TOKEN")
	if fleetToken == "" {
		log.Fatal("FLEET_TOKEN is required")
	}

	cfg := worker.Config{
		TemporalAddress: os.Getenv("TEMPORAL_ADDRESS"),
		APIServerURL:    os.Getenv("API_SERVER_URL"),
		InternalSecret:  os.Getenv("ORCHESTRATOR_SECRET"),
		CallbackURL:     os.Getenv("CALLBACK_URL"),
	}
	cfg.Provider = worker.NewTokenFleetProvider(cfg.APIServerURL, fleetToken, nil)

	// Fail on a missing required setting here, before Run ever reaches the network: a bad
	// config would otherwise surface first as an opaque registration HTTP error.
	if err := cfg.Validate(); err != nil {
		log.Fatalf("invalid worker config: %v", err)
	}

	if err := worker.Run(ctx, cfg); err != nil {
		log.Fatalf("worker exited: %v", err)
	}
}
