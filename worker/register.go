package worker

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
)

// TokenFleetProvider obtains this Worker's Fleet by registering with a Fleet token.
// The token is Fleet-scoped rather than Worker-scoped, so a deployment with several
// replicas needs no per-process setup: each process registers and receives its own
// identity in the response.
type TokenFleetProvider struct {
	baseURL string
	token   string
	hc      *http.Client
	// instanceID identifies this process for the life of the process. The server keys the
	// worker row on it, so a value regenerated per call would leave a row per renewal.
	instanceID string
}

func NewTokenFleetProvider(apiServerURL, fleetToken string, hc *http.Client) *TokenFleetProvider {
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	return &TokenFleetProvider{
		baseURL:    strings.TrimRight(apiServerURL, "/"),
		token:      fleetToken,
		hc:         hc,
		instanceID: uuid.NewString(),
	}
}

type registerResponse struct {
	WorkerID          string `json:"workerId"`
	TemporalNamespace string `json:"temporalNamespace"`
	TaskQueue         string `json:"taskQueue"`
	Token             string `json:"token"`
	ExpiresInSeconds  int64  `json:"expiresInSeconds"`
	Endpoint          string `json:"endpoint"`
	InternalToken     string `json:"internalToken"`
}

func (p *TokenFleetProvider) Fleets(ctx context.Context) (Registration, error) {
	// Ensure the registration call has a deadline. If ctx lacks one, add 30 seconds.
	if _, ok := ctx.Deadline(); !ok {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, 30*time.Second)
		defer cancel()
	}

	hostname, err := os.Hostname()
	if err != nil {
		return Registration{}, fmt.Errorf("read hostname: %w", err)
	}
	body, err := json.Marshal(map[string]any{
		"hostname":     hostname,
		"instanceId":   p.instanceID,
		"capabilities": map[string]string{},
	})
	if err != nil {
		return Registration{}, fmt.Errorf("encode registration: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.baseURL+"/worker/register", bytes.NewReader(body))
	if err != nil {
		return Registration{}, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+p.token)
	req.Header.Set("Content-Type", "application/json")

	resp, err := p.hc.Do(req)
	if err != nil {
		return Registration{}, fmt.Errorf("register: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		// The token stays out of this message: it is the one credential this process
		// holds that would let anything else join the Fleet.
		return Registration{}, fmt.Errorf("register: unexpected status %d", resp.StatusCode)
	}
	var rr registerResponse
	if err := json.NewDecoder(resp.Body).Decode(&rr); err != nil {
		return Registration{}, fmt.Errorf("decode registration: %w", err)
	}
	internalToken := rr.InternalToken
	if internalToken == "" {
		// The server minted none, so this Worker keeps the credential it authenticated with.
		// A deployment whose server does mint gets a short-lived one instead, and this line is
		// what lets one binary serve both without a mode flag.
		internalToken = p.token
	}
	return Registration{
		Fleets: []Fleet{{
			Namespace:        rr.TemporalNamespace,
			TaskQueue:        rr.TaskQueue,
			Token:            rr.Token,
			WorkerID:         rr.WorkerID,
			Endpoint:         rr.Endpoint,
			ExpiresInSeconds: rr.ExpiresInSeconds,
		}},
		InternalToken: internalToken,
	}, nil
}
