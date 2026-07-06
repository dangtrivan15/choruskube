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

# Same-origin proxy config, staged (not auto-loaded). When NGINX_PROXY_API=true
# the CMD swaps it in as default.conf so the SPA can reach the API same-origin.
COPY nginx-proxy.conf /etc/nginx/nginx-proxy.conf

EXPOSE 80

# Self-composing startup: the image serves SPA-only by default (for deployments where
# the API is reached on a different origin over an absolute URL). Set NGINX_PROXY_API=true
# (the Docker Compose stacks do) to swap in the reverse-proxy config so the SPA reaches
# the API same-origin. Then render config.js and run nginx — no `command:` override needed.
CMD ["/bin/sh", "-c", "if [ \"${NGINX_PROXY_API:-false}\" = \"true\" ]; then cp /etc/nginx/nginx-proxy.conf /etc/nginx/conf.d/default.conf; fi; envsubst < /config.js.template > /usr/share/nginx/html/config.js && exec nginx -g 'daemon off;'"]
