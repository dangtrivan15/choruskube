package registry

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/worker/credential"
)

// withTestClient points the package's shared client at srv's own client, which trusts its
// self-signed certificate, and returns host without the "https://" prefix Check always adds.
func withTestClient(t *testing.T, srv *httptest.Server) (host string) {
	t.Helper()
	old := client
	client = srv.Client()
	t.Cleanup(func() { client = old })
	return strings.TrimPrefix(srv.URL, "https://")
}

func TestCheckDirectBasicSuccessIsValid(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "/v2/", r.URL.Path)
		assert.True(t, strings.HasPrefix(r.Header.Get("Authorization"), "Basic "))
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()
	host := withTestClient(t, srv)

	status, _ := Check(context.Background(), host, "user", "pass")

	assert.Equal(t, credential.StatusValid, status)
}

func TestCheckFollowsTheBearerChallengeToTheRealm(t *testing.T) {
	var realmURL string
	mux := http.NewServeMux()
	mux.HandleFunc("/v2/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer realm="%s",service="registry"`, realmURL))
		w.WriteHeader(http.StatusUnauthorized)
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "registry", r.URL.Query().Get("service"))
		w.WriteHeader(http.StatusOK)
	})
	srv := httptest.NewTLSServer(mux)
	defer srv.Close()
	realmURL = srv.URL + "/token"
	host := withTestClient(t, srv)

	status, _ := Check(context.Background(), host, "user", "pass")

	assert.Equal(t, credential.StatusValid, status)
}

func TestCheckScopedRetrySucceedsForARepositoryScopedCredential(t *testing.T) {
	var realmURL string
	var sawScopelessAttempt, sawScopedAttempt bool
	mux := http.NewServeMux()
	mux.HandleFunc("/v2/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer realm="%s"`, realmURL))
		w.WriteHeader(http.StatusUnauthorized)
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("scope") == probeScope {
			sawScopedAttempt = true
			w.WriteHeader(http.StatusOK)
			return
		}
		sawScopelessAttempt = true
		w.WriteHeader(http.StatusUnauthorized)
	})
	srv := httptest.NewTLSServer(mux)
	defer srv.Close()
	realmURL = srv.URL + "/token"
	host := withTestClient(t, srv)

	status, _ := Check(context.Background(), host, "user", "pass")

	assert.Equal(t, credential.StatusValid, status)
	assert.True(t, sawScopelessAttempt)
	assert.True(t, sawScopedAttempt)
}

func TestCheckScopedRetryStillRejectedIsExpired(t *testing.T) {
	var realmURL string
	mux := http.NewServeMux()
	mux.HandleFunc("/v2/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("WWW-Authenticate", fmt.Sprintf(`Bearer realm="%s"`, realmURL))
		w.WriteHeader(http.StatusUnauthorized)
	})
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	})
	srv := httptest.NewTLSServer(mux)
	defer srv.Close()
	realmURL = srv.URL + "/token"
	host := withTestClient(t, srv)

	status, detail := Check(context.Background(), host, "user", "pass")

	assert.Equal(t, credential.StatusExpired, status)
	assert.NotEmpty(t, detail)
}

func TestCheckDirect401WithNoChallengeIsExpired(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	host := withTestClient(t, srv)

	status, _ := Check(context.Background(), host, "user", "pass")

	assert.Equal(t, credential.StatusExpired, status)
}

func TestCheck5xxIsUnreachable(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()
	host := withTestClient(t, srv)

	status, _ := Check(context.Background(), host, "user", "pass")

	assert.Equal(t, credential.StatusUnreachable, status)
}

func TestCheckConnectionRefusedIsUnreachable(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	host := withTestClient(t, srv)
	srv.Close() // closed before the call: nothing is listening

	status, detail := Check(context.Background(), host, "user", "pass")

	assert.Equal(t, credential.StatusUnreachable, status)
	assert.NotEmpty(t, detail)
}

func TestCheckNeverIncludesTheCredentialInTheDetail(t *testing.T) {
	const password = "super-secret-registry-password"
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	host := withTestClient(t, srv)

	_, detail := Check(context.Background(), host, "user", password)

	assert.NotContains(t, detail, password)
}

func TestCheckUnparseableChallengeIsUnreachable(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("WWW-Authenticate", "Bearer this has no realm")
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer srv.Close()
	host := withTestClient(t, srv)

	status, detail := Check(context.Background(), host, "user", "pass")

	require.Equal(t, credential.StatusUnreachable, status)
	assert.NotEmpty(t, detail)
}
