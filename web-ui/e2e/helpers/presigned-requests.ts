/**
 * presigned-requests.ts — pure URL builders for `specs/presigned-request-contract.spec.ts`, unit
 * tested without a stack. Every builder works from the minted URL's own bucket and the agent's own
 * `output_path`, never a literal bucket name or host, so the spec that uses them runs unchanged
 * against either e2e stack that composes over this one.
 */

/** The first path segment of a path-style presigned URL -- the bucket the store routes on. */
export function bucketOf(signedUrl: string): string {
  const match = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/?#]+\/([^/?#]+)/.exec(signedUrl);
  if (!match) {
    throw new Error("signed URL has no bucket segment");
  }
  return match[1];
}

/**
 * A key inside `ownTree` whose tail spells `/<bucket>/<targetKey>` -- so presigning it is a
 * request for a key in the caller's own run, and sending it to `targetKey`'s path with the prefix
 * from {@link forwardingPrefixFor} rebuilds exactly the path this key was signed for.
 */
export function retargetKey(ownTree: string, targetKey: string, bucket: string): string {
  return `${ownTree}x/${bucket}/${targetKey}`;
}

/** The `X-Forwarded-Prefix` that, joined to a target path, rebuilds {@link retargetKey}'s signed path. */
export function forwardingPrefixFor(ownTree: string, bucket: string): string {
  return `/${bucket}/${ownTree}x`;
}

/**
 * `signedUrl` with its path replaced by `path` -- same scheme, authority and raw query, taken as
 * substrings of the original rather than through a URL parser, so neither is altered by
 * normalisation.
 */
export function retargetUrl(signedUrl: string, path: string): string {
  const match = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^/?#]+/.exec(signedUrl);
  if (!match) {
    throw new Error("signed URL has no scheme and authority");
  }
  const schemeAndAuthority = match[0];
  const queryIndex = signedUrl.indexOf("?");
  const rawQuery = queryIndex === -1 ? "" : signedUrl.slice(queryIndex);
  return `${schemeAndAuthority}${path}${rawQuery}`;
}
