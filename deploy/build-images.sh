#!/usr/bin/env bash
# Builds and pushes the images this repository owns: the four shells, the gateway, the four
# demo services, the IA control plane and the pod that serves catalogued APIs as MCP servers — and
# the documentation site (doc/), last, with no Maven step.
#
# Four shell images from two modules: each console is served by a Vaadin one and a Redwood one,
# differing only in which Mateu frontend artifact the build puts on the classpath. consoles/shell
# builds ec-demo1-shell by default and ec-demo1-shell-redwood with -Predwood (control-shell the
# same). They render the same backends through different renderers, which is the point — see
# deploy/manifests/31-shell-redwood.yaml.
#
# Everything else runs from published images — the engine's orchestrator/forms/rules/worker from
# Docker Hub, Keycloak from Quay, Postgres and Redpanda from their own registries.
#
#   ./deploy/build-images.sh TAG          # one tag for every image; DOCS_TAG for the site
#
# linux/amd64 and linux/arm64 (PLATFORMS): ec1 pins every workload to amd64 — the Karpenter pool can
# provision arm64, and its manifests say which one they want — while deploy/local/up.sh on an Apple
# Silicon Mac runs an arm64 node. The images only copy a jar onto a multi-arch JRE, so the second
# platform costs a few seconds, not a second build.
set -euo pipefail
cd "$(dirname "$0")/.."

REGISTRY="${REGISTRY:-miguelperezcolom}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
TAG="${1:?usage: build-images.sh TAG — one tag for every image (there is no default: a forgotten one tagged everything 0.8.0)}"

# By path: the modules are grouped by system (README, "Layout"). The image is named after the
# module's folder — systems/erp builds ec-demo1-erp. The shells in this list build twice: see
# RENDERED_TWICE below.
APPS="consoles/shell consoles/gateway systems/crs/booking supporting/content control-plane/users ai/ia-agent ai/ia-control-plane ai/api-mcp consoles/control-shell systems/erp integration/crs-integration-service integration/mapping-service integration/pms-integration-service control-plane/communication-service control-plane/integrations-service integration/customer-mdm-service systems/front-office systems/notices systems/customer-history systems/loyalty control-plane/registration-rules control-plane/audit-service integration/journey-service"

# grpc-interface first, and installed rather than packaged: it is not an application and gets no
# image, but `users` compiles against the protobuf stubs generated from its .proto, so it has to
# be in the local repository before that module is built. It is also the one module that reaches
# the network for something other than dependencies — the protobuf plugin downloads protoc.
echo "── installing grpc-interface (the stubs users compiles against) ──"
( cd control-plane/grpc-interface && mvn -B -ntp -DskipTests install )

# The contracts likewise: the published-language libraries the services compile against, one per
# context, and contracts-testing, their tests' schema tooling (contracts/README.md).
echo "── installing the contracts (the language the services speak) ──"
( cd contracts && mvn -B -ntp -DskipTests install )

# messaging likewise: the outbox, its relay and the inbox every service with a database shares.
echo "── installing messaging (the shared outbox and inbox) ──"
( cd supporting/messaging && mvn -B -ntp -DskipTests install )
# agent-sql: the read-only SQL over each service's agent views (doc/src/content/docs/ia/datos-del-agente.md).
echo "── installing agent-sql (the agents' read-only SQL) ──"
( cd supporting/agent-sql && mvn -B -ntp -DskipTests install )

# demo-reset likewise: the reset@1 task every service serves for the demo's reset (process reset-demo).
echo "── installing demo-reset (each service's own reset) ──"
( cd supporting/demo-reset && mvn -B -ntp -DskipTests install )

# ui-commons likewise: the shells, the front office and the control-plane UIs compile against it.
echo "── installing ui-commons (what the UIs share) ──"
( cd supporting/ui-commons && mvn -B -ntp -DskipTests install )

# The shells: one module per console, one image per renderer. The default build is the Vaadin
# image, named after the folder; -Predwood is the Redwood one, the same name plus -redwood. The
# image names are what the manifests pull, and have not changed since these were four modules.
RENDERED_TWICE="consoles/shell consoles/control-shell"

build_and_push() {  # <module dir> <image name> [maven args...]
  local app="$1" image="$2"; shift 2
  # Built outside the image on purpose: the Dockerfiles copy target/*.jar, so Maven's cache works
  # and a code change does not re-resolve the whole dependency tree.
  #
  # `clean` is not paranoia, and it is the expensive half of that trade for a reason.
  #
  # Mateu's annotation processor writes the bootstrap page's controller into
  # target/generated-sources, and Maven does NOT re-run it when the only thing that changed is
  # `mateu.version`: the module's own .java files are untouched, so javac skips the round and the
  # PREVIOUS Mateu's generated controller survives into the jar. An image built that way carries a
  # new frontend bundle behind an old bootstrap.
  #
  # That is not hypothetical. It shipped: a fix released in alpha.308 was still missing from a
  # console running alpha.309, and the symptoms were a body with the browser's default 8px margin,
  # an app sitting that far down its viewport, the chat panel's input bar hanging off the bottom
  # edge, and menus that did not resolve. Every build was green throughout, and so was the obvious
  # check — "all eight modules compile" is true and says nothing whatever about whether the
  # processor ran again. What says it is a clean build of the same pom producing a different
  # generated controller.
  #
  # The same holds, and matters more, between the two renderers of one shell: the second build of
  # a module reuses the first one's target/ unless it is cleaned, and a Redwood image built on a
  # Vaadin build's leftovers is a Vaadin image with a Redwood name. So every renderer's build
  # is a `clean package` of its own, immediately followed by its image.
  ( cd "$app" && mvn -B -ntp -DskipTests clean package "$@" )
  docker buildx build --platform "$PLATFORMS" -t "$REGISTRY/$image:$TAG" --push "$app"
  PUSHED="${PUSHED:-} $image"
}

for app in $APPS; do
  echo "── building $app ──"
  build_and_push "$app" "ec-demo1-$(basename "$app")"
  case " $RENDERED_TWICE " in
    *" $app "*)
      echo "── building $app, Redwood ──"
      build_and_push "$app" "ec-demo1-$(basename "$app")-redwood" -Predwood
      ;;
  esac
done

# The documentation site (doc/): not a Maven module. Its Dockerfile builds it with Node and serves
# it with nginx, so there is no package step here — only the image, with its own tag (DOCS_TAG), as
# deploy/manifests/81-docs.yaml pulls it.
DOCS_TAG="${DOCS_TAG:-0.1.0}"
echo "── building doc (the documentation site) ──"
docker buildx build --platform "$PLATFORMS" -t "$REGISTRY/ec-demo1-docs:$DOCS_TAG" --push doc
echo "Pushed $REGISTRY/ec-demo1-docs:$DOCS_TAG"

echo
for image in $PUSHED; do echo "Pushed $REGISTRY/$image:$TAG"; done
echo "Point the manifests in deploy/manifests/ at the tag if it is not $TAG."
