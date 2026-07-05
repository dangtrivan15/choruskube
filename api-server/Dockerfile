# Stage 1: Dependency resolution (cached unless build config changes)
FROM gradle:9.2.1-jdk25-alpine AS deps
WORKDIR /app

# Build configuration — changes when dependencies change. This is a single-project
# standalone Gradle build (settings.gradle.kts declares one root project with no
# submodules); it produces the runnable OSS bootJar.
COPY settings.gradle.kts build.gradle ./

# Resolve dependencies to warm the cache layer
RUN --mount=type=cache,target=/root/.gradle/caches \
    gradle dependencies --no-daemon

# Stage 2: Build application (rebuilds on source change)
FROM deps AS build
COPY src src
RUN --mount=type=cache,target=/root/.gradle/caches \
    gradle bootJar --no-daemon

# Stage 3: Minimal production image
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
# The build produces both a plain library jar and the runnable fat jar
# (classifier `boot`); copy the boot jar specifically.
COPY --from=build /app/build/libs/*-boot.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
