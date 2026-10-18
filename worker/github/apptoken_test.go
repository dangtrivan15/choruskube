package github

import (
	"context"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newRSAKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	return key
}

func pkcs1PEM(key *rsa.PrivateKey) []byte {
	return pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: x509.MarshalPKCS1PrivateKey(key)})
}

func pkcs8PEM(t *testing.T, key any) []byte {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(key)
	require.NoError(t, err)
	return pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der})
}

var testTokenRequest = TokenRequest{
	Repositories: []string{"backend-api"},
	Permissions:  map[string]string{"contents": "write"},
}

type jwtClaims struct {
	Iat int64  `json:"iat"`
	Exp int64  `json:"exp"`
	Iss string `json:"iss"`
}

// verifyJWT checks the compact serialization's shape and RS256 signature, returning header and claims.
func verifyJWT(t *testing.T, jwt string, pub *rsa.PublicKey) (map[string]any, jwtClaims) {
	t.Helper()
	parts := strings.Split(jwt, ".")
	require.Len(t, parts, 3)
	for _, p := range parts {
		assert.NotContains(t, p, "=", "base64url must be unpadded")
		assert.NotContains(t, p, "+")
		assert.NotContains(t, p, "/")
	}
	decode := func(s string) []byte {
		raw, err := base64.RawURLEncoding.DecodeString(s)
		require.NoError(t, err)
		return raw
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	require.NoError(t, rsa.VerifyPKCS1v15(pub, crypto.SHA256, digest[:], decode(parts[2])))

	var header map[string]any
	require.NoError(t, json.Unmarshal(decode(parts[0]), &header))
	var claims jwtClaims
	require.NoError(t, json.Unmarshal(decode(parts[1]), &claims))
	return header, claims
}

func TestInstallationTokenSignsRS256JWT(t *testing.T) {
	key := newRSAKey(t)
	now := time.Date(2026, 10, 11, 9, 0, 0, 0, time.UTC)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "/app/installations/99/access_tokens", r.URL.Path)
		assert.Equal(t, "application/vnd.github+json", r.Header.Get("Accept"))
		assert.Equal(t, "2022-11-28", r.Header.Get("X-GitHub-Api-Version"))
		auth := r.Header.Get("Authorization")
		require.True(t, strings.HasPrefix(auth, "Bearer "))

		header, claims := verifyJWT(t, strings.TrimPrefix(auth, "Bearer "), &key.PublicKey)
		assert.Equal(t, map[string]any{"alg": "RS256", "typ": "JWT"}, header)
		assert.Equal(t, "42", claims.Iss)
		assert.LessOrEqual(t, claims.Exp-claims.Iat, int64(600))
		assert.Equal(t, now.Unix()-60, claims.Iat)
		assert.Equal(t, now.Unix()+540, claims.Exp)

		body, err := io.ReadAll(r.Body)
		require.NoError(t, err)
		assert.JSONEq(t, `{"repositories":["backend-api"],"permissions":{"contents":"write"}}`, string(body))

		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"token":"ghs_x","expires_at":"2026-10-11T10:00:00Z"}`)
	})

	tok, err := c.InstallationToken(context.Background(), "42", "99", pkcs1PEM(key), now, testTokenRequest)

	require.NoError(t, err)
	assert.Equal(t, "ghs_x", tok.Value)
	assert.True(t, time.Date(2026, 10, 11, 10, 0, 0, 0, time.UTC).Equal(tok.ExpiresAt))
}

func TestInstallationTokenAcceptsAPKCS8Key(t *testing.T) {
	key := newRSAKey(t)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		verifyJWT(t, strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "), &key.PublicKey)
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"token":"ghs_x","expires_at":"2026-10-11T10:00:00Z"}`)
	})

	tok, err := c.InstallationToken(context.Background(), "42", "99", pkcs8PEM(t, key), time.Now(), testTokenRequest)

	require.NoError(t, err)
	assert.Equal(t, "ghs_x", tok.Value)
}

func TestInstallationTokenRejectsAKeyItCannotUseWithoutCallingGitHub(t *testing.T) {
	ecKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	require.NoError(t, err)
	called := false
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { called = true })

	for name, keyPEM := range map[string][]byte{
		"empty":            nil,
		"not PEM":          []byte("-----not a key-----"),
		"garbage in block": pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: []byte("SENSITIVE-BYTES")}),
		"non-RSA PKCS8":    pkcs8PEM(t, ecKey),
	} {
		t.Run(name, func(t *testing.T) {
			_, err := c.InstallationToken(context.Background(), "42", "99", keyPEM, time.Now(), testTokenRequest)

			require.Error(t, err)
			assert.NotContains(t, err.Error(), "SENSITIVE-BYTES")
			assert.NotContains(t, err.Error(), "not a key")
		})
	}
	assert.False(t, called)
}

