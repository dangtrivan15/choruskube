# Stage 1: Build
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
# Retry transient registry network resets (ECONNRESET) — generic, no registry host pinned.
RUN npm ci --fetch-retries=5 --fetch-retry-mintimeout=10000 --fetch-retry-maxtimeout=120000
COPY . .
RUN npm run build

# Stage 2: Serve with nginx
FROM nginx:alpine AS production

# Install gettext for envsubst
RUN apk add --no-cache gettext

COPY --from=build /app/dist /usr/share/nginx/html
COPY src/config.js.template /config.js.template

COPY <<'EOF' /etc/nginx/conf.d/default.conf
server {
    listen 80;
    root /usr/share/nginx/html;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    # Don't cache config.js — environment-specific
    location = /config.js {
        add_header Cache-Control "no-store, no-cache, must-revalidate";
        expires -1;
    }
}
EOF

# Copy E2E nginx config for optional use via volume mount or COPY override
COPY nginx-e2e.conf /etc/nginx/conf.d/e2e.conf.template

EXPOSE 80

# Render config.js from environment variables, then start nginx
# Uses CMD (not ENTRYPOINT) so docker-compose `command` can override the startup
CMD ["/bin/sh", "-c", "envsubst < /config.js.template > /usr/share/nginx/html/config.js && exec nginx -g 'daemon off;'"]
