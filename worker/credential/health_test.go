package credential

import (
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
)

func TestNotHeldReportCoversEveryKind(t *testing.T) {
	now := time.Date(2026, 11, 4, 12, 0, 0, 0, time.UTC)

	got := NotHeldReport(now)

	assert.Len(t, got, 3)
	seen := map[Kind]bool{}
	for _, r := range got {
		assert.Equal(t, StatusNotHeld, r.Status)
		assert.Equal(t, now, r.CheckedAt)
		assert.Empty(t, r.Detail)
		seen[r.Kind] = true
	}
	assert.True(t, seen[KindGitHub])
	assert.True(t, seen[KindClaude])
	assert.True(t, seen[KindRegistry])
}

func TestNotHeldReportOrdersKindsGitHubClaudeRegistry(t *testing.T) {
	got := NotHeldReport(time.Now())

	assert.Equal(t, []Kind{KindGitHub, KindClaude, KindRegistry}, []Kind{got[0].Kind, got[1].Kind, got[2].Kind})
}

func TestTruncateDetailLeavesAShortStringAlone(t *testing.T) {
	assert.Equal(t, "short detail", truncateDetail("short detail"))
}

func TestTruncateDetailNeverExceedsTheCap(t *testing.T) {
	// Multi-byte runes: a byte-based cap would either cut mid-rune or allow more runes than the
	// contract permits, so the cap is asserted in runes, not bytes.
	long := strings.Repeat("é", MaxHealthDetail+100)

	got := truncateDetail(long)

	assert.LessOrEqual(t, len([]rune(got)), MaxHealthDetail)
	assert.Equal(t, MaxHealthDetail, len([]rune(got)))
	assert.True(t, strings.HasPrefix(long, got))
}

func TestTruncateDetailAtExactlyTheCapIsUnchanged(t *testing.T) {
	exact := strings.Repeat("x", MaxHealthDetail)
	assert.Equal(t, exact, truncateDetail(exact))
}
