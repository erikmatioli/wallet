# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
# Lockfile first: the dependency layer is cached until package*.json changes. `npm ci` installs exactly
# what the lockfile pins (same reason as wallet-console's Dockerfile).
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- runtime ----
FROM nginx:1.27-alpine
# The template becomes /etc/nginx/conf.d/default.conf at start, with APP_API_UPSTREAM filled in.
COPY docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build /app/dist/wallet-mobile/browser /usr/share/nginx/html
ENV APP_API_UPSTREAM=http://app-api:8083
EXPOSE 80
