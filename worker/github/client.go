// Package github is a small stdlib-only GitHub REST client for the Worker: the compare, pull
// request and merge calls a gate needs, ref deletion for run-branch cleanup, and GitHub App
// installation-token minting.
package github

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	acceptHeader   = "application/vnd.github+json"
	apiVersion     = "2022-11-28"
	maxReasonChars = 300
	maxBodyBytes   = 32 << 20
	// userAgent identifies the Worker as the caller in GitHub's logs and, in e2e, in WireMock's
	// request journal, which is how a test tells the Worker's GitHub calls apart from the API server's.
	userAgent = "choruskube-worker"
)

// Client talks to one GitHub API root. The caller supplies a token per call, so a Client holds no
// credential and is safe to share.
type Client struct {
	baseURL string
	http    *http.Client
}

// New returns a Client for the API root at baseURL, for example https://api.github.com.
func New(baseURL string) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		http: &http.Client{
			Timeout: 30 * time.Second,
			// A followed redirect rewrites the merge PUT into a GET that answers 200 without merging.
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}
}

// APIError is a non-success status. The response body is never kept: GitHub can echo the request,
// Authorization header included, and this error reaches logs and activity failures.
// OwnerRepo is empty for calls that are not about a repository.
type APIError struct {
	Status    int
	OwnerRepo string
}

func (e *APIError) Error() string {
	if e.OwnerRepo == "" {
		return fmt.Sprintf("GitHub API returned HTTP %d", e.Status)
	}
	return fmt.Sprintf("GitHub API returned HTTP %d for %s", e.Status, e.OwnerRepo)
}

// RefusedError is GitHub declining a merge (405, 409 or 422). Reason is GitHub's message field
// only, with the token scrubbed and capped at 300 characters.
type RefusedError struct {
	Status int
	Reason string
}

func (e *RefusedError) Error() string {
	return fmt.Sprintf("merge refused (HTTP %d): %s", e.Status, e.Reason)
}

// Compare returns how many commits head is ahead of base.
func (c *Client) Compare(ctx context.Context, token, ownerRepo, base, head string) (aheadBy int, err error) {
	repo, err := repoPath(ownerRepo)
	if err != nil {
		return 0, err
	}
	baseRef, err := escapePath(base)
	if err != nil {
		return 0, err
	}
	headRef, err := escapePath(head)
	if err != nil {
		return 0, err
	}
	resp, err := c.send(ctx, http.MethodGet, "/repos/"+repo+"/compare/"+baseRef+"..."+headRef, token, nil)
	if err != nil {
		return 0, err
	}
	if resp.status != http.StatusOK {
		return 0, &APIError{Status: resp.status, OwnerRepo: ownerRepo}
	}
	var wire struct {
		AheadBy *int `json:"ahead_by"`
	}
	if err := json.Unmarshal(resp.body, &wire); err != nil {
		return 0, fmt.Errorf("decode compare response for %s: %w", ownerRepo, err)
	}
	// Zero would read as "main has not moved", so a response without the field must fail.
	if wire.AheadBy == nil {
		return 0, fmt.Errorf("compare response for %s has no ahead_by", ownerRepo)
	}
	return *wire.AheadBy, nil
}

// DeleteRef deletes a ref, for example "heads/choruskube-run-<runID>". 204 returns nil; any other
// status returns an *APIError without reading the body, which can echo the request.
func (c *Client) DeleteRef(ctx context.Context, token, ownerRepo, ref string) error {
	repo, err := repoPath(ownerRepo)
	if err != nil {
		return err
	}
	escapedRef, err := escapePath(ref)
	if err != nil {
		return err
	}
	resp, err := c.send(ctx, http.MethodDelete, "/repos/"+repo+"/git/refs/"+escapedRef, token, nil)
	if err != nil {
		return err
	}
	if resp.status != http.StatusNoContent {
		return &APIError{Status: resp.status, OwnerRepo: ownerRepo}
	}
	return nil
}

// PullRequest is a pull request's state as GitHub reports it. Mergeable is nil while GitHub has
// not yet computed it.
type PullRequest struct {
	State     string
	Merged    bool
	MergedAt  *time.Time
	Draft     bool
	Mergeable *bool
	HeadSHA   string
	BaseRef   string
}

