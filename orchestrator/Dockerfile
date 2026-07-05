# Stage 1: Build
FROM golang:1.25-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o orchestrator .

# Stage 2: Production
FROM alpine:3.21
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=build /app/orchestrator .
EXPOSE 9090
ENTRYPOINT ["./orchestrator"]
