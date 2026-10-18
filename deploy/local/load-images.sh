#!/usr/bin/env bash
# Builds this repository's images for this machine's platform and loads them straight into the local
# cluster (deploy/local/up.sh), under the tag the manifests pull: nothing goes to Docker Hub. Pushing
# from a home connection took 18 minutes for one image; this takes minutes for all of them.
#
#   deploy/local/load-images.sh                 # the tag the manifests pull (deploy/manifests/30-shell.yaml)
#   deploy/local/load-images.sh 0.76.0 [module…] # a tag, and only these modules (paths, as in build-images.sh)
#
# One reactor build for every module — the root pom orders the shared libraries — then each image from
# its jar, and the two shells once more with -Predwood, cleaned first: see build-images.sh on why a
# renderer's build must never reuse the other's target/. The pods take the new image on their next
# start (the manifests' tags are fixed and the pull policy is IfNotPresent):
#   kubectl -n ec-demo1 rollout restart deploy/<app>
# ec1 still needs build-images.sh: it pulls from Docker Hub.
set -euo pipefail
cd "$(dirname "$0")/../.."
REPO="$(pwd)"
REGISTRY="${REGISTRY:-miguelperezcolom}"
CLUSTER=ec-demo1-local
TAG="${1:-$(sed -n 's#.*image: miguelperezcolom/ec-demo1-shell:\(.*\)$#\1#p' deploy/manifests/30-shell.yaml | head -1)}"
shift || true
APPS="${*:-$(sed -n 's/^APPS="\(.*\)"$/\1/p' deploy/build-images.sh)}"
RENDERED_TWICE="consoles/shell consoles/control-shell"
DOCS_TAG="$(sed -n 's#.*image: miguelperezcolom/ec-demo1-docs:\(.*\)$#\1#p' deploy/manifests/81-docs.yaml | head -1)"
PLATFORM="linux/$(docker version -f '{{.Server.Arch}}')"
# The docker driver: --load puts the image in this Docker without an export, which kind then reads.
BUILDER="${BUILDER:-desktop-linux}"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21 2>/dev/null || true)}"

image() {  # <module dir> <image name>
  docker buildx build --builder "$BUILDER" --platform "$PLATFORM" -q --load -t "$REGISTRY/$2:$TAG" "$1" >/dev/null
  echo "  $REGISTRY/$2:$TAG"
}

echo "══ Maven: every module, one reactor build ══"
mvn -B -ntp -q -T 1C -DskipTests clean package

echo "══ Images ($PLATFORM, tag $TAG) ══"
LOADED=""
for app in $APPS; do
  image "$app" "ec-demo1-$(basename "$app")" &
  LOADED="$LOADED $REGISTRY/ec-demo1-$(basename "$app"):$TAG"
done
wait
for app in $APPS; do
  case " $RENDERED_TWICE " in
    *" $app "*)
      ( cd "$app" && mvn -B -ntp -q -DskipTests clean package -Predwood )
      image "$app" "ec-demo1-$(basename "$app")-redwood"
      LOADED="$LOADED $REGISTRY/ec-demo1-$(basename "$app")-redwood:$TAG"
      ;;
  esac
done
if [ -z "${*:-}" ] && [ -n "$DOCS_TAG" ]; then
  docker buildx build --builder "$BUILDER" --platform "$PLATFORM" -q --load -t "$REGISTRY/ec-demo1-docs:$DOCS_TAG" doc >/dev/null
  echo "  $REGISTRY/ec-demo1-docs:$DOCS_TAG"
  LOADED="$LOADED $REGISTRY/ec-demo1-docs:$DOCS_TAG"
fi

echo "══ Into the node ══"
# shellcheck disable=SC2086
kind load docker-image --name "$CLUSTER" $LOADED
echo "Loaded. Restart what should take them: kubectl -n ec-demo1 rollout restart deploy/<app>"
