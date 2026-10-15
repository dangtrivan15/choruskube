package github

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newTestClient(t *testing.T, h http.HandlerFunc) *Client {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return New(srv.URL)
}

func requireGitHubHeaders(t *testing.T, r *http.Request, token string) {
	t.Helper()
	assert.Equal(t, "Bearer "+token, r.Header.Get("Authorization"))
	assert.Equal(t, "application/vnd.github+json", r.Header.Get("Accept"))
	assert.Equal(t, "2022-11-28", r.Header.Get("X-GitHub-Api-Version"))
	assert.Equal(t, "choruskube-worker", r.Header.Get("User-Agent"))
}

func TestCompareReturnsAheadBy(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/repos/acme/app/compare/abc...main", r.URL.Path)
		requireGitHubHeaders(t, r, "t")
		_, _ = io.WriteString(w, `{"ahead_by":3,"status":"ahead"}`)
	})

	ahead, err := c.Compare(context.Background(), "t", "acme/app", "abc", "main")

	require.NoError(t, err)
	assert.Equal(t, 3, ahead)
}

func TestCompare404IsAPIError404(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = io.WriteString(w, `{"message":"Not Found"}`)
	})

	_, err := c.Compare(context.Background(), "t", "acme/app", "abc", "main")

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 404, apiErr.Status)
	assert.Equal(t, "acme/app", apiErr.OwnerRepo)
}

func TestCompareKeepsSlashesInRefsAndEscapesTheRest(t *testing.T) {
	var escaped string
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		escaped = r.URL.EscapedPath()
		_, _ = io.WriteString(w, `{"ahead_by":0}`)
	})

	_, err := c.Compare(context.Background(), "t", "acme/app", "feature/a b#1?x", "release/v1")

	require.NoError(t, err)
	assert.Equal(t, "/repos/acme/app/compare/feature/a%20b%231%3Fx...release/v1", escaped)
}

func TestCompareRejectsRefsThatWouldChangeThePath(t *testing.T) {
	called := false
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { called = true })

	for _, tc := range []struct{ name, ownerRepo, base, head string }{
		{"dot-dot in base", "acme/app", "../../x", "main"},
		{"dot-dot in head", "acme/app", "abc", "a/../b"},
		{"empty head", "acme/app", "abc", ""},
		{"empty base", "acme/app", "", "main"},
		{"owner only", "acme", "abc", "main"},
		{"three segments", "a/b/c", "abc", "main"},
		{"dot-dot in repo", "acme/..", "abc", "main"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, err := c.Compare(context.Background(), "t", tc.ownerRepo, tc.base, tc.head)
			require.Error(t, err)
		})
	}
	assert.False(t, called, "an unsafe path must never reach the server")
}

func TestDeleteRefSendsDeleteToTheRefPath(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodDelete, r.Method)
		assert.Equal(t, "", r.Header.Get("Content-Type"))
		requireGitHubHeaders(t, r, "t")
		assert.Regexp(t, `^/repos/acme/app/git/refs/heads/choruskube-run-[0-9a-f-]+$`, r.URL.EscapedPath())
		w.WriteHeader(http.StatusNoContent)
	})

	err := c.DeleteRef(context.Background(), "t", "acme/app", "heads/choruskube-run-11111111-1111-1111-1111-111111111111")

	require.NoError(t, err)
}

func TestDeleteRefKeepsSlashesInRefAndEscapesTheRest(t *testing.T) {
	var escaped string
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		escaped = r.URL.EscapedPath()
		w.WriteHeader(http.StatusNoContent)
	})

	err := c.DeleteRef(context.Background(), "t", "acme/app", "heads/feature/a b#1?x")

	require.NoError(t, err)
	assert.Equal(t, "/repos/acme/app/git/refs/heads/feature/a%20b%231%3Fx", escaped)
}

