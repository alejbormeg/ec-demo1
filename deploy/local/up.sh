#!/usr/bin/env bash
# The whole demo on this machine: a kind cluster, the same manifests as ec1 and deploy.sh, on
# http://*.localhost instead of https://*.ec1.mateu.io.
#
#   deploy/local/up.sh          # create (or converge) the cluster and deploy everything
#   deploy/local/down.sh        # delete the cluster; the data under $LOCAL_HOME stays
#
# Then, with KUBECONFIG=$LOCAL_HOME/kubeconfig for kubectl and the demo scripts:
#   http://ec1.localhost:8800  http://rw.localhost:8800  http://console.localhost:8800
#   http://rw-console.localhost:8800  http://front.localhost:8800  http://auth.localhost:8800
#   http://grafana.localhost:8800  http://doc.localhost:8800
#
# Port 8800, not 80: this machine's k3s (Traefik, through iptables) takes 127.0.0.1:80 and :443 before
# Docker's port mapping sees them. Inside the cluster the ingress Service listens on 8800 too, so a
# pod reaches http://auth.localhost:8800 like the browser does; the backends get the Host without
# the port (upstream-vhost), as on ec1 — the gateway routes by exact host.
#
# Why *.localhost: browsers resolve it to 127.0.0.1 by themselves and treat it as a secure context,
# so keycloak-js, the PWA and Web Push work over plain http. Inside the cluster CoreDNS sends every
# *.localhost to the ingress controller, so a service reaches Keycloak by the same URL the browser
# uses and the token's issuer matches.
#
# Disk: the node's containerd (the images) and the volumes are bind-mounted from $LOCAL_HOME, on
# /home — / has no room for them. Only the node container itself lives in Docker's root.
#
# Its own kubeconfig, never merged into ~/.kube/config: `kubectl` and deploy/demo/*.sh keep pointing
# at ec1 unless KUBECONFIG says otherwise.
#
# Opera and Salesforce are the same as ec1's (deploy/.secrets/credentials.env): the local MDM hears
# the same Salesforce events and spends the same daily API allowance, and the local integrations
# write to the same Opera UAT — under their own context (ECLOCAL-…), never EC-DEMO1's or a run's.
# zero.sh run against this cluster still deletes every contact in the org: ec1's too.
#
# On a Mac (Apple Silicon, Docker Desktop) it runs too, with `brew install kind gnu-sed`: GNU sed as
# gsed, the node's containerd and volumes inside Docker's VM (overlayfs does not work on a shared
# folder), no amd64 pin, and the resource requests shrunk — ec1's add up to ~30 GiB and a pod that
# does not fit stays Pending, while the whole demo uses ~18 GiB. Give Docker Desktop 24 GiB and
# Rosetta. The engine's images are amd64 only on Docker Hub: arm64 ones built here (docker images)
# are loaded into the node instead of pulled — see LOCAL_IMAGES below — and this repository's are
# built here and loaded too (deploy/local/load-images.sh): pushing them from home is far too slow.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
LOCAL_HOME="${LOCAL_HOME:-$HOME/.local/share/ec-demo1-local}"
CLUSTER=ec-demo1-local
PORT="${PORT:-8800}"
export KUBECONFIG="$LOCAL_HOME/kubeconfig"
TREE="$LOCAL_HOME/tree"
mkdir -p "$LOCAL_HOME/containerd" "$LOCAL_HOME/local-path"
SED=sed
MAC=false
if [ "$(uname -s)" = Darwin ]; then
  MAC=true
  SED=gsed
  command -v gsed >/dev/null || { echo "GNU sed is needed: brew install gnu-sed" >&2; exit 1; }
fi
MOUNTS="
    extraMounts:
      - { hostPath: $LOCAL_HOME/containerd, containerPath: /var/lib/containerd }
      - { hostPath: $LOCAL_HOME/local-path, containerPath: /var/local-path-provisioner }"
$MAC && MOUNTS=""

