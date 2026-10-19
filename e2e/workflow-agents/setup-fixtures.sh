#!/usr/bin/env bash
# Install only synthetic endpoints into an already selected test cluster.
set -euo pipefail
cd "$(dirname "$0")"
NS="${WORKFLOW_FIXTURE_NAMESPACE:-ec-demo1}"
if [ "$NS" != ec-demo1 ]; then
  echo 'fixtures.yaml targets ec-demo1; use the same namespace.' >&2; exit 1
fi
kubectl config current-context
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
chmod 700 "$tmp_dir"
if ! kubectl -n "$NS" get secret workflow-fixture-credentials >/dev/null 2>&1; then
  fixture_password=$(openssl rand -hex 24)
  printf %s "$fixture_password" > "$tmp_dir/POSTGRES_PASSWORD"
  printf %s "jdbc:postgresql://workflow-rag-mock:5432/synthetic_workflow?user=synthetic_workflow&password=$fixture_password" > "$tmp_dir/WORKFLOW_SYNTHETIC_RAG_URL"
  printf %s synthetic-fixture-key > "$tmp_dir/WORKFLOW_SYNTHETIC_MODEL_KEY"
  printf %s synthetic-fixture-key > "$tmp_dir/WORKFLOW_SYNTHETIC_EMBEDDING_KEY"
  kubectl -n "$NS" create secret generic workflow-fixture-credentials --from-file="$tmp_dir" >/dev/null
  unset fixture_password
fi
# Upgrade an already installed fixture Secret without rotating its database password.
kubectl -n "$NS" patch secret workflow-fixture-credentials --type=merge --patch \
  '{"stringData":{"WORKFLOW_SYNTHETIC_MODEL_KEY":"synthetic-fixture-key","WORKFLOW_SYNTHETIC_EMBEDDING_KEY":"synthetic-fixture-key"}}' >/dev/null
kubectl -n "$NS" create configmap workflow-fixture-code --from-file=fixture_server.py --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create configmap workflow-fixture-seed --from-file=seed.sql --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f fixtures.yaml
kubectl -n "$NS" patch deployment ia-control-plane --type=strategic --patch '
{"spec":{"template":{"spec":{"containers":[{"name":"ia-control-plane","env":[
 {"name":"WORKFLOW_SYNTHETIC_RAG_URL","valueFrom":{"secretKeyRef":{"name":"workflow-fixture-credentials","key":"WORKFLOW_SYNTHETIC_RAG_URL"}}},
 {"name":"WORKFLOW_SYNTHETIC_MODEL_KEY","valueFrom":{"secretKeyRef":{"name":"workflow-fixture-credentials","key":"WORKFLOW_SYNTHETIC_MODEL_KEY"}}},
 {"name":"WORKFLOW_SYNTHETIC_EMBEDDING_KEY","valueFrom":{"secretKeyRef":{"name":"workflow-fixture-credentials","key":"WORKFLOW_SYNTHETIC_EMBEDDING_KEY"}}}
]}]}}}}'
for deployment in workflow-fixtures workflow-rag-mock ia-control-plane; do
  kubectl -n "$NS" rollout status "deployment/$deployment" --timeout=5m
done
echo 'Synthetic fixtures are ready. Reconcile the feature branch of ec-ia-config before running the workflow.'
