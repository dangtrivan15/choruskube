package activity

import "testing"

func TestPrefixPath_WithOrgSlug(t *testing.T) {
	if got := prefixPath("acme", "runs/abc/run_log.md"); got != "acme/runs/abc/run_log.md" {
		t.Fatalf("prefixPath = %q, want %q", got, "acme/runs/abc/run_log.md")
	}
}

func TestPrefixPath_EmptyOrgSlug(t *testing.T) {
	if got := prefixPath("", "runs/abc/run_log.md"); got != "runs/abc/run_log.md" {
		t.Fatalf("prefixPath = %q, want %q", got, "runs/abc/run_log.md")
	}
}

func TestPrefixPath_WithHyphenatedSlug(t *testing.T) {
	if got := prefixPath("my-org", "runs/abc/out/"); got != "my-org/runs/abc/out/" {
		t.Fatalf("prefixPath = %q, want %q", got, "my-org/runs/abc/out/")
	}
}
