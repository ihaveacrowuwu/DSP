# Build the SPA, then serve it as static files behind nginx.
FROM node:22-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .

# Vite inlines VITE_* at build time, so these are build arguments.
#
# The map needs none of them: its geography is committed under public/basemap and
# is copied in with the rest of the build. The tile arguments exist only for
# someone who wants raster imagery underneath it, and default to empty so the
# image never reaches for a third-party tile server. An earlier version of this
# file defaulted to a public style URL, which quietly made the container depend on
# a service the project is not allowed to depend on.
ARG VITE_API_BASE_URL=""
ARG VITE_MAP_TILE_URL=""
ARG VITE_MAP_TILE_ATTRIBUTION=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_MAP_TILE_URL=$VITE_MAP_TILE_URL \
    VITE_MAP_TILE_ATTRIBUTION=$VITE_MAP_TILE_ATTRIBUTION

RUN npm run build

FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