echo "══ kind cluster $CLUSTER (data in $LOCAL_HOME) ══"
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  cat > "$LOCAL_HOME/kind.yaml" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    # The manifests pin every pod to hel1/amd64, as on ec1: the one node says it is.
    labels:
      topology.kubernetes.io/region: hel1$MOUNTS
    extraPortMappings:
      - { containerPort: 80, hostPort: $PORT, listenAddress: 127.0.0.1 }
EOF
  kind create cluster --name "$CLUSTER" --config "$LOCAL_HOME/kind.yaml" --kubeconfig "$KUBECONFIG" --wait 120s
fi
kubectl label node --all topology.kubernetes.io/region=hel1 --overwrite >/dev/null

# Feature tags are built locally; load them after creating kind and before any rollout waits.
# Set this switch to false when the four explicit image tags live in a reachable registry.
if [ "${WORKFLOW_AGENTS_ENABLED:-false}" = true ] && [ "${WORKFLOW_AGENTS_LOAD_LOCAL_IMAGES:-true}" = true ]; then
  : "${WORKFLOW_AGENT_ENGINE_IMAGE:?Set the locally built workflow engine image}"
  : "${WORKFLOW_AGENT_IMAGE:?Set the locally built workflow runtime image}"
  : "${WORKFLOW_AGENT_CONTROL_PLANE_IMAGE:?Set the locally built workflow control-plane image}"
  : "${WORKFLOW_AGENT_GATEWAY_IMAGE:?Set the locally built workflow gateway image}"
  kind load docker-image --name "$CLUSTER" "$WORKFLOW_AGENT_ENGINE_IMAGE" "$WORKFLOW_AGENT_IMAGE" \
    "$WORKFLOW_AGENT_CONTROL_PLANE_IMAGE" "$WORKFLOW_AGENT_GATEWAY_IMAGE"
fi

# ec1's volumes are hcloud-volumes: here the same name, on kind's local-path provisioner.
kubectl apply -f - <<'EOF'
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata: { name: hcloud-volumes }
provisioner: rancher.io/local-path
reclaimPolicy: Delete
volumeBindingMode: WaitForFirstConsumer
EOF

# Every *.localhost to the ingress controller, for the pods (the browser does it by itself).
kubectl -n kube-system get configmap coredns -o jsonpath='{.data.Corefile}' > "$LOCAL_HOME/Corefile"
if ! grep -q 'localhost' "$LOCAL_HOME/Corefile"; then
  "$SED" -i 's|^\(\s*\)ready$|\1ready\n\1rewrite name regex (.+)\\.localhost\\.$ ingress-nginx-controller.ingress-nginx.svc.cluster.local. answer auto|' "$LOCAL_HOME/Corefile"
  kubectl -n kube-system create configmap coredns --from-file=Corefile="$LOCAL_HOME/Corefile" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n kube-system rollout restart deployment coredns
fi

echo "══ A local copy of deploy/: *.ec1.mateu.io → *.localhost, http, no certificates ══"
rm -rf "$TREE"; mkdir -p "$TREE"
rsync -a --exclude .secrets --exclude local "$REPO/deploy" "$TREE/"
# What the services serve, for check-contracts.sh / demo-prep.sh health run from the copy.
mkdir -p "$TREE/contracts" && rsync -a "$REPO/contracts/workers" "$TREE/contracts/"
find "$TREE/deploy" -type f \( -name '*.yaml' -o -name '*.json' -o -name '*.sh' \) -print0 | xargs -0 "$SED" -i -E \
  -e "s#https?://([a-z0-9-]+)\.ec1\.mateu\.io#http://\1.localhost:$PORT#g" \
  -e "s#https?://ec1\.mateu\.io#http://ec1.localhost:$PORT#g" \
  -e 's#([a-z0-9-]+)\.ec1\.mateu\.io#\1.localhost#g' \
  -e 's#ec1\.mateu\.io#ec1.localhost#g' \
  -e '/cert-manager\.io\/cluster-issuer/d'
