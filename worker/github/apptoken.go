package github

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"time"
)

// InstallationToken is a short-lived token that acts as a GitHub App installation.
type InstallationToken struct {
	Value     string
	ExpiresAt time.Time
}

// InstallationToken exchanges a JWT signed with the App's private key for an installation token.
// now is a parameter so the JWT's validity window is deterministic.
func (c *Client) InstallationToken(ctx context.Context, appID, installationID string, privateKeyPEM []byte, now time.Time) (InstallationToken, error) {
	if appID == "" || installationID == "" {
		return InstallationToken{}, errors.New("app id and installation id are required")
	}
	key, err := parseRSAPrivateKey(privateKeyPEM)
	if err != nil {
		return InstallationToken{}, err
	}
	jwt, err := signAppJWT(appID, key, now)
	if err != nil {
		return InstallationToken{}, err
	}
	path := "/app/installations/" + url.PathEscape(installationID) + "/access_tokens"
	resp, err := c.send(ctx, http.MethodPost, path, jwt, nil)
	if err != nil {
		return InstallationToken{}, err
	}
	if resp.status != http.StatusOK && resp.status != http.StatusCreated {
		return InstallationToken{}, &APIError{Status: resp.status}
	}
	var wire struct {
		Token     string    `json:"token"`
		ExpiresAt time.Time `json:"expires_at"`
	}
	if err := json.Unmarshal(resp.body, &wire); err != nil {
		return InstallationToken{}, fmt.Errorf("decode installation token response: %w", err)
	}
	if wire.Token == "" {
		return InstallationToken{}, errors.New("installation token response has no token")
	}
	return InstallationToken{Value: wire.Token, ExpiresAt: wire.ExpiresAt}, nil
}

// parseRSAPrivateKey accepts PKCS#1 and PKCS#8. Its errors never quote the PEM: a key that fails
// to parse must not end up in a log.
func parseRSAPrivateKey(pemBytes []byte) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil {
		return nil, errors.New("GitHub App private key is not PEM encoded")
	}
	if key, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return key, nil
	}
	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, errors.New("GitHub App private key is neither PKCS#1 nor PKCS#8")
	}
	key, ok := parsed.(*rsa.PrivateKey)
	if !ok {
		return nil, errors.New("GitHub App private key is not an RSA key")
	}
	return key, nil
}

// signAppJWT builds the App JWT. GitHub rejects an exp more than 10 minutes out, and iat is set
// back a minute so clock drift between this host and GitHub does not make it "issued in the future".
func signAppJWT(appID string, key *rsa.PrivateKey, now time.Time) (string, error) {
	claims, err := json.Marshal(struct {
		Iat int64  `json:"iat"`
		Exp int64  `json:"exp"`
		Iss string `json:"iss"`
	}{Iat: now.Unix() - 60, Exp: now.Unix() + 540, Iss: appID})
	if err != nil {
		return "", fmt.Errorf("encode JWT claims: %w", err)
	}
	enc := base64.RawURLEncoding
	signingInput := enc.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`)) + "." + enc.EncodeToString(claims)
	digest := sha256.Sum256([]byte(signingInput))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, digest[:])
	if err != nil {
		return "", fmt.Errorf("sign JWT: %w", err)
	}
	return signingInput + "." + enc.EncodeToString(sig), nil
}
