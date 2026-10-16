// Package github is a small stdlib-only GitHub REST client for the Worker: the compare, pull
// request and merge calls a gate needs, ref deletion for run-branch cleanup, issue filing and
// closing, and GitHub App installation-token minting.
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
	"strconv"
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
	// RateLimited is true when Status reflects GitHub throttling the caller rather than refusing
	// the request outright; a caller retries this after backing off instead of giving up on it.
	RateLimited bool
}

func (e *APIError) Error() string {
	if e.OwnerRepo == "" {
		return fmt.Sprintf("GitHub API returned HTTP %d", e.Status)
	}
	return fmt.Sprintf("GitHub API returned HTTP %d for %s", e.Status, e.OwnerRepo)
}

// RefusedError is GitHub declining a request (merge: 405, 409 or 422; issue listing or creation:
// 403, 404, 410 or 422). Reason is GitHub's message field only, with the token scrubbed and capped
// at 300 characters.
type RefusedError struct {
	Status int
	Reason string
	// Op names what was refused, for example "merge" or "request"; empty reads as "merge" so a
	// caller built before this field existed keeps its exact wording.
	Op string
}

func (e *RefusedError) Error() string {
	op := e.Op
	if op == "" {
		op = "merge"
	}
	return fmt.Sprintf("%s refused (HTTP %d): %s", op, e.Status, e.Reason)
}

// isRateLimited tells a GitHub rate limit from a refusal: 403 carries both meanings, and reading a
// throttled request as "no permission" would stop retrying something that heals on its own.
func isRateLimited(status int, h http.Header) bool {
	switch {
	case status == http.StatusTooManyRequests:
		return true
	case status != http.StatusForbidden:
		return false
	case h.Get("Retry-After") != "":
		return true
	default:
		return h.Get("X-RateLimit-Remaining") == "0"
	}
}

// classify turns a non-success response into *RefusedError when status is one of refusalStatuses
// and it is not actually a rate limit (403 is both), or *APIError otherwise, so a caller can tell
// "a person must act" from "retry me".
func classify(resp response, ownerRepo, token string, refusalStatuses ...int) error {
	rateLimited := isRateLimited(resp.status, resp.header)
	if !rateLimited {
		for _, s := range refusalStatuses {
			if resp.status == s {
				return &RefusedError{
					Status: resp.status,
					Reason: refusalReason(resp.body, resp.status, token, "request"),
					Op:     "request",
				}
			}
		}
	}
	return &APIError{Status: resp.status, OwnerRepo: ownerRepo, RateLimited: rateLimited}
}

// Issue is a GitHub issue as read back for the lookup phase of filing one: Body carries the hidden
// task-key comment a retry searches for.
type Issue struct {
	Number  int
	HTMLURL string
	Body    string
}

// IssuesSince lists ownerRepo's issues, open or closed, updated since the given time, through the
// plain listing rather than search, whose index lags a just-created issue. Pull requests, which
// the same endpoint returns, are skipped. onPage, when given, is called once per page fetched.
func (c *Client) IssuesSince(ctx context.Context, token, ownerRepo string, since time.Time, onPage ...func(page int)) ([]Issue, error) {
	repo, err := repoPath(ownerRepo)
	if err != nil {
		return nil, err
	}
	var issues []Issue
	for page := 1; ; page++ {
		q := url.Values{}
		q.Set("state", "all")
		q.Set("since", since.UTC().Format(time.RFC3339))
		q.Set("per_page", "100")
		q.Set("page", strconv.Itoa(page))
		resp, err := c.send(ctx, http.MethodGet, "/repos/"+repo+"/issues?"+q.Encode(), token, nil)
		if err != nil {
			return nil, err
		}
		if resp.status != http.StatusOK {
			return nil, classify(resp, ownerRepo, token, http.StatusForbidden, http.StatusNotFound, http.StatusGone)
		}
		var wire []struct {
			Number      int             `json:"number"`
			HTMLURL     string          `json:"html_url"`
			Body        *string         `json:"body"`
			PullRequest json.RawMessage `json:"pull_request"`
		}
		if err := json.Unmarshal(resp.body, &wire); err != nil {
			return nil, fmt.Errorf("decode issues for %s: %w", ownerRepo, err)
		}
		for _, w := range wire {
			// GitHub's issues endpoint also returns pull requests; only those carry this key.
			if len(w.PullRequest) > 0 {
				continue
			}
			body := ""
			if w.Body != nil {
				body = *w.Body
			}
			issues = append(issues, Issue{Number: w.Number, HTMLURL: w.HTMLURL, Body: body})
		}
		for _, cb := range onPage {
			cb(page)
		}
		if len(wire) < 100 {
			break
		}
	}
	return issues, nil
}