// PullRequest reads one pull request.
func (c *Client) PullRequest(ctx context.Context, token, ownerRepo string, number int) (PullRequest, error) {
	repo, err := repoPath(ownerRepo)
	if err != nil {
		return PullRequest{}, err
	}
	resp, err := c.send(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/pulls/%d", repo, number), token, nil)
	if err != nil {
		return PullRequest{}, err
	}
	if resp.status != http.StatusOK {
		return PullRequest{}, &APIError{Status: resp.status, OwnerRepo: ownerRepo}
	}
	var wire struct {
		State     string     `json:"state"`
		Merged    bool       `json:"merged"`
		MergedAt  *time.Time `json:"merged_at"`
		Draft     bool       `json:"draft"`
		Mergeable *bool      `json:"mergeable"`
		Head      struct {
			SHA string `json:"sha"`
		} `json:"head"`
		Base struct {
			Ref string `json:"ref"`
		} `json:"base"`
	}
	if err := json.Unmarshal(resp.body, &wire); err != nil {
		return PullRequest{}, fmt.Errorf("decode pull request %s#%d: %w", ownerRepo, number, err)
	}
	// An empty head SHA would later send Merge unpinned, merging commits nobody inspected.
	if wire.State == "" || wire.Head.SHA == "" {
		return PullRequest{}, fmt.Errorf("pull request %s#%d response has no state or head sha", ownerRepo, number)
	}
	return PullRequest{
		State:     wire.State,
		Merged:    wire.Merged,
		MergedAt:  wire.MergedAt,
		Draft:     wire.Draft,
		Mergeable: wire.Mergeable,
		HeadSHA:   wire.Head.SHA,
		BaseRef:   wire.Base.Ref,
	}, nil
}

// Merge merges a pull request and returns the merge commit SHA. sha pins the head GitHub will
// merge, so a push after inspection refuses; an empty sha sends the merge unpinned.
func (c *Client) Merge(ctx context.Context, token, ownerRepo string, number int, method, sha string) (mergeSHA string, err error) {
	repo, err := repoPath(ownerRepo)
	if err != nil {
		return "", err
	}
	payload, err := json.Marshal(struct {
		MergeMethod string `json:"merge_method"`
		SHA         string `json:"sha,omitempty"`
	}{MergeMethod: method, SHA: sha})
	if err != nil {
		return "", fmt.Errorf("encode merge request for %s#%d: %w", ownerRepo, number, err)
	}
	resp, err := c.send(ctx, http.MethodPut, fmt.Sprintf("/repos/%s/pulls/%d/merge", repo, number), token, payload)
	if err != nil {
		return "", err
	}
	switch resp.status {
	case http.StatusOK:
		var wire struct {
			SHA string `json:"sha"`
		}
		if err := json.Unmarshal(resp.body, &wire); err != nil {
			return "", fmt.Errorf("decode merge response for %s#%d: %w", ownerRepo, number, err)
		}
		if wire.SHA == "" {
			return "", fmt.Errorf("merge response for %s#%d has no sha", ownerRepo, number)
		}
		return wire.SHA, nil
	case http.StatusMethodNotAllowed, http.StatusConflict, http.StatusUnprocessableEntity:
		return "", &RefusedError{Status: resp.status, Reason: refusalReason(resp.body, resp.status, token)}
	default:
		return "", &APIError{Status: resp.status, OwnerRepo: ownerRepo}
	}
}

// refusalReason keeps GitHub's message field and nothing else of the body, which can echo the
// request. The token is scrubbed before the cap so a cut through it cannot leave a prefix behind.
func refusalReason(body []byte, status int, token string) string {
	var wire struct {
		Message *string `json:"message"`
	}
	_ = json.Unmarshal(body, &wire)
	if wire.Message == nil || strings.TrimSpace(*wire.Message) == "" {
		return fmt.Sprintf("GitHub refused the merge (HTTP %d)", status)
	}
	reason := *wire.Message
	if strings.TrimSpace(token) != "" {
		reason = strings.ReplaceAll(reason, token, "***")
	}
	if runes := []rune(reason); len(runes) > maxReasonChars {
		reason = string(runes[:maxReasonChars])
	}
	return reason
}

type response struct {
	status int
	body   []byte
}

// send issues one request and reads a bounded body. Errors it returns carry no header values.
func (c *Client) send(ctx context.Context, method, path, bearer string, payload []byte) (response, error) {
	var body io.Reader
	if payload != nil {
		body = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, body)
	if err != nil {
		return response{}, fmt.Errorf("build GitHub request: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+bearer)
	req.Header.Set("Accept", acceptHeader)
	req.Header.Set("X-GitHub-Api-Version", apiVersion)
	req.Header.Set("User-Agent", userAgent)
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return response{}, fmt.Errorf("GitHub request: %w", err)
	}
	defer func() { _ = resp.Body.Close() }()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes+1))
	if err != nil {
		return response{}, fmt.Errorf("read GitHub response: %w", err)
	}
	if len(raw) > maxBodyBytes {
		return response{}, errors.New("GitHub response exceeds the size limit")
	}
	return response{status: resp.StatusCode, body: raw}, nil
}

// repoPath validates an owner/repo slug and escapes it for a URL path.
func repoPath(ownerRepo string) (string, error) {
	if strings.Count(ownerRepo, "/") != 1 {
		return "", fmt.Errorf("%q is not an owner/repo slug", ownerRepo)
	}
	return escapePath(ownerRepo)
}

// escapePath escapes each "/"-separated segment, keeping the slashes GitHub accepts in ref names.
// Empty and dot segments are refused: the server would resolve them, silently changing the route.
func escapePath(s string) (string, error) {
	segments := strings.Split(s, "/")
	for i, seg := range segments {
		if seg == "" || seg == "." || seg == ".." {
			return "", fmt.Errorf("%q is not a safe URL path", s)
		}
		segments[i] = url.PathEscape(seg)
	}
	return strings.Join(segments, "/"), nil
}
