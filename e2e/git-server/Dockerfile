# e2e/git-server/Dockerfile — hermetic git remote for the e2e-test/* repos.
#
# `git` and `git-daemon` are installed HERE, at image build time, not in the
# container's runtime entrypoint. They used to be `apk add`-ed every time the
# stack came up (see git history) — that made every `docker compose up` depend
# on live network access to Alpine's package mirror from inside the compose
# network, on the container's default 5s-interval healthcheck budget, with no
# retry. When that fetch stalled (observed in practice: the `apk add` process
# blocked indefinitely on its download socket, never completing), the
# container sat "unhealthy" forever — nothing in the stack depends on
# git-server, so `docker compose up`/`e2eStackUp` still reported success, and
# the failure only surfaced ~90s later as an unrelated Playwright timeout
# waiting on a node that could never clone. Installing packages at build time
# instead means: it happens once (cached Docker layer), a fetch failure there
# fails `e2eImages` loudly and immediately instead of hanging a background
# health check, and the container's actual startup (seed-and-serve.sh) does
# no network I/O at all — matching every other custom-built service in
# docker-compose.e2e.yaml (api-server, orchestrator, web-ui), none of which
# install packages from their entrypoint.
FROM alpine:3.20

RUN apk add --no-cache git git-daemon

COPY seed-and-serve.sh /usr/local/bin/seed-and-serve.sh
RUN chmod +x /usr/local/bin/seed-and-serve.sh

ENTRYPOINT ["/usr/local/bin/seed-and-serve.sh"]
