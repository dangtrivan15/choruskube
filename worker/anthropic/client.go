// Package anthropic is a minimal client for the one Anthropic API call a Worker's credential
// health check needs: proving a Claude token is still accepted, without spending a real request.
package anthropic

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/dangtrivan15/choruskube/worker/credential"
)

// userAgent identifies the Worker as the caller, the same way the GitHub client does, so a stub's
// request journal can tell this check apart from any other caller.
const userAgent = "choruskube-worker"

// Client talks to one Anthropic API root. The caller supplies a token per call, so a Client holds
// no credential and is safe to share.
type Client struct {
	baseURL string
	http    *http.Client
}

// New returns a Client for the Anthropic API root at baseURL, for example
// https://api.anthropic.com.
func New(baseURL string) *Client {
	return &Client{baseURL: strings.TrimRight(baseURL, "/"), http: &http.Client{Timeout: 10 * time.Second}}
}

// Check sends GET /v1/models with Bearer auth and the required anthropic-version header,
// classifying the result the same way every credential health check does: 200 valid, 401
// expired, 403 insufficient permissions, anything else (including a network error or timeout)
// unreachable. The token never reaches the returned detail.
func (c *Client) Check(ctx context.Context, token string) (credential.Status, string) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/v1/models", nil)
	if err != nil {
		return credential.StatusUnreachable, err.Error()
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("anthropic-version", "2023-06-01")
	req.Header.Set("User-Agent", userAgent)
	resp, err := c.http.Do(req)
	if err != nil {
		return credential.StatusUnreachable, "request failed"
	}
	defer func() { _ = resp.Body.Close() }()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	switch resp.StatusCode {
	case http.StatusOK:
		return credential.StatusValid, ""
	case http.StatusUnauthorized:
		return credential.StatusExpired, fmt.Sprintf("Anthropic API returned HTTP %d", resp.StatusCode)
	case http.StatusForbidden:
		return credential.StatusInsufficientPermissions, fmt.Sprintf("Anthropic API returned HTTP %d", resp.StatusCode)
	default:
		return credential.StatusUnreachable, fmt.Sprintf("Anthropic API returned HTTP %d", resp.StatusCode)
	}
}
