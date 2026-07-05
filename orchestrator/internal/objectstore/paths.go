package objectstore

// PrefixPath prepends the org slug to an object storage key.
// Returns the key unchanged if orgSlug is empty (backward compatibility
// for runs started before org-prefixed paths were introduced).
func PrefixPath(orgSlug, key string) string {
	if orgSlug == "" {
		return key
	}
	return orgSlug + "/" + key
}
