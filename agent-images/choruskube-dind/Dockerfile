# agent-images/choruskube-dind/Dockerfile
# Docker-in-Docker sidecar for agent pods: the stock dind daemon, plus an optional
# baked warm-Docker-cache archive (see build-preload.sh) that entrypoint.sh loads
# at boot before signaling readiness. Kept separate from choruskube-dev so a
# non-docker agent, and the CI builder that produces it, never pull this image's
# weight in.

# Warm Docker cache: PRELOAD_TAR names a build-preload.sh archive, in the build
# context, to bake at the path entrypoint.sh loads at boot. A bare
# `COPY ${PRELOAD_TAR} ...` fails the build when the ARG is unset (the default, for
# a non-warm build), and Dockerfile has no conditional-COPY syntax; picking between
# an empty and a populated scratch stage by ARG-substituted stage name stands in for
# one, without duplicating the dind base image below.
ARG PRELOAD_TAR=
ARG PRELOAD_MODE=${PRELOAD_TAR:+file}
FROM scratch AS preload-empty
FROM scratch AS preload-file
ARG PRELOAD_TAR
COPY ${PRELOAD_TAR} /stack.tar
FROM preload-${PRELOAD_MODE:-empty} AS preload-src

FROM docker:29-dind

# Warm Docker cache: no-op (empty directory) on a non-warm build; entrypoint.sh's
# load_preload_archives() sweeps this directory and treats no *.tar as a cold-cache
# no-op, not a failure. An image built FROM this one drops its own delta archive here
# under a distinct name (both load) rather than overwriting stack.tar. The base image's
# VOLUME /var/lib/docker doesn't cover this path, so the baked archive survives
# independently of the container's docker storage volume.
COPY --from=preload-src / /opt/choruskube/preload/

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
