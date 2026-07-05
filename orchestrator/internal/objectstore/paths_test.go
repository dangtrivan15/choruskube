package objectstore

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestPrefixPath_WithOrgSlug(t *testing.T) {
	assert.Equal(t, "acme/runs/abc/run_log.md", PrefixPath("acme", "runs/abc/run_log.md"))
}

func TestPrefixPath_EmptyOrgSlug(t *testing.T) {
	assert.Equal(t, "runs/abc/run_log.md", PrefixPath("", "runs/abc/run_log.md"))
}

func TestPrefixPath_WithHyphenatedSlug(t *testing.T) {
	assert.Equal(t, "my-org/runs/abc/out/", PrefixPath("my-org", "runs/abc/out/"))
}
