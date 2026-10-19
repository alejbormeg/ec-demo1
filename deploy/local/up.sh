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
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
LOCAL_HOME="${LOCAL_HOME:-$HOME/.local/share/ec-demo1-local}"
CLUSTER=ec-demo1-local
PORT="${PORT:-8800}"
export KUBECONFIG="$LOCAL_HOME/kubeconfig"
TREE="$LOCAL_HOME/tree"
mkdir -p "$LOCAL_HOME/containerd" "$LOCAL_HOME/local-path"

echo "══ kind cluster $CLUSTER (data in $LOCAL_HOME) ══"
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  cat > "$LOCAL_HOME/kind.yaml" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    # The manifests pin every pod to hel1/amd64, as on ec1: the one node says it is.
    labels:
      topology.kubernetes.io/region: hel1
    extraMounts:
      - { hostPath: $LOCAL_HOME/containerd, containerPath: /var/lib/containerd }
      - { hostPath: $LOCAL_HOME/local-path, containerPath: /var/local-path-provisioner }
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
  sed -i 's|^\(\s*\)ready$|\1ready\n\1rewrite name regex (.+)\\.localhost\\.$ ingress-nginx-controller.ingress-nginx.svc.cluster.local. answer auto|' "$LOCAL_HOME/Corefile"
  kubectl -n kube-system create configmap coredns --from-file=Corefile="$LOCAL_HOME/Corefile" \
    --dry-run=client -o yaml | kubectl apply -f -
  kubectl -n kube-system rollout restart deployment coredns
fi

echo "══ A local copy of deploy/: *.ec1.mateu.io → *.localhost, http, no certificates ══"
rm -rf "$TREE"; mkdir -p "$TREE"
rsync -a --exclude .secrets --exclude local "$REPO/deploy" "$TREE/"
find "$TREE/deploy" -type f \( -name '*.yaml' -o -name '*.json' -o -name '*.sh' \) -print0 | xargs -0 sed -i -E \
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
sed -i "s#localhost:PORT#localhost:$PORT#g" "$TREE/deploy/manifests/40-ingress.yaml"
# The gateway's other three hosts: not in its manifest, their defaults (in the jar) are ec1's.
sed -i -E 's#^(\s*)- name: CONTROL_HOST$#\1- name: RW_CONSOLE_HOST\n\1  value: rw.localhost\n\1- name: RW_CONTROL_HOST\n\1  value: rw-console.localhost\n\1- name: FRONT_OFFICE_HOST\n\1  value: front.localhost\n\1- name: CONTROL_HOST#' \
  "$TREE/deploy/manifests/35-gateway.yaml"
# The ingress controller: on the node's ports 80/443 (kind maps them to this machine), no
# LoadBalancer, and no redirect to https (the Ingresses keep their tls: blocks; nothing issues them).
sed -i -E \
  -e "s#controller\.service\.type=LoadBalancer#controller.service.type=NodePort --set controller.service.ports.http=$PORT --set controller.hostPort.enabled=true --set-string controller.config.ssl-redirect=false --set controller.allowSnippetAnnotations=true --set-string controller.config.annotations-risk-level=Critical#" \
  "$TREE/deploy/deploy.sh"
sed -i -E '/05-clusterissuer\.yaml/d' "$TREE/deploy/deploy.sh"

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

echo "══ deploy.sh, as on ec1 ══"
bash "$TREE/deploy/deploy.sh"
