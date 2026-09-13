# agent-images/choruskube-dind/Dockerfile
# Docker-in-Docker sidecar for agent pods: the stock dind daemon plus an optional baked
# warm-Docker-cache archive (see build-preload.sh) that entrypoint.sh loads at boot. Separate
# from choruskube-dev so non-docker agents never pull this image's weight.

# Warm Docker cache: PRELOAD_TAR names a build-preload.sh archive in the build context to bake
# where entrypoint.sh loads it. Dockerfile has no conditional COPY, and a bare `COPY ${PRELOAD_TAR}`
# fails when the ARG is unset (the non-warm default); selecting an empty vs populated scratch stage
# by ARG-substituted stage name stands in for one.
ARG PRELOAD_TAR=
ARG PRELOAD_MODE=${PRELOAD_TAR:+file}
FROM scratch AS preload-empty
FROM scratch AS preload-file
ARG PRELOAD_TAR
COPY ${PRELOAD_TAR} /stack.tar
FROM preload-${PRELOAD_MODE:-empty} AS preload-src

# Pin to 27: docker 29 defaults to the containerd image store, whose build steps
# ignore the daemon MTU and fail on reduced-MTU networks. Nothing here needs 29.
FROM docker:27-dind

# Warm Docker cache: empty on a non-warm build; entrypoint.sh treats no *.tar here as a cold-cache
# no-op. An image built FROM this one drops its delta archive here under a distinct name (both
# load) rather than overwriting stack.tar. This path is outside the base image's VOLUME
# /var/lib/docker, so the baked archive survives independently of the container's docker storage.
COPY --from=preload-src / /opt/choruskube/preload/

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