func TestDeleteRefRejectsRefsThatWouldChangeThePath(t *testing.T) {
	called := false
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { called = true })

	for _, tc := range []struct{ name, ref string }{
		{"dot-dot", "heads/../main"},
		{"empty segment", "heads//x"},
		{"empty", ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			err := c.DeleteRef(context.Background(), "t", "acme/app", tc.ref)
			require.Error(t, err)
		})
	}
	assert.False(t, called, "an unsafe ref must never reach the server")
}

func TestDeleteRefNonSuccessIsAPIErrorThatNeverEchoesTheBody(t *testing.T) {
	for _, status := range []int{404, 422, 500} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				_, _ = io.WriteString(w, `{"message":"boom","request":{"Authorization":"Bearer secret-token"}}`)
			})

			err := c.DeleteRef(context.Background(), "secret-token", "acme/app", "heads/choruskube-run-x")

			var apiErr *APIError
			require.ErrorAs(t, err, &apiErr)
			assert.Equal(t, status, apiErr.Status)
			assert.NotContains(t, err.Error(), "secret-token")
			assert.NotContains(t, err.Error(), "boom")
		})
	}
}

func TestPullRequestParsesFields(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/repos/acme/app/pulls/7", r.URL.Path)
		requireGitHubHeaders(t, r, "t")
		_, _ = io.WriteString(w, `{
			"state":"closed","merged":true,"merged_at":"2026-10-11T09:30:00Z",
			"draft":true,"mergeable":false,
			"head":{"sha":"h1","ref":"feature"},"base":{"ref":"main","sha":"b1"}}`)
	})

	pr, err := c.PullRequest(context.Background(), "t", "acme/app", 7)

	require.NoError(t, err)
	wantMergedAt := time.Date(2026, 10, 11, 9, 30, 0, 0, time.UTC)
	require.NotNil(t, pr.MergedAt)
	assert.True(t, wantMergedAt.Equal(*pr.MergedAt))
	require.NotNil(t, pr.Mergeable)
	assert.False(t, *pr.Mergeable)
	assert.Equal(t, "closed", pr.State)
	assert.True(t, pr.Merged)
	assert.True(t, pr.Draft)
	assert.Equal(t, "h1", pr.HeadSHA)
	assert.Equal(t, "main", pr.BaseRef)
}

func TestPullRequestLeavesUnknownMergeabilityAndMergedAtNil(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"state":"open","merged":false,"merged_at":null,"draft":false,
			"mergeable":null,"head":{"sha":"h1"},"base":{"ref":"main"}}`)
	})

	pr, err := c.PullRequest(context.Background(), "t", "acme/app", 7)

	require.NoError(t, err)
	assert.Nil(t, pr.MergedAt)
	assert.Nil(t, pr.Mergeable, "null mergeable means GitHub has not computed it yet, not false")
	assert.Equal(t, "open", pr.State)
}

func TestPullRequestNon200IsAPIError(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
	})

	_, err := c.PullRequest(context.Background(), "t", "acme/app", 7)

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 403, apiErr.Status)
}

func TestMergePinsHeadSHA(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPut, r.Method)
		assert.Equal(t, "/repos/acme/app/pulls/7/merge", r.URL.Path)
		requireGitHubHeaders(t, r, "t")
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))
		var body map[string]any
		assert.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, map[string]any{"merge_method": "squash", "sha": "h1"}, body)
		_, _ = io.WriteString(w, `{"sha":"m1","merged":true}`)
	})

	got, err := c.Merge(context.Background(), "t", "acme/app", 7, "squash", "h1")

	require.NoError(t, err)
	assert.Equal(t, "m1", got)
}

func TestMergeOmitsShaWhenNoneGiven(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		assert.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, map[string]any{"merge_method": "merge"}, body)
		_, _ = io.WriteString(w, `{"sha":"m1"}`)
	})

	_, err := c.Merge(context.Background(), "t", "acme/app", 7, "merge", "")

	require.NoError(t, err)
}

func TestMergeWithoutAShaInTheResponseIsAnError(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"merged":true}`)
	})

	got, err := c.Merge(context.Background(), "t", "acme/app", 7, "squash", "h1")

	require.Error(t, err)
	assert.Empty(t, got)
}

