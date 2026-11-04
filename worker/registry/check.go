// Package registry checks a container registry login with the lightest authenticated read the
// Docker Registry v2 API offers, with no pull and no push. It always dials https and carries no
// SSRF guard, because it checks a registry the Worker's own operator configured, never one a
// tenant supplied.
package registry

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/dangtrivan15/choruskube/worker/credential"
)

var realmPattern = regexp.MustCompile(`realm="([^"]+)"`)
var servicePattern = regexp.MustCompile(`service="([^"]+)"`)

// probeScope is a repository name no real credential check needs to reach: it exists only to ask
// a registry for a pull-scoped token, the shape a principal authorized for specific repositories
// (rather than an account-wide login) is actually granted.
const probeScope = "repository:choruskube-credential-check:pull"

// client is shared across every call: each request already carries its own timeout, via ctx or
// this default, so nothing here needs per-call state.
var client = &http.Client{Timeout: 10 * time.Second}

// Check pings host's Docker Registry v2 API with Basic auth, following a Bearer challenge to its
// token realm (with one retry carrying a pull scope, for a credential scoped to specific
// repositories). The outcomes are an API server's own registry check's, so a status means the
// same whichever side checked: 200 valid; a rejection (401, or the token endpoint's 401/403)
// expired; anything else, including a network error or timeout, unreachable.
func Check(ctx context.Context, host, username, password string) (credential.Status, string) {
	basic := base64.StdEncoding.EncodeToString([]byte(username + ":" + password))
	pingURL := "https://" + host + "/v2/"

	resp, err := get(ctx, pingURL, "Basic "+basic)
	if err != nil {
		return credential.StatusUnreachable, "network error reaching registry"
	}
	defer drain(resp)

	switch resp.StatusCode {
	case http.StatusOK:
		return credential.StatusValid, "registry accepted the credential"
	case http.StatusUnauthorized:
		challenge := resp.Header.Get("WWW-Authenticate")
		if challenge != "" && strings.HasPrefix(strings.ToLower(challenge), "bearer") {
			return checkViaTokenEndpoint(ctx, challenge, basic)
		}
		return credential.StatusExpired, "registry rejected the credential (401)"
	default:
		return credential.StatusUnreachable, fmt.Sprintf("registry returned HTTP %d", resp.StatusCode)
	}
}

func checkViaTokenEndpoint(ctx context.Context, challenge, basicAuth string) (credential.Status, string) {
	realmMatch := realmPattern.FindStringSubmatch(challenge)
	if realmMatch == nil {
		return credential.StatusUnreachable, "registry sent an unparseable auth challenge"
	}
	realmURL := realmMatch[1]
	parsed, err := url.ParseRequestURI(realmURL)
	if err != nil {
		return credential.StatusUnreachable, "registry sent an unparseable auth challenge"
	}
	if !strings.EqualFold(parsed.Scheme, "https") {
		// The initial ping is always https; following its challenge to a plaintext token
		// endpoint would send this credential's Basic auth header over the network in clear.
		return credential.StatusUnreachable, "registry auth challenge points at a non-HTTPS token endpoint; refusing to send credentials over plaintext"
	}

	if serviceMatch := servicePattern.FindStringSubmatch(challenge); serviceMatch != nil {
		realmURL = addQueryParam(realmURL, "service", serviceMatch[1])
	}

	resp, err := get(ctx, realmURL, "Basic "+basicAuth)
	if err != nil {
		return credential.StatusUnreachable, "network error reaching registry token endpoint"
	}
	defer drain(resp)

	switch resp.StatusCode {
	case http.StatusOK:
		return credential.StatusValid, "registry token endpoint accepted the credential"
	case http.StatusUnauthorized, http.StatusForbidden:
		// The scope-less token request is the registry's account-wide "login" grant. A
		// principal authorized only for specific repositories is rejected here even with a
		// valid credential, so retry once with a pull scope -- a normal Docker Registry v2
		// pull-token request -- before concluding the credential itself is bad.
		scopedURL := addQueryParam(realmURL, "scope", probeScope)
		scoped, err := get(ctx, scopedURL, "Basic "+basicAuth)
		if err != nil {
			return credential.StatusUnreachable, "network error reaching registry token endpoint"
		}
		defer drain(scoped)
		switch scoped.StatusCode {
		case http.StatusOK:
			return credential.StatusValid, "registry token endpoint accepted the credential"
		case http.StatusUnauthorized, http.StatusForbidden:
			return credential.StatusExpired, fmt.Sprintf("registry rejected the credential (%d)", scoped.StatusCode)
		default:
			return credential.StatusUnreachable, fmt.Sprintf("registry token endpoint returned HTTP %d", scoped.StatusCode)
		}
	default:
		return credential.StatusUnreachable, fmt.Sprintf("registry token endpoint returned HTTP %d", resp.StatusCode)
	}
}

func addQueryParam(rawURL, key, value string) string {
	sep := "?"
	if strings.Contains(rawURL, "?") {
		sep = "&"
	}
	return rawURL + sep + key + "=" + url.QueryEscape(value)
}

func get(ctx context.Context, rawURL, authorization string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", authorization)
	req.Header.Set("User-Agent", "choruskube-worker")
	return client.Do(req)
}

// drain discards the body so the connection can be reused, then closes it.
func drain(resp *http.Response) {
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<16))
	_ = resp.Body.Close()
}
