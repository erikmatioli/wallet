# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
# Lockfile first: dependency layer is cached until package*.json changes.
# `npm ci` (not `npm install`) installs exactly what the lockfile pins, and never re-resolves
# the dependency tree - which also sidesteps an npm 10 resolver bug hit on fresh installs of
# Angular 21's Vitest peer-dependency graph.
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- runtime ----
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/wallet-console/browser /usr/share/nginx/html
EXPOSE 80
