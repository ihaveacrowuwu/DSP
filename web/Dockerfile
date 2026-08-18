# Build the SPA, then serve it as static files behind nginx.
FROM node:22-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .

# Vite inlines VITE_* at build time, so the API URL is a build argument.
ARG VITE_API_BASE_URL=""
ARG VITE_MAP_STYLE_URL="https://tiles.openfreemap.org/styles/positron"
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_MAP_STYLE_URL=$VITE_MAP_STYLE_URL

RUN npm run build

FROM nginx:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
