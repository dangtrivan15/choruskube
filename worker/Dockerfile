# Stage 1: Build. BUILD_BASE defaults to the stock Go toolchain image (the published
# build passes nothing); a deployment may override it — e.g. e2e points it at a warm
# build image whose Go module + compile caches are pre-baked at the paths below, so a
# from-scratch build reuses them instead of a cold `go build`. On the default base those
# dirs are simply empty (cold), unchanged from before. USER root so the build can write
# its workdir on either base (the warm base ends as a non-root user).
ARG BUILD_BASE=golang:1.25-alpine
FROM ${BUILD_BASE} AS build
USER root
ENV GOPATH=/home/agent/go GOMODCACHE=/home/agent/go/pkg/mod GOCACHE=/home/agent/.cache/go-build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o worker ./cmd/worker

# Stage 2: Production
FROM alpine:3.21
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=build /app/worker .
ENTRYPOINT ["./worker"]