func TestInstallationTokenRequiresBothIdentifiers(t *testing.T) {
	key := newRSAKey(t)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		t.Error("GitHub must not be called without both identifiers")
	})

	_, err := c.InstallationToken(context.Background(), "", "99", pkcs1PEM(key), time.Now(), testTokenRequest)
	require.Error(t, err)
	_, err = c.InstallationToken(context.Background(), "42", "", pkcs1PEM(key), time.Now(), testTokenRequest)
	require.Error(t, err)
}

func TestInstallationTokenRefusesAnEmptyRequestWithoutCallingGitHub(t *testing.T) {
	key := newRSAKey(t)
	called := false
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { called = true })

	for name, req := range map[string]TokenRequest{
		"no repositories": {Repositories: nil, Permissions: map[string]string{"contents": "write"}},
		"no permissions":  {Repositories: []string{"backend-api"}, Permissions: nil},
		"both empty":      {},
	} {
		t.Run(name, func(t *testing.T) {
			_, err := c.InstallationToken(context.Background(), "42", "99", pkcs1PEM(key), time.Now(), req)
			require.Error(t, err)
		})
	}
	assert.False(t, called)
}

func TestInstallationTokenRefusalIsAnAPIErrorWithoutTheBodyOrJWT(t *testing.T) {
	key := newRSAKey(t)
	var sentJWT string
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		sentJWT = strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = io.WriteString(w, `{"message":"Bad credentials echo: `+sentJWT+`"}`)
	})

	_, err := c.InstallationToken(context.Background(), "42", "99", pkcs1PEM(key), time.Now(), testTokenRequest)

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 401, apiErr.Status)
	require.NotEmpty(t, sentJWT)
	assert.NotContains(t, err.Error(), sentJWT)
	assert.NotContains(t, err.Error(), "Bad credentials")
}

func TestInstallationTokenWithoutATokenInTheResponseIsAnError(t *testing.T) {
	key := newRSAKey(t)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_, _ = io.WriteString(w, `{"expires_at":"2026-10-11T10:00:00Z"}`)
	})

	_, err := c.InstallationToken(context.Background(), "42", "99", pkcs1PEM(key), time.Now(), testTokenRequest)

	require.Error(t, err)
}

// -----------------------------------------------------------------------------------
// Installation
// -----------------------------------------------------------------------------------

func TestInstallationParsesAccountAndPermissions(t *testing.T) {
	key := newRSAKey(t)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "/app/installations/99", r.URL.Path)
		auth := r.Header.Get("Authorization")
		require.True(t, strings.HasPrefix(auth, "Bearer "))
		verifyJWT(t, strings.TrimPrefix(auth, "Bearer "), &key.PublicKey)

		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"account":{"login":"acme"},"permissions":{"contents":"write","metadata":"read"}}`)
	})

	inst, err := c.Installation(context.Background(), "42", "99", pkcs1PEM(key), time.Now())

	require.NoError(t, err)
	assert.Equal(t, "acme", inst.Account)
	assert.Equal(t, map[string]string{"contents": "write", "metadata": "read"}, inst.Permissions)
}

func TestInstallationNon200IsAnAPIError(t *testing.T) {
	key := newRSAKey(t)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = io.WriteString(w, `{"message":"suspended"}`)
	})

	_, err := c.Installation(context.Background(), "42", "99", pkcs1PEM(key), time.Now())

	var apiErr *APIError
	require.ErrorAs(t, err, &apiErr)
	assert.Equal(t, 403, apiErr.Status)
	assert.NotContains(t, err.Error(), "suspended")
}

func TestInstallationRequiresBothIdentifiers(t *testing.T) {
	key := newRSAKey(t)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		t.Error("GitHub must not be called without both identifiers")
	})

	_, err := c.Installation(context.Background(), "", "99", pkcs1PEM(key), time.Now())
	require.Error(t, err)
	_, err = c.Installation(context.Background(), "42", "", pkcs1PEM(key), time.Now())
	require.Error(t, err)
}

func TestInstallationWithoutAnAccountLoginIsAnError(t *testing.T) {
	key := newRSAKey(t)
	c := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, `{"permissions":{"contents":"write"}}`)
	})

	_, err := c.Installation(context.Background(), "42", "99", pkcs1PEM(key), time.Now())

	require.Error(t, err)
}
