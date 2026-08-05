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

# Pre-bake the Gradle wrapper distribution. The wrapper computes a deterministic hash
# from distributionUrl and looks for the unpacked dist at
# $GRADLE_USER_HOME/wrapper/dists/gradle-<version>-bin/<hash>/. Pre-populating the cache
# eliminates the per-run cold download that, in our environment, ran at ~100 KB/s under
# the wrapper's hard-coded -Xmx64m JVM and burned ~10-15 minutes per agent execution.
# Bump GRADLE_VERSION + GRADLE_WRAPPER_HASH together when bumping the wrapper in repos
# this image serves; the hash is the directory name observed at runtime.
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

# Install Docker Compose and Buildx plugins (Docker apt repo inherited from base image)
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
# (~/.gradle/caches/modules-2), so a CI job running in this image begins with the
# artifacts already on disk instead of re-downloading them per run.
#
# This exists because the obvious alternative is a trap. actions/setup-java's
# `cache: gradle` keys the cache on a hash of every *.gradle* file in the workspace and
# sets no restoreKeys at all — upstream cache.ts says so outright: "No restoreKeys is set,
# to start with a clear cache after dependency update". Editing a single line of a single
# build file therefore discards the ENTIRE cache and re-downloads every artifact; measured
# at ~205s of added CI wall-clock on one build-file commit. A cache baked into the image
# degrades gracefully instead: Gradle keeps everything still valid and fetches only the
# delta.
#
# Only the build files are copied — never sources — so this layer is invalidated by a
# dependency change rather than by every commit. api-server arrives as a named build
# context so the main context stays scoped to this directory (see build-images.yml).
#
# Placed LAST on purpose: build files change far more often than the JDK/Go/Playwright
# layers above, and a busted layer invalidates every layer after it. Keeping the warm-up
# at the end means a dependency bump rebuilds only this layer.
#
# Resolution goes straight to Maven Central. Do NOT thread a faster internal mirror
# through a --build-arg: build args are recorded in the image history, and this image is
# published to a public registry.
COPY gradle-warmup.init.gradle /warmup/
COPY --from=api-server gradlew settings.gradle.kts build.gradle /warmup/api-server/
COPY --from=api-server gradle /warmup/api-server/gradle
RUN cd /warmup/api-server \
    && GRADLE_USER_HOME=/home/agent/.gradle ./gradlew --no-daemon --console=plain \
         --init-script /warmup/gradle-warmup.init.gradle warmUpDependencies \
    && rm -rf /warmup /home/agent/.gradle/daemon /home/agent/.gradle/native \
    && chown -R 1000:1000 /home/agent/.gradle

USER 1000
