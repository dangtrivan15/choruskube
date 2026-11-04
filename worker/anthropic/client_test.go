package anthropic

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"github.com/dangtrivan15/choruskube/worker/credential"
)

func TestCheckSendsTheAnthropicVersionHeaderAndBearerAuth(t *testing.T) {
	var gotVersion, gotAuth, gotUA string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotVersion = r.Header.Get("anthropic-version")
		gotAuth = r.Header.Get("Authorization")
		gotUA = r.Header.Get("User-Agent")
		assert.Equal(t, "/v1/models", r.URL.Path)
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	status, detail := New(srv.URL).Check(context.Background(), "sk-ant-oat-token")

	assert.Equal(t, credential.StatusValid, status)
	assert.Empty(t, detail)
	assert.Equal(t, "2023-06-01", gotVersion)
	assert.Equal(t, "Bearer sk-ant-oat-token", gotAuth)
	assert.Equal(t, "choruskube-worker", gotUA)
}

func TestCheckMapsStatusesToOutcomes(t *testing.T) {
	cases := []struct {
		status int
		want   credential.Status
	}{
		{http.StatusOK, credential.StatusValid},
		{http.StatusUnauthorized, credential.StatusExpired},
		{http.StatusForbidden, credential.StatusInsufficientPermissions},
		{http.StatusInternalServerError, credential.StatusUnreachable},
	}
	for _, tc := range cases {
		t.Run(http.StatusText(tc.status), func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
			}))
			defer srv.Close()

			status, _ := New(srv.URL).Check(context.Background(), "tok")

			assert.Equal(t, tc.want, status)
		})
	}
}

func TestCheckNeverIncludesTheTokenInTheDetail(t *testing.T) {
	const token = "sk-ant-oat-super-secret"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()

	_, detail := New(srv.URL).Check(context.Background(), token)

	assert.NotContains(t, detail, token)
}

func TestCheckTimeoutIsUnreachable(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-r.Context().Done():
		case <-time.After(2 * time.Second):
		}
	}))
	defer srv.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	status, detail := New(srv.URL).Check(ctx, "tok")

	assert.Equal(t, credential.StatusUnreachable, status)
	assert.NotEmpty(t, detail)
}
