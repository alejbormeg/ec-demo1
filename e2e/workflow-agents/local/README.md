# Isolated local workflow-agent acceptance

This harness runs only synthetic agent definitions and deterministic model, MCP and
embedding fixtures. It supports an isolated namespace on the local kind cluster or its
own Docker Compose project, network and volumes.
It does not use the full demo's credentials, GitHub, paid models or existing databases.
The control plane still runs its real GitOps tarball importer, encrypted snapshots,
PostgreSQL persistence and pgvector search. The runtime uses real Kafka and HTTP A2A.

Prerequisites: Docker Compose CLI, Python 3, Maven and JDK 21, plus either the accessible
local Kubernetes cluster or Docker Engine. Compose configuration rendering does not need
the Docker daemon. The first build
can download Maven dependencies and the listed public container images. Allow about
6 GiB RAM for this isolated stack. Nothing is pushed to a registry.

Use the four feature-branch checkouts. The launcher discovers sibling checkouts from
the ec-demo1 Git common directory, including when ec-demo1 is itself a worktree.
Override `ENGINE_REPO`, `IA_CONFIG_REPO` and `DEFINITIONS_REPO` if needed.

## Local Kubernetes

This is the verified path on `ec-demo1-local-control-plane`. It checks the node name before
changing anything and deploys only to `ec-workflow-agent-test`. Existing `ec-demo1` workloads
and credentials are untouched. Applications run the locally built JARs from a dedicated PVC;
no image registry push or Docker daemon access is necessary.

```bash
export KUBECONFIG="$HOME/.kube/ec-demo1-local"
e2e/workflow-agents/local/run.sh prepare
e2e/workflow-agents/local/run.sh build --jars-only
e2e/workflow-agents/local/run-k8s.sh up
e2e/workflow-agents/local/run-k8s.sh check
```

`up` starts dedicated databases, broker, Keycloak and fixtures, uploads the three branch-built
JARs, waits for real authenticated GitOps publication, then starts the runtime and engine.
`check` executes the workflow and idempotent replay, loses an MCP reply after its effect,
restarts the worker, verifies no duplicate write, and reconciles the root and child calls.
The checker writes `.state/acceptance-evidence.json` without tokens or credentials.

```bash
e2e/workflow-agents/local/run-k8s.sh status
e2e/workflow-agents/local/run-k8s.sh connect
e2e/workflow-agents/local/run-k8s.sh logs ia-workflow-worker
e2e/workflow-agents/local/run-k8s.sh check
e2e/workflow-agents/local/run-k8s.sh down
```

`down` stops only this namespace's workloads and its recorded port forwards, retaining PVCs
and Secrets. All forwards listen on `127.0.0.1`. The runtime uses **28095** here to avoid the
existing listener on 18095; `--runtime-port` can override it. Other URLs in the table below
are shared with the Compose path. The engine console is at http://127.0.0.1:18105/.
Use user `local-operator` with `ENGINE_HTTP_PASSWORD` from the generated local `.env` file.
That identity is for this synthetic console only. Runtime review uses the separately issued
Keycloak operator token, which the checker obtains automatically.

The initial live run is recorded in [evidence/2026-10-16-local-kubernetes.json](evidence/2026-10-16-local-kubernetes.json).
PostgreSQL showed two completed processes, four completed agent executions, two frozen
snapshots, twelve unique usage receipts, and no undelivered outbox entries.

## Docker Compose alternative

```bash
e2e/workflow-agents/local/run.sh prepare
e2e/workflow-agents/local/run.sh build --jars-only
e2e/workflow-agents/local/run.sh up
```

`up` builds the jars and images by default. After a separate full `build`, use
`up --skip-build`. It starts the control plane before the engine and waits until the
two Git-managed agents are published, so native definition import can validate their
references. It then runs the acceptance checks. `check` repeats those checks.

The harness generates `.state/` with mode 700 and `.env` with mode 600. Generated
credentials remain local and are reused until reset. The databases and Keycloak receive
their bootstrap credentials through `.state/engine-db.env`, `.state/catalogue-db.env`
and `.state/keycloak-bootstrap.env` (mode 600). Compose requires these generated files
at startup. Run `prepare` again to generate them for an existing local state.
The Keycloak realm file holds only these disposable credentials. Do not commit `.env`
or `.state`.
`inputs.json` records source commits and the SHA-256 of each imported IA YAML;
`jars.json` records the exact locally built application jars. The tarball includes
only the six `workflow-*` YAMLs copied from ec-ia-config. The workflow and task come
from ec-definitions. Its local workflow copy adds synchronous invocation and a REPLY
step so the checker can identify each process from its invocation response.

| Endpoint | Local URL |
| --- | --- |
| Keycloak realm | http://localhost:18080/realms/workflow-agents |
| Control plane | http://127.0.0.1:18110 |
| Durable agent runtime | Kubernetes: http://127.0.0.1:28095; Compose: http://127.0.0.1:18095 |
| Workflow engine | http://127.0.0.1:18105 |
| Deterministic fixtures | http://127.0.0.1:18090 |

Published ports bind only to loopback. PostgreSQL and Kafka are private to the Compose
network. The service client has only `workflow-agent-executor`; the separate synthetic
operator has `ai-admin`. The runtime and engine use the technical client, while review
uses the operator's real signed token.

```bash
e2e/workflow-agents/local/run.sh status
e2e/workflow-agents/local/run.sh logs ia-workflow-worker
e2e/workflow-agents/local/run.sh down
e2e/workflow-agents/local/run.sh reset
```

`down` preserves this project's volumes. `reset` removes only this project's volumes
and generated state; its synthetic execution evidence is then lost. Neither command
changes Kubernetes or another Compose project. Check results demonstrate this synthetic
scenario, not model quality, production readiness or all failure modes.