// CreateIssue opens a new issue. A refusal (no permission, archived or missing repo, validation
// failure) returns *RefusedError; anything else, including a rate limit, returns *APIError.
func (c *Client) CreateIssue(ctx context.Context, token, ownerRepo, title, body string) (Issue, error) {
	repo, err := repoPath(ownerRepo)
	if err != nil {
		return Issue{}, err
	}
	payload, err := json.Marshal(struct {
		Title string `json:"title"`
		Body  string `json:"body"`
	}{Title: title, Body: body})
	if err != nil {
		return Issue{}, fmt.Errorf("encode issue request for %s: %w", ownerRepo, err)
	}
	resp, err := c.send(ctx, http.MethodPost, "/repos/"+repo+"/issues", token, payload)
	if err != nil {
		return Issue{}, err
	}
	if resp.status != http.StatusCreated {
		return Issue{}, classify(resp, ownerRepo, token,
			http.StatusForbidden, http.StatusNotFound, http.StatusGone, http.StatusUnprocessableEntity)
	}
	var wire struct {
		Number  int    `json:"number"`
		HTMLURL string `json:"html_url"`
	}
	if err := json.Unmarshal(resp.body, &wire); err != nil {
		return Issue{}, fmt.Errorf("decode created issue for %s: %w", ownerRepo, err)
	}
	return Issue{Number: wire.Number, HTMLURL: wire.HTMLURL}, nil
}

// CloseIssue sets an issue's state to closed. Any 2xx returns nil; any other status, a transferred
// issue's unfollowed redirect included, returns an *APIError that never keeps the body.
func (c *Client) CloseIssue(ctx context.Context, token, ownerRepo string, number int) error {
	repo, err := repoPath(ownerRepo)
	if err != nil {
		return err
	}
	if number <= 0 {
		return fmt.Errorf("issue number %d for %s is not positive", number, ownerRepo)
	}
	payload, err := json.Marshal(struct {
		State string `json:"state"`
	}{State: "closed"})
	if err != nil {
		return fmt.Errorf("encode issue close request for %s#%d: %w", ownerRepo, number, err)
	}
	resp, err := c.send(ctx, http.MethodPatch, fmt.Sprintf("/repos/%s/issues/%d", repo, number), token, payload)
	if err != nil {
		return err
	}
	if resp.status < 200 || resp.status > 299 {
		return &APIError{Status: resp.status, OwnerRepo: ownerRepo}
	}
	return nil
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
// status returns an *APIError that never keeps the body, which can echo the request.
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
		return "", &RefusedError{Status: resp.status, Reason: refusalReason(resp.body, resp.status, token, "merge")}
	default:
		return "", &APIError{Status: resp.status, OwnerRepo: ownerRepo}
	}
}

// refusalReason keeps GitHub's message field and nothing else of the body, which can echo the
// request. The token is scrubbed before the cap so a cut through it cannot leave a prefix behind.
// op names what was refused, for example "merge" or "request", and appears only in the fallback
// used when GitHub's own response carries no message.
func refusalReason(body []byte, status int, token, op string) string {
	var wire struct {
		Message *string `json:"message"`
	}
	_ = json.Unmarshal(body, &wire)
	if wire.Message == nil || strings.TrimSpace(*wire.Message) == "" {
		return fmt.Sprintf("GitHub refused the %s (HTTP %d)", op, status)
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
	header http.Header
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
	return response{status: resp.StatusCode, header: resp.Header, body: raw}, nil
}

// repoPath validates an owner/repo slug and escapes it for a URL path.
func repoPath(ownerRepo string) (string, error) {
	if strings.Count(ownerRepo, "/") != 1 {
		return "", fmt.Errorf("%q is not an owner/repo slug", ownerRepo)
	}
	return escapePath(ownerRepo)
}

// ValidOwnerRepo reports whether ownerRepo is a slug this client's calls accept, so a caller can
// reject one before spending a GitHub call on it.
func ValidOwnerRepo(ownerRepo string) bool {
	_, err := repoPath(ownerRepo)
	return err == nil
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
