# The documentation site, as served on doc.ec1.mateu.io (deploy/manifests/81-docs.yaml).
#
# Two stages: Node builds the static site (Astro + Starlight, `npm run build` → dist/), and nginx
# serves it. Nothing of Node reaches the image that runs.
#
# nginx runs unprivileged — as its own user, on 8080, with its pid and temp files under /tmp — so the
# pod keeps the same securityContext as every other workload here (non-root, read-only root
# filesystem, /tmp an emptyDir). See nginx.conf.
#
#   docker buildx build --platform linux/amd64 -t miguelperezcolom/ec-demo1-docs:0.1.0 --push doc

# The site is the same for every platform: built once, on the builder's own.
FROM --platform=$BUILDPLATFORM node:24-slim AS build
WORKDIR /site
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /site/dist /usr/share/nginx/html
USER 101
EXPOSE 8080
