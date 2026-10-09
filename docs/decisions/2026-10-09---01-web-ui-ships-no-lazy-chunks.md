# The web UI ships all of its JavaScript up front, in one `vendor` chunk, and the build fails if anything would load lazily

## Status

current

## Context

The web-UI image's server keeps only the current build's files; a deploy removes the previous
build's `dist/assets` entirely. Mermaid (and the libraries it in turn imports lazily — KaTeX for
diagram math, Cytoscape and its layout engines) ships about 50 chunks that are fetched only the
first time a given diagram type is drawn. Every one of those chunks imports the app's own entry
chunk, so any app-code change — even a one-character edit — renames all of them on rebuild. A
browser tab opened before a deploy therefore asks the new server for chunk file names that no
longer exist. nginx answered that miss with the SPA's `index.html` as a normal `200 text/html`
page, which surfaces in the tab as a "Failed to fetch dynamically imported module" error rather
than a clean failure.

Measured on this change, OSS build:
- Initial JS grows from 3.84 MB to 6.23 MB raw; gzip is 1.11–1.78 MB (Vite's estimate), and nginx's
  on-the-fly gzip actually sends 2.09 MB.
- DOMContentLoaded on a cold `/docs` load (median of 9 runs, Playwright Chromium) rises from 308 to
  460 ms (+152 ms); at 4× CPU throttling, from 918 to 1,334 ms (+416 ms). Main-thread script time
  rises by 89 ms (+335 ms throttled). Only full page loads pay this — client-side navigation does
  not.
- A deploy that changes no dependency and no library export the app uses renames only the app
  chunk; the `vendor` chunk's name, and its 5.86 MB, are untouched. That deploy re-downloads 0.37 MB
  raw (0.09 MB gzipped) rather than the whole bundle.

## Decision

- **One `vendor` chunk, loaded at page load, holds every third-party module.** `manualChunks`
  assigns every `node_modules` import that isn't CSS to `vendor`; CSS stays in the app's existing
  single stylesheet, in its existing order, so cascade order is unaffected — verified by a
  byte-identical build output. Nothing in the resulting bundle is fetched after the page has
  loaded, so a diagram renders in a tab regardless of how many deploys happened since it was
  opened: Mermaid's lazy `import()` calls resolve against code already in memory.
- **A build-time guard rejects anything that would still load lazily.** A Vite plugin walks the
  chunk graph reachable from the entry chunk and fails the build if any emitted chunk falls
  outside it, or if any emitted asset is a `.js`/`.mjs` file (the shape Vite gives a Web Worker or
  a `?url` script import — those never appear as chunks, so a chunk-only check misses them). The
  error names every offending file.
- **The serving contract is declared once and shared by both server configs.**
  The SPA shell (`index.html`, every SPA route) is `Cache-Control: no-cache` so it always
  revalidates. Everything under `/assets/` is `public, max-age=31536000, immutable` and served
  gzip-compressed, including to requests a reverse proxy forwards (nginx skips those by default);
  a miss under `/assets/` is a real `404`, never the shell. This is what turns
  the increased first-load size into a net transfer saving: the `vendor` chunk is fetched once per
  browser per dependency set, compressed, and cached for a year, while today's single entry chunk
  is resent uncompressed on every deploy.

## Alternatives rejected

- **Keep lazy chunks, retain old builds' files.** Every deploy renames roughly 2.4 MB of chunks;
  keeping them reachable needs either an image that carries prior builds' files forward or a
  shared store across deployments, plus pruning neither of those has today.
- **Reload the page automatically when a lazy chunk fails to fetch** (the `vite:preloadError`
  recipe). Rejected because a surface that holds a feedback box beside rendered diagrams would
  lose unsaved input to the reload, and the diagram still isn't visible until the reload
  completes.
- **Show a "reload to see this" prompt instead of reloading automatically.** Honest, but the
  diagram stays blank and the prompt recurs after every subsequent deploy.
- **Put only Mermaid in its own chunk.** Measured: KaTeX, which Mermaid itself imports lazily for
  math labels, remained a separately lazy-fetched chunk, so the failure mode wasn't actually
  closed.
- **Keep the lazy chunks but `<link rel="modulepreload">` all of them at page load**, so each sits
  in the tab's module map before any deploy. Measured cost at startup was small (DOMContentLoaded
  308→361 ms, or 918→941 ms at 4× throttling), but rejected because: every lazy chunk still imports
  the entry chunk, so a deploy still renames all ~50 of them and a returning visitor re-downloads
  the full ~6.2 MB (~2 MB gzipped) rather than `vendor`'s 0.37 MB; a preload failure (e.g. during a
  rollout overlap) is cached as a failure for the document's lifetime, leaving that diagram type
  broken until reload; and the build guard would have to parse the HTML's preload list instead of
  walking the chunk graph.
- **Compress and cache at the edge instead of in the app's own nginx config.** Rejected because it
  puts one application's policy into shared infrastructure, and a self-hosted deployment of the
  published image without that edge wouldn't get the behavior at all.
- **Precompress hashed assets at build time (`gzip_static`).** Measured on-the-fly gzip instead:
  it costs roughly 55 ms of CPU per `vendor` fetch, paid once per browser per `vendor` version
  because the response is then cached as immutable. Precompressing at the maximum level would
  save roughly 0.3 MB on `vendor`, not enough to justify a second build artifact per file in the
  image.

## Consequences

- Code-splitting the app's own code, or adding a Web Worker, needs a design for how a tab that
  spans a deploy gets the newer files first — the build guard blocks both until one exists.
- Only Vite's content-hashed build output may ever live under `/assets/`, because everything
  there is cached as immutable for a year.
- The immutable `Cache-Control` header is never sent on a `404` — a transient miss during a
  rollout must not stick in a browser for a year.
- The `vendor` chunk's name, and the bytes a returning visitor has to re-fetch, change only when a
  dependency changes or the app starts or stops using one of a library's exports — not on every
  deploy.
- Every full page load now evaluates every diagram renderer's code, a fixed ~0.15 s of extra
  script work on a desktop-class CPU with no budget enforcing it; only full loads pay this, not
  client-side navigation.
