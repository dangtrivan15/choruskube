package worker

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"

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
		hc = http.DefaultClient
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
}

func (p *TokenFleetProvider) Fleets(ctx context.Context) ([]Fleet, error) {
	hostname, err := os.Hostname()
	if err != nil {
		return nil, fmt.Errorf("read hostname: %w", err)
	}
	body, err := json.Marshal(map[string]any{
		"hostname":     hostname,
		"instanceId":   p.instanceID,
		"capabilities": map[string]string{},
	})
	if err != nil {
		return nil, fmt.Errorf("encode registration: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, p.baseURL+"/worker/register", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+p.token)
	req.Header.Set("Content-Type", "application/json")

	resp, err := p.hc.Do(req)
	if err != nil {
		return nil, fmt.Errorf("register: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		// The token stays out of this message: it is the one credential this process
		// holds that would let anything else join the Fleet.
		return nil, fmt.Errorf("register: unexpected status %d", resp.StatusCode)
	}
	var rr registerResponse
	if err := json.NewDecoder(resp.Body).Decode(&rr); err != nil {
		return nil, fmt.Errorf("decode registration: %w", err)
	}
	return []Fleet{{
		Namespace: rr.TemporalNamespace,
		TaskQueue: rr.TaskQueue,
		Token:     rr.Token,
		WorkerID:  rr.WorkerID,
		Endpoint:  rr.Endpoint,
	}}, nil
}