func TestMergeRefusalIsRefusedErrorScrubbed(t *testing.T) {
	for _, status := range []int{405, 409, 422} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			body := `{"message":"Pull Request is not mergeable; the branch policy blocks it","documentation_url":"x","echo":"Bearer t"}`
			c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
				_, _ = io.WriteString(w, body)
			})

			_, err := c.Merge(context.Background(), "t", "acme/app", 7, "squash", "h1")

			var refused *RefusedError
			require.ErrorAs(t, err, &refused)
			assert.Equal(t, status, refused.Status)
			assert.NotContains(t, refused.Reason, "t", "the token must be replaced everywhere it occurs")
			assert.Contains(t, refused.Reason, "***")
			assert.LessOrEqual(t, utf8.RuneCountInString(refused.Reason), 300)
			assert.NotContains(t, err.Error(), "documentation_url", "only the message field survives")
			assert.NotContains(t, err.Error(), "echo")
		})
	}
}

func TestMergeRefusalScrubsBeforeCappingSoACutCannotLeaveATokenPrefix(t *testing.T) {
	const token = "ghs_abcdefghijklmnop"
	message := strings.Repeat("x", 290) + token + strings.Repeat("y", 50)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusConflict)
		raw, _ := json.Marshal(map[string]string{"message": message})
		_, _ = w.Write(raw)
	})

	_, err := c.Merge(context.Background(), token, "acme/app", 7, "squash", "h1")

	var refused *RefusedError
	require.ErrorAs(t, err, &refused)
	assert.Equal(t, 300, utf8.RuneCountInString(refused.Reason))
	assert.NotContains(t, refused.Reason, "ghs_")
	assert.True(t, strings.HasPrefix(refused.Reason, strings.Repeat("x", 290)+"***"))
}

func TestMergeRefusalCapCountsCharactersNotBytes(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnprocessableEntity)
		raw, _ := json.Marshal(map[string]string{"message": strings.Repeat("é", 400)})
		_, _ = w.Write(raw)
	})

	_, err := c.Merge(context.Background(), "t", "acme/app", 7, "squash", "h1")

	var refused *RefusedError
	require.ErrorAs(t, err, &refused)
	assert.Equal(t, strings.Repeat("é", 300), refused.Reason)
	assert.True(t, utf8.ValidString(refused.Reason))
}

func TestMergeRefusalFallsBackWhenTheBodyHasNoMessage(t *testing.T) {
	for name, body := range map[string]string{
		"not json":      "<html>the token t was here</html>",
		"no message":    `{"errors":["t"]}`,
		"blank message": `{"message":"   "}`,
		"null message":  `{"message":null}`,
	} {
		t.Run(name, func(t *testing.T) {
			c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(http.StatusConflict)
				_, _ = io.WriteString(w, body)
			})

			_, err := c.Merge(context.Background(), "t", "acme/app", 7, "squash", "h1")

			var refused *RefusedError
			require.ErrorAs(t, err, &refused)
			assert.Equal(t, "GitHub refused the merge (HTTP 409)", refused.Reason)
		})
	}
}

func TestMergeOtherStatusIsAPIErrorThatNeverEchoesTheBody(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = io.WriteString(w, `{"message":"boom","request":{"Authorization":"Bearer secret-token"}}`)
	})

	_, err := c.Merge(context.Background(), "secret-token", "acme/app", 7, "squash", "h1")

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 500, apiErr.Status)
	var refused *RefusedError
	assert.False(t, errors.As(err, &refused))
	assert.NotContains(t, err.Error(), "secret-token")
	assert.NotContains(t, err.Error(), "boom")
	assert.Contains(t, err.Error(), "500")
	assert.Contains(t, err.Error(), "acme/app")
}

