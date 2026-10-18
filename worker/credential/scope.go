package credential

import (
	"errors"
	"fmt"
	"sort"
	"strings"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// ErrNoReachableRepository means a run's scope held at least one repository, but the installation
// account about to mint owns none of them — wrapped with the dropped names and the account, since
// that is what an operator needs to add the repository to the installation's selection.
var ErrNoReachableRepository = errors.New("no repository in the run's scope belongs to this GitHub App installation")

// levelRank/rankLevel: read=1 < write=2 < admin=3 — how narrow caps a requested permission at
// what the installation grants. Must match the API server's GitHubTokenScope exactly: both sides
// implement the same narrowing rule independently (see docs/decisions for why it is not shared
// code), and a mismatch here would let one side mint a scope the other would refuse.
var levelRank = map[string]int{"read": 1, "write": 2, "admin": 3}
var rankLevel = map[int]string{1: "read", 2: "write", 3: "admin"}

// narrow applies the narrowing rule now that inst's account and grants are known: repositories
// under another owner are dropped, the rest reduced to a bare name (de-duplicated
// case-insensitively, keeping whichever spelling sorts first in byte order) and sorted; each
// permission is requested at the lower of desired and granted, a permission the installation does
// not grant is omitted. Either an empty repository list or an empty result at any step is an
// error — GitHub reads a missing field as "every repository" or "every permission".
func narrow(inst github.Installation, repositories []string, permissions map[string]string) (github.TokenRequest, error) {
	if len(repositories) == 0 {
		return github.TokenRequest{}, errors.New("no repository in this run's scope")
	}

	namesByLower := make(map[string]string)
	var dropped []string
	for _, repo := range repositories {
		parts := strings.Split(repo, "/")
		if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
			// Never produced by the API server's scope route; ignored defensively rather than
			// treated as a foreign-owner drop, since there is no owner here to name.
			continue
		}
		owner, name := parts[0], parts[1]
		if !strings.EqualFold(owner, inst.Account) {
			dropped = append(dropped, repo)
			continue
		}
		key := strings.ToLower(name)
		if existing, ok := namesByLower[key]; !ok || name < existing {
			namesByLower[key] = name
		}
	}

	if len(namesByLower) == 0 {
		sort.Strings(dropped)
		return github.TokenRequest{}, fmt.Errorf("%w: dropped %v (installation account %q)", ErrNoReachableRepository, dropped, inst.Account)
	}

	names := make([]string, 0, len(namesByLower))
	for _, name := range namesByLower {
		names = append(names, name)
	}
	sort.Strings(names)

	capped := make(map[string]string)
	for permission, desired := range permissions {
		desiredRank, ok := levelRank[desired]
		if !ok {
			continue
		}
		grantedLevel, ok := inst.Permissions[permission]
		if !ok {
			continue
		}
		grantedRank, ok := levelRank[grantedLevel]
		if !ok {
			continue
		}
		rank := desiredRank
		if grantedRank < rank {
			rank = grantedRank
		}
		capped[permission] = rankLevel[rank]
	}
	if len(capped) == 0 {
		return github.TokenRequest{}, fmt.Errorf("no permission this installation grants for account %q", inst.Account)
	}

	return github.TokenRequest{Repositories: names, Permissions: capped}, nil
}