# The backends see the host without the port, as on ec1.
python3 - "$TREE/deploy/manifests/40-ingress.yaml" "$TREE/deploy/manifests/81-docs.yaml" "$TREE/deploy/manifests/50-kafka-console.yaml" <<'PY'
import re, sys
for path in sys.argv[1:]:
    docs = open(path).read().split('\n---')
    out = []
    for d in docs:
        m = re.search(r'^\s*- host: (\S+)', d, re.M)
        if re.search(r'^kind: Ingress', d, re.M) and m and 'upstream-vhost' not in d:
            d = re.sub(r'^(  annotations:\n)', r'\1    nginx.ingress.kubernetes.io/upstream-vhost: "%s"\n' % m.group(1), d, count=1, flags=re.M)
            # The consoles' pages carry ec1's Keycloak, compiled in from @KeycloakSecured(url = …):
            # rewritten on the way out to this cluster's.
            if not m.group(1).startswith(('auth.', 'doc.', 'kafka.')):
                d = re.sub(r'^(  annotations:\n)', r'\1    nginx.ingress.kubernetes.io/configuration-snippet: |\n'
                           r'      proxy_set_header Accept-Encoding "";\n      sub_filter_once off;\n'
                           r"      sub_filter 'https://auth.ec1.mateu.io' 'http://auth.localhost:PORT';\n", d, count=1, flags=re.M)
        out.append(d)
    open(path, 'w').write('\n---'.join(out))
PY
"$SED" -i "s#localhost:PORT#localhost:$PORT#g" "$TREE/deploy/manifests/40-ingress.yaml"
# The gateway's other three hosts: not in its manifest, their defaults (in the jar) are ec1's.
"$SED" -i -E 's#^(\s*)- name: CONTROL_HOST$#\1- name: RW_CONSOLE_HOST\n\1  value: rw.localhost\n\1- name: RW_CONTROL_HOST\n\1  value: rw-console.localhost\n\1- name: FRONT_OFFICE_HOST\n\1  value: front.localhost\n\1- name: CONTROL_HOST#' \
  "$TREE/deploy/manifests/35-gateway.yaml"
# The ingress controller: on the node's ports 80/443 (kind maps them to this machine), no
# LoadBalancer, and no redirect to https (the Ingresses keep their tls: blocks; nothing issues them).
"$SED" -i -E \
  -e "s#controller\.service\.type=LoadBalancer#controller.service.type=NodePort --set controller.service.ports.http=$PORT --set controller.hostPort.enabled=true --set-string controller.config.ssl-redirect=false --set controller.allowSnippetAnnotations=true --set-string controller.config.annotations-risk-level=Critical#" \
  "$TREE/deploy/deploy.sh"
"$SED" -i -E '/05-clusterissuer\.yaml/d' "$TREE/deploy/deploy.sh"

if $MAC; then
  echo "══ Mac: no amd64 pin, small requests, node-exporter without the host's root ══"
  find "$TREE/deploy" -type f -name '*.yaml' -print0 | xargs -0 "$SED" -i -E \
    -e '/^\s*kubernetes\.io\/arch: amd64\s*$/d' \
    -e 's/,\s*kubernetes\.io\/arch: amd64//' \
    -e 's/\{\s*kubernetes\.io\/arch: amd64\s*\}/{}/' \
    -e 's/requests:\s*\{\s*memory:[^}]*\}/requests: { memory: "16Mi", cpu: "5m" }/'
  "$SED" -i -E '/kubernetes\\\.io\/arch"=amd64/d' "$TREE/deploy/deploy.sh"
  # No liveness probes: ~35 JVMs starting at once on a laptop take minutes, a liveness probe kills each
  # before it is up, and the restarts feed each other until the node does nothing else (load 130 on
  # 14 cores). Readiness stays, so nothing gets traffic before it can take it.
  find "$TREE/deploy/manifests" "$TREE/deploy/chart" -type f -name '*.yaml' -print0 | xargs -0 python3 -c '
import re, sys
for path in sys.argv[1:]:
    lines = open(path).read().split("\n"); out = []; skip = None
    for line in lines:
        indent = len(line) - len(line.lstrip())
        if skip is not None:
            if line.strip() == "" or indent > skip:
                continue
            skip = None
        if re.match(r"^\s*livenessProbe:", line):
            skip = indent
            continue
        out.append(line)
    open(path, "w").write("\n".join(out))