func TestRedirectsAreSurfacedNotFollowed(t *testing.T) {
	// A followed redirect turns the merge PUT into a GET, which would 200 without merging anything.
	var hits int
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		hits++
		if r.URL.Path == "/moved" {
			_, _ = io.WriteString(w, `{"sha":"looks-merged"}`)
			return
		}
		http.Redirect(w, r, "/moved", http.StatusMovedPermanently)
	})

	got, err := c.Merge(context.Background(), "t", "acme/app", 7, "squash", "h1")

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 301, apiErr.Status)
	assert.Empty(t, got)
	assert.Equal(t, 1, hits)
}

func TestCancelledContextAbortsTheCall(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(5 * time.Second):
		}
	})
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	_, err := c.Compare(ctx, "t", "acme/app", "abc", "main")

	require.Error(t, err)
	assert.ErrorIs(t, err, context.DeadlineExceeded)
}

func TestNewTrimsATrailingSlashFromTheBaseURL(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/repos/acme/app/pulls/1", r.URL.Path)
		_, _ = io.WriteString(w, `{"state":"open","head":{"sha":"h"},"base":{"ref":"main"}}`)
	}))
	t.Cleanup(srv.Close)

	_, err := New(srv.URL+"/").PullRequest(context.Background(), "t", "acme/app", 1)

	require.NoError(t, err)
}

func TestCompareWithoutAheadByIsAnErrorNotZero(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"status":"ahead"}`)
	})

	ahead, err := c.Compare(context.Background(), "t", "acme/app", "abc", "main")

	require.Error(t, err)
	assert.Zero(t, ahead)
}

func TestPullRequestWithoutAHeadShaIsAnError(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `{"state":"open","head":{},"base":{"ref":"main"}}`)
	})

	_, err := c.PullRequest(context.Background(), "t", "acme/app", 7)

	require.Error(t, err)
}

func issueWire(number int) string {
	return fmt.Sprintf(`{"number":%d,"html_url":"https://github.com/acme/app/issues/%d","body":"body %d"}`, number, number, number)
}

func TestIssuesSinceSendsTheQueryGitHubExpects(t *testing.T) {
	var gotPath, gotQuery string
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		requireGitHubHeaders(t, r, "t")
		_, _ = io.WriteString(w, "[]")
	})
	since := time.Date(2026, 10, 14, 9, 30, 0, 0, time.FixedZone("x", 3600))

	_, err := c.IssuesSince(context.Background(), "t", "acme/app", since)

	require.NoError(t, err)
	assert.Equal(t, "/repos/acme/app/issues", gotPath)
	q, err := url.ParseQuery(gotQuery)
	require.NoError(t, err)
	assert.Equal(t, "all", q.Get("state"))
	assert.Equal(t, "100", q.Get("per_page"))
	assert.Equal(t, "1", q.Get("page"))
	assert.Equal(t, "2026-10-14T08:30:00Z", q.Get("since"), "since is converted to UTC before formatting")
}

func TestIssuesSincePaginatesUntilAShortPage(t *testing.T) {
	var pages []string
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		pages = append(pages, r.URL.Query().Get("page"))
		if r.URL.Query().Get("page") == "1" {
			items := make([]string, 100)
			for i := range items {
				items[i] = issueWire(i + 1)
			}
			_, _ = io.WriteString(w, "["+strings.Join(items, ",")+"]")
			return
		}
		_, _ = io.WriteString(w, "["+issueWire(101)+","+issueWire(102)+"]")
	})
	var heartbeats []int
	onPage := func(page int) { heartbeats = append(heartbeats, page) }

	issues, err := c.IssuesSince(context.Background(), "t", "acme/app", time.Now(), onPage)

	require.NoError(t, err)
	assert.Equal(t, []string{"1", "2"}, pages, "a full first page is followed by a second request")
	assert.Len(t, issues, 102)
	assert.Equal(t, []int{1, 2}, heartbeats, "onPage is called once per page fetched")
}

func TestIssuesSinceSkipsPullRequestsAndReadsNullBodyAsEmpty(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `[
			{"number":1,"html_url":"https://github.com/acme/app/issues/1","body":null},
			{"number":2,"html_url":"https://github.com/acme/app/pull/2","body":"has a PR","pull_request":{"url":"x"}}
		]`)
	})

	issues, err := c.IssuesSince(context.Background(), "t", "acme/app", time.Now())

	require.NoError(t, err)
	require.Len(t, issues, 1, "the pull request is skipped")
	assert.Equal(t, 1, issues[0].Number)
	assert.Equal(t, "", issues[0].Body, "a null body reads as empty, not the literal \"null\"")
}

func TestIssuesSinceRefusalStatusesReturnAScrubbedRefusedErrorNeverMentioningMerge(t *testing.T) {
	for _, status := range []int{http.StatusNotFound, http.StatusGone, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
			})

			_, err := c.IssuesSince(context.Background(), "t", "acme/app", time.Now())

			var refused *RefusedError
			require.ErrorAs(t, err, &refused)
			assert.Equal(t, status, refused.Status)
			assert.NotContains(t, refused.Reason, "merge")
			assert.Contains(t, refused.Reason, fmt.Sprintf("HTTP %d", status))
		})
	}
}

func TestIssuesSinceForbiddenWithRateLimitSignalsIsAPIErrorNotRefused(t *testing.T) {
	cases := []struct {
		name    string
		headers map[string]string
	}{
		{"Retry-After", map[string]string{"Retry-After": "30"}},
		{"X-RateLimit-Remaining zero", map[string]string{"X-RateLimit-Remaining": "0"}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				for k, v := range tc.headers {
					w.Header().Set(k, v)
				}
				w.WriteHeader(http.StatusForbidden)
			})

			_, err := c.IssuesSince(context.Background(), "t", "acme/app", time.Now())

			var apiErr *APIError
			require.ErrorAs(t, err, &apiErr)
			assert.True(t, apiErr.RateLimited)
			var refused *RefusedError
			assert.False(t, errors.As(err, &refused), "a rate limit is retried, not refused")
		})
	}
}

func TestIssuesSince429IsRateLimited(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
	})

	_, err := c.IssuesSince(context.Background(), "t", "acme/app", time.Now())

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.True(t, apiErr.RateLimited)
}

func TestCreateIssueSendsTitleAndBodyAndDecodesTheCreatedIssue(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/repos/acme/app/issues", r.URL.Path)
		requireGitHubHeaders(t, r, "t")
		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		assert.Equal(t, map[string]any{"title": "a title", "body": "a body"}, body)
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"number":42,"html_url":"https://github.com/acme/app/issues/42"}`)
	})

	iss, err := c.CreateIssue(context.Background(), "t", "acme/app", "a title", "a body")

	require.NoError(t, err)
	assert.Equal(t, 42, iss.Number)
	assert.Equal(t, "https://github.com/acme/app/issues/42", iss.HTMLURL)
}

