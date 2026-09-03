package worker

import (
	"fmt"
	"strings"
)

// ParseCapabilitiesEnv is parseCapabilities exported for cmd/worker, which lives in a separate
// package and so cannot reach the unexported form the tests in this package exercise directly.
func ParseCapabilitiesEnv(raw string) (map[string]string, error) {
	return parseCapabilities(raw)
}

// parseCapabilities reads a "key=value,key=value" setting into the map a Worker reports at
// registration. A server that records required capabilities on a Fleet rejects a Worker that
// does not report them, so a silently dropped entry here surfaces as a refused registration
// with no indication which entry was lost -- hence a malformed pair is an error, not a skip.
func parseCapabilities(raw string) (map[string]string, error) {
	out := map[string]string{}
	for _, pair := range strings.Split(raw, ",") {
		pair = strings.TrimSpace(pair)
		if pair == "" {
			continue
		}
		k, v, ok := strings.Cut(pair, "=")
		k = strings.TrimSpace(k)
		if !ok || k == "" {
			return nil, fmt.Errorf("capability %q is not key=value", pair)
		}
		out[k] = strings.TrimSpace(v)
	}
	return out, nil
}
