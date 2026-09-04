package executor

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGenerateJobSecret(t *testing.T) {
	secret, hash, err := GenerateJobSecret()
	require.NoError(t, err)

	assert.Len(t, secret, 64, "secret should be 32 bytes hex-encoded")
	assert.Len(t, hash, 64, "hash should be sha256 hex")

	// Hash matches the secret
	h := sha256.Sum256([]byte(secret))
	assert.Equal(t, hex.EncodeToString(h[:]), hash)
}

func TestGenerateJobSecret_Unique(t *testing.T) {
	s1, _, _ := GenerateJobSecret()
	s2, _, _ := GenerateJobSecret()
	assert.NotEqual(t, s1, s2)
}
