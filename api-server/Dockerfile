# Stage 1: Dependency resolution (cached unless build config changes). BUILD_BASE defaults to
# the stock Gradle+JDK image (the published build passes nothing); e2e overrides it to a warm
# dev base whose Gradle wrapper dist + module cache are pre-baked at GRADLE_USER_HOME below, so
# a from-scratch build reuses them with no downloads. On the default base that dir starts empty
# (cold), unchanged from before. USER root so the build can write its workdir on either base
# (the warm base ends as a non-root user). Prefer the base's installed `gradle` when present: the
# default Gradle image already ships the pinned distribution on PATH, so using it skips a redundant
# ~130MB wrapper re-download of that same version — a large transfer that intermittently resets
# mid-download in CI. Fall back to ./gradlew for the warm base (no `gradle` on PATH, baked wrapper
# dist). BUILD_BASE's gradle must stay the same version as the wrapper's pinned distribution. A
# BuildKit cache mount is omitted because it would mask the warm base's baked module cache.
ARG BUILD_BASE=gradle:9.2.1-jdk25-alpine
FROM ${BUILD_BASE} AS deps
USER root
ENV GRADLE_USER_HOME=/home/agent/.gradle
WORKDIR /app

# Build configuration — changes when dependencies change. This is a single-project
# standalone Gradle build (settings.gradle.kts declares one root project with no
# submodules); it produces the runnable OSS bootJar. The wrapper (gradlew + gradle/) is
# copied so the ./gradlew fallback works on a base that has no system `gradle`.
COPY gradlew ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle ./

# Resolve dependencies to warm the cache layer
RUN gradle=$(command -v gradle || echo ./gradlew) && "$gradle" dependencies --no-daemon

# Stage 2: Build application (rebuilds on source change)
FROM deps AS build
COPY src src
RUN gradle=$(command -v gradle || echo ./gradlew) && "$gradle" bootJar --no-daemon

# Stage 3: Minimal production image
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# The build produces both a plain library jar and the runnable fat jar
# (classifier `boot`); copy the boot jar specifically.
COPY --from=build /app/build/libs/*-boot.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
