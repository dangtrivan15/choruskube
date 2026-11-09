package executor

import (
	"crypto/sha256"
	"encoding/hex"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestGenerateJobSecret(t *testing.T) {
	secret, hash, err := GenerateJobSecret()
	require.NoError(t, err)

	assert.Len(t, secret, 64, "secret should be 32 bytes hex-encoded")
	assert.Len(t, hash, 64, "hash should be sha256 hex")

	h := sha256.Sum256([]byte(secret))
	assert.Equal(t, hex.EncodeToString(h[:]), hash)
}

func TestGenerateJobSecret_Unique(t *testing.T) {
	s1, _, _ := GenerateJobSecret()
	s2, _, _ := GenerateJobSecret()
	assert.NotEqual(t, s1, s2)
}

func TestBounds_Deadline(t *testing.T) {
	b := Bounds{ActivityTimeout: 10 * time.Minute, HeartbeatTimeout: time.Minute}
	assert.Equal(t, 10*time.Minute+LogGrace, b.Deadline())
}

func TestBounds_Retention(t *testing.T) {
	t.Run("setting above the floor wins", func(t *testing.T) {
		b := Bounds{HeartbeatTimeout: time.Minute}
		assert.Equal(t, time.Hour, b.Retention(time.Hour))
	})

	t.Run("setting below the floor is stamped as the floor", func(t *testing.T) {
		b := Bounds{HeartbeatTimeout: 120 * time.Second}
		assert.Equal(t, 120*time.Second+LogGrace, b.Retention(30*time.Second))
		assert.Equal(t, 240*time.Second, b.Retention(30*time.Second))
	})
}

func TestParseRetention(t *testing.T) {
	t.Run("empty defaults to one hour", func(t *testing.T) {
		d, err := ParseRetention("")
		require.NoError(t, err)
		assert.Equal(t, time.Hour, d)
	})

	t.Run("a valid duration parses", func(t *testing.T) {
		d, err := ParseRetention("90s")
		require.NoError(t, err)
		assert.Equal(t, 90*time.Second, d)
	})

	for _, raw := range []string{"0s", "-1m", "soon"} {
		t.Run(raw+" is an error", func(t *testing.T) {
			_, err := ParseRetention(raw)
			assert.Error(t, err)
		})
	}
}