'
  # The embeddings server publishes its arm64 build under a tag of its own.
  "$SED" -i 's#text-embeddings-inference:cpu-\([0-9]\)#text-embeddings-inference:cpu-arm64-\1#' "$TREE/deploy/manifests/12-embeddings.yaml"
  "$SED" -i -E 's/^prometheus-node-exporter:$/prometheus-node-exporter:\n  hostRootFsMount:\n    enabled: false/' \
    "$TREE/deploy/observability/kube-prometheus-stack.yaml"
fi

echo "══ Credentials: generated here, except what is bought (Opera, Salesforce, Anthropic, mail) ══"
mkdir -p "$TREE/deploy/.secrets"
LOCAL_SECRETS="$LOCAL_HOME/credentials.env"
if [ ! -f "$LOCAL_SECRETS" ]; then
  # Not GOOGLE_CHAT_WEBHOOK: the local alerts must not land in ec1's chat.
  grep -E '^(SF_DOMAIN|SF_CLIENT_ID|SF_CLIENT_SECRET|OPERA_[A-Z_]+|ANTHROPIC_API_KEY|POSTFIX_RELAY_PASSWORD|DEMO_PASSWORD|GITOPS_GITHUB_TOKEN)=' \
    "$REPO/deploy/.secrets/credentials.env" > "$LOCAL_SECRETS" || true
  chmod 600 "$LOCAL_SECRETS"
fi
# deploy.sh generates these only when it creates the file itself, which it does not here.
for k in EC_POSTGRES_PASSWORD KEYCLOAK_ADMIN_PASSWORD GRAFANA_ADMIN_PASSWORD KAFKA_CONSOLE_PASSWORD; do
  grep -q "^$k=" "$LOCAL_SECRETS" || echo "$k=$(openssl rand -base64 24 | tr -d '/+=' | head -c 24)" >> "$LOCAL_SECRETS"
done
grep -q "^GIT_WEBHOOK_SECRET=" "$LOCAL_SECRETS" || echo "GIT_WEBHOOK_SECRET=$(openssl rand -hex 24)" >> "$LOCAL_SECRETS"
# deploy.sh appends the passwords it generates to the copy: keep them for the next run.
cp "$LOCAL_SECRETS" "$TREE/deploy/.secrets/credentials.env"
trap 'cp "$TREE/deploy/.secrets/credentials.env" "$LOCAL_SECRETS"' EXIT

# Opera: this environment's own context, before anything can write.
kubectl create namespace ec-demo1 --dry-run=client -o yaml | kubectl apply -f -
if ! kubectl -n ec-demo1 get configmap ec-demo-run >/dev/null 2>&1; then
  ctx="ECLOCAL-$(date -u +%m%d%H%M)"
  kubectl -n ec-demo1 create configmap ec-demo-run \
    --from-literal=OPERA_EXTERNAL_SYSTEM="$ctx" --from-literal=OPERA_CUSTOM_REFERENCE="$ctx"
  echo "  Opera context: $ctx"
fi

# Images this Docker has and the node cannot pull for its platform — on a Mac the engine's (amd64 only
# on Docker Hub; build arm64 ones from the released jars): loaded into the node, where IfNotPresent
# finds them. This repository's own: deploy/local/load-images.sh.
if $MAC && [ -z "${LOCAL_IMAGES:-}" ]; then
  LOCAL_IMAGES="$(awk '/image: miguelperezcolom\/.*-standalone-app/{i=$2} /imageTag:/ && i {gsub(/"/,"",$2); print i":"$2; i=""}' \
    "$TREE/deploy/values/eventconductor.yaml")"
fi
for img in ${LOCAL_IMAGES:-}; do
  if docker image inspect "$img" >/dev/null 2>&1; then
    echo "  loading $img into the node"
    kind load docker-image "$img" --name "$CLUSTER" >/dev/null
  fi
done

echo "══ deploy.sh, as on ec1 ══"
bash "$TREE/deploy/deploy.sh"