func TestCreateIssueRefusalStatusesNeverMentionMerge(t *testing.T) {
	for _, status := range []int{http.StatusNotFound, http.StatusGone, http.StatusUnprocessableEntity, http.StatusForbidden} {
		t.Run(http.StatusText(status), func(t *testing.T) {
			c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(status)
			})

			_, err := c.CreateIssue(context.Background(), "t", "acme/app", "t", "b")

			var refused *RefusedError
			require.ErrorAs(t, err, &refused)
			assert.Equal(t, status, refused.Status)
			assert.NotContains(t, refused.Reason, "merge")
			assert.NotContains(t, err.Error(), "merge")
		})
	}
}

func TestCreateIssueForbiddenWithRateLimitSignalsIsAPIErrorNotRefused(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "60")
		w.WriteHeader(http.StatusForbidden)
	})

	_, err := c.CreateIssue(context.Background(), "t", "acme/app", "t", "b")

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.True(t, apiErr.RateLimited)
}

func TestCreateIssue429IsRateLimited(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
	})

	_, err := c.CreateIssue(context.Background(), "t", "acme/app", "t", "b")

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.True(t, apiErr.RateLimited)
}

func TestCreateIssueOtherStatusIsAPIError(t *testing.T) {
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	})

	_, err := c.CreateIssue(context.Background(), "t", "acme/app", "t", "b")

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 500, apiErr.Status)
}
