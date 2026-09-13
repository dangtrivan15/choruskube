# agent-images/choruskube-dev/Dockerfile
# Extends the base Claude Code agent with Java 25 and Go 1.25 for choruskube development.
# BASE_AGENT_IMAGE selects the base agent runtime to extend. Defaults to the public
# agent image; CI overrides it (via --build-arg) to build from a freshly-built agent.
ARG BASE_AGENT_IMAGE=registry.choruskube.com/claude-code:latest
FROM ${BASE_AGENT_IMAGE}

USER root

# Install Java 25 (Eclipse Temurin)
RUN apt-get update && apt-get install -y --no-install-recommends wget apt-transport-https gpg \
    && wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg \
    && echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb bookworm main" \
    | tee /etc/apt/sources.list.d/adoptium.list \
    && apt-get update && apt-get install -y --no-install-recommends temurin-25-jdk \
    && rm -rf /var/lib/apt/lists/*

# Pre-bake the Gradle wrapper distribution. The wrapper looks for the unpacked dist at
# $GRADLE_USER_HOME/wrapper/dists/gradle-<version>-bin/<hash>/, so pre-populating that path
# eliminates a slow per-run cold download. Bump GRADLE_VERSION and GRADLE_WRAPPER_HASH together
# when bumping the wrapper; the hash is the dist directory name observed at runtime.
ARG GRADLE_VERSION=9.2.1
ARG GRADLE_WRAPPER_HASH=2t0n5ozlw9xmuyvbp7dnzaxug
RUN install -d -o 1000 -g 1000 /home/agent/.gradle \
    && DIR=/home/agent/.gradle/wrapper/dists/gradle-${GRADLE_VERSION}-bin/${GRADLE_WRAPPER_HASH} \
    && mkdir -p ${DIR} \
    && curl -fsSL -o ${DIR}/gradle-${GRADLE_VERSION}-bin.zip \
        https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip \
    && (cd ${DIR} && unzip -q gradle-${GRADLE_VERSION}-bin.zip) \
    && touch ${DIR}/gradle-${GRADLE_VERSION}-bin.zip.ok \
    && chown -R 1000:1000 /home/agent/.gradle

# Install Go 1.25. TARGETARCH (provided by the builder: amd64 / arm64) maps 1:1 to
# Go's release naming, so the image builds natively on both architectures.
ARG TARGETARCH
RUN curl -fsSL https://go.dev/dl/go1.25.0.linux-${TARGETARCH}.tar.gz | tar -C /usr/local -xz
ENV PATH="/usr/local/go/bin:${PATH}"

# Docker Compose + Buildx plugins (Docker apt repo inherited from the base image)
RUN apt-get update && apt-get install -y --no-install-recommends \
    docker-compose-plugin \
    docker-buildx-plugin \
    && rm -rf /var/lib/apt/lists/*

# Install Playwright globally and its Chromium system dependencies
# Use shared system path so browsers are accessible to all users (agent runs as uid 1000)
ENV PLAYWRIGHT_BROWSERS_PATH=/usr/lib/ms-playwright
RUN npm install -g @playwright/test@1.58.2 \
    && playwright install-deps chromium \
    && playwright install chromium

# Pre-resolve the api-server dependency graph into the Gradle module cache
# (~/.gradle/caches/modules-2) so a CI job in this image starts with the artifacts on disk.
# Prefer this to actions/setup-java's `cache: gradle`, which sets no restoreKeys, so editing one
# build file discards the whole cache; a baked cache instead fetches only the delta.
#
# Only build files are copied, never sources, so a dependency change invalidates this layer, not
# every commit. api-server arrives as a named build context so the main context stays scoped to
# this directory (see build-images.yml). Placed LAST: build files change more often than the
# JDK/Go/Playwright layers, and a rebuilt layer invalidates every layer after it.
#
# Resolution goes straight to Maven Central. Do NOT thread a faster internal mirror through a
# --build-arg: build args are recorded in the image history, and this image is published publicly.
COPY gradle-warmup.init.gradle /warmup/
COPY --from=api-server gradlew settings.gradle.kts build.gradle /warmup/api-server/
COPY --from=api-server gradle /warmup/api-server/gradle
RUN cd /warmup/api-server \
    && GRADLE_USER_HOME=/home/agent/.gradle ./gradlew --no-daemon --console=plain \
         --init-script /warmup/gradle-warmup.init.gradle warmUpDependencies \
    && rm -rf /warmup /home/agent/.gradle/daemon /home/agent/.gradle/native \
    && chown -R 1000:1000 /home/agent/.gradle

# Pre-resolve AND pre-compile the Go module + build caches, so a CI job reuses compiled
# stdlib+deps+packages instead of a full cold `go build` (the compile, not the download, dominates
# a from-scratch build). orchestrator and worker are separate modules, warmed independently. Only
# the sources needed to compile are copied; a stale cache stays correct, since Go content-hashes
# every input. Placed LAST like the Gradle warm-up: source changes must not invalidate the layers
# above. Resolution goes straight to the public proxy — never thread a mirror through a --build-arg
# (it lands in this public image's history).
ENV GOPATH=/home/agent/go GOMODCACHE=/home/agent/go/pkg/mod GOCACHE=/home/agent/.cache/go-build
COPY --from=orchestrator . /warmup/orchestrator/
COPY --from=worker . /warmup/worker/
RUN cd /warmup/orchestrator && go mod download && CGO_ENABLED=0 go build ./... \
    && cd /warmup/worker && go mod download && CGO_ENABLED=0 go build ./... \
    && rm -rf /warmup \
    && chown -R 1000:1000 /home/agent/go /home/agent/.cache

# Warm npm's cache for web-ui development. NPM_CONFIG_CACHE is an ENV (not inline)
# so a job that overrides HOME still finds it; only the lockfile is copied, so a UI
# source change does not rebuild this layer. Placed LAST, like the Go warm-up above.
ENV NPM_CONFIG_CACHE=/home/agent/.npm
COPY --from=web-ui package.json package-lock.json /warmup/web-ui/
RUN cd /warmup/web-ui && npm ci --no-audit --no-fund \
    && rm -rf /warmup \
    && chown -R 1000:1000 /home/agent/.npm

USER 1000
