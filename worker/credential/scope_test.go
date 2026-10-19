package credential

import (
	"errors"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/dangtrivan15/choruskube/worker/github"
)

// runPermissions mirrors the run permission list the API server's scope route hands out, for
// tests that only care about repository narrowing.
var runPermissions = map[string]string{
	"actions": "read", "checks": "read", "contents": "write", "issues": "write",
	"metadata": "read", "pull_requests": "write", "statuses": "read", "workflows": "write",
}

// acmeInstallation grants every run permission, so a repository-narrowing test is not
// incidentally refused by the permission step it is not testing.
func acmeInstallation() github.Installation {
	return github.Installation{Account: "acme", Permissions: runPermissions}
}

// The narrowing rule's case table — kept identical to GitHubTokenScopeTest.java's, since both
// languages must drop and cap exactly the same way.

func TestNarrow_OwnerMatchMixedCase_KeepsBothBareNames(t *testing.T) {
	req, err := narrow(acmeInstallation(), []string{"Acme/API", "acme/web"}, runPermissions)

	require.NoError(t, err)
	assert.Equal(t, []string{"API", "web"}, req.Repositories)
}

func TestNarrow_ForeignOwner_IsDropped(t *testing.T) {
	req, err := narrow(acmeInstallation(), []string{"acme/api", "other/tools"}, runPermissions)

	require.NoError(t, err)
	assert.Equal(t, []string{"api"}, req.Repositories)
}

func TestNarrow_AllForeign_Refuses(t *testing.T) {
	_, err := narrow(acmeInstallation(), []string{"other/tools"}, runPermissions)

	require.Error(t, err)
	assert.True(t, errors.Is(err, ErrNoReachableRepository))
	assert.Contains(t, err.Error(), "other/tools")
	assert.Contains(t, err.Error(), "acme")
}

func TestNarrow_Duplicates_SameCase_Deduplicated(t *testing.T) {
	req, err := narrow(acmeInstallation(), []string{"acme/api", "ACME/api"}, runPermissions)

	require.NoError(t, err)
	assert.Equal(t, []string{"api"}, req.Repositories)
}

func TestNarrow_DuplicateNamesDifferingInCase_KeepsTheSpellingThatSortsFirst(t *testing.T) {
	req, err := narrow(acmeInstallation(), []string{"acme/api", "acme/API"}, runPermissions)

	require.NoError(t, err)
	assert.Equal(t, []string{"API"}, req.Repositories)
}

func TestNarrow_Empty_Refuses(t *testing.T) {
	_, err := narrow(acmeInstallation(), nil, runPermissions)

	require.Error(t, err)
}

func TestNarrow_MalformedEntry_IsIgnored(t *testing.T) {
	req, err := narrow(acmeInstallation(), []string{"acme", "acme/a/b", "acme/api"}, runPermissions)

	require.NoError(t, err)
	assert.Equal(t, []string{"api"}, req.Repositories)
}

// Permission narrowing.

func TestNarrow_GrantedRead_RequestsRead_BelowDesiredWrite(t *testing.T) {
	inst := github.Installation{Account: "acme", Permissions: map[string]string{"contents": "read", "metadata": "read"}}
	req, err := narrow(inst, []string{"acme/api"}, runPermissions)

	require.NoError(t, err)
	assert.Equal(t, "read", req.Permissions["contents"])
}

func TestNarrow_GrantedAdmin_RequestsTheLowerDesiredWrite(t *testing.T) {
	inst := github.Installation{Account: "acme", Permissions: map[string]string{"contents": "admin", "metadata": "read"}}
	req, err := narrow(inst, []string{"acme/api"}, runPermissions)

	require.NoError(t, err)
	assert.Equal(t, "write", req.Permissions["contents"])
}

func TestNarrow_NotGranted_IsOmitted(t *testing.T) {
	inst := github.Installation{Account: "acme", Permissions: map[string]string{"contents": "write", "metadata": "read"}}
	req, err := narrow(inst, []string{"acme/api"}, runPermissions)

	require.NoError(t, err)
	_, ok := req.Permissions["workflows"]
	assert.False(t, ok)
}

func TestNarrow_GrantedAdministration_IsNeverRequested(t *testing.T) {
	inst := github.Installation{Account: "acme", Permissions: map[string]string{
		"contents": "write", "metadata": "read", "administration": "write",
	}}
	req, err := narrow(inst, []string{"acme/api"}, runPermissions)

	require.NoError(t, err)
	_, ok := req.Permissions["administration"]
	assert.False(t, ok)
}

func TestNarrow_EmptyGrantedPermissions_Refuses(t *testing.T) {
	_, err := narrow(github.Installation{Account: "acme"}, []string{"acme/api"}, runPermissions)

	require.Error(t, err)
}

func TestNarrow_GrantedOnlyUnknownLevels_Refuses(t *testing.T) {
	inst := github.Installation{Account: "acme", Permissions: map[string]string{"contents": "bogus"}}
	_, err := narrow(inst, []string{"acme/api"}, runPermissions)

	require.Error(t, err)
}
