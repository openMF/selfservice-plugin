# =============================================================================
# Mifos Self Service Plugin — Documentation Dockerfile
# Multi-stage: Node.js build stage + Nginx Alpine runtime stage
# Target: https://docs.mifos.community
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Build the Astro + Starlight static site
# -----------------------------------------------------------------------------
FROM node:22-alpine AS builder

WORKDIR /app

# Install dependencies first (layer caching)
COPY package.json package-lock.json* ./
RUN npm ci --no-audit --no-fund

# Copy source and build
COPY . .
RUN npm run build

# -----------------------------------------------------------------------------
# Stage 2: Nginx Alpine runtime
# -----------------------------------------------------------------------------
FROM nginx:1.27-alpine AS runner

# Install timezone data (optional, for logs) and curl (for healthcheck)
RUN apk add --no-cache tzdata curl

# Create a non-root user for Nginx worker processes
RUN addgroup -g 1001 -S nodejs && \
    adduser -S nodejs -u 1001 -G nodejs

# Copy custom full Nginx configuration (replaces /etc/nginx/nginx.conf)
COPY docker/nginx.conf /etc/nginx/nginx.conf

# Copy built static assets from builder stage
COPY --from=builder /app/dist /usr/share/nginx/html

# Set ownership of static assets and pre-create nginx cache dirs so the
# non-root worker can write cache/pid files without root privileges
RUN chown -R nodejs:nodejs /usr/share/nginx/html && \
    chmod -R 755 /usr/share/nginx/html && \
    mkdir -p /var/cache/nginx/client_temp /var/cache/nginx/proxy_temp \
             /var/cache/nginx/fastcgi_temp /var/cache/nginx/uwsgi_temp \
             /var/cache/nginx/scgi_temp && \
    chown -R nodejs:nodejs /var/cache/nginx && \
    chmod -R 755 /var/cache/nginx

# Nginx master binds port 8080 (>1024, non-root friendly); workers run as nodejs
USER nodejs

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 --start-period=5s \
  CMD curl -f http://localhost:8080/healthz || exit 1

CMD ["nginx", "-g", "daemon off;"]
