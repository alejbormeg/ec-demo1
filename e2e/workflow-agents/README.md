# Native workflow agents

This feature spans four repositories, each on `feat/workflow-agent-step`:

| Repository | Responsibility |
| --- | --- |
| EventConductor | Native `AGENT` DSL, schema/plugin, editor, dispatch, persisted correlation and review holds |
| ec-demo1 | GitOps publication, encrypted snapshots, durable workflow runtime, review UI and Kubernetes deployment |
| ec-definitions | `run-agent.ectask` and `assess-synthetic-case.ec` |
| ec-ia-config | Synthetic agent/model/MCP/RAG definitions |

The matching checkouts are sibling folders `../eventconductor-workflow-agent-step`,
`../ec-definitions` and `../ec-ia-config`. The original `../eventconductor` checkout remains
on its original branch.

## Authoring

Define the agent in the configured IA GitOps repository, including `outputSchema`, fixed model,
system prompt and allowed `mcp`, `rag`, `peers`. The control plane publishes only successfully
reconciled Git YAMLs. Console edits remain useful for chat but cannot publish workflow agents.

```yaml
- id: assess
  type: AGENT
  name: Assess the case
  agent:
    ref: workflow-case-assessor
    taskPrompt: Assess the supplied synthetic case using the available evidence.
    input:
      caseId: "${caseId}"
      case: "${caseData}"
    outputVariable: assessment
  timeout: PT5M
  retries: 0
```

Only `ref` and `outputVariable` are required inside `agent`. Missing `input` means `{}`.
Omitted `mcp`, `rag` or `peers` inherits the agent's selection; an empty list disables it.
Explicit lists must be subsets. The workflow cannot override model, credential or system prompt.
The final answer must match the published JSON schema. The engine stores it as a JSON string;
conditions can use `json:parse(assessment).decision == 'accepted'`.

## Execution and operations

The engine sends only the rendered `__agent` envelope on `agent-calls`, using `run-agent@1`.
It freezes the explicit inputs and invocation ID, and stores the initiating actor for audit.
The worker uses the `workflow-agent-executor` service identity for authorization, MCP and A2A.
It freezes the root agent and reachable peer configurations in an encrypted control-plane
snapshot, with a digest and catalogue revision. Git edits cannot change an accepted execution.

The separate `ia-workflow-worker` Deployment uses the same image as chat with profile `workflow`.
Its own `agent_executions` PostgreSQL database stores model rounds, tool arguments, confirmed
results, A2A children, review evidence, usage and status outboxes. Complete provider responses
and assistant metadata survive replay. Recovery reuses committed results. Kafka offsets are
committed only after durable acceptance. Usage receipts and status sequence numbers suppress
duplicate accounting and stale updates.

The worker executes model rounds explicitly. MCP supports SSE and Streamable HTTP; RAG uses
the frozen source and embedding model. A2A sends JSON data through HTTP `message/send` and
`tasks/get`, with stable child IDs, bounded depth and cycle checks. A model cannot invent a
peer identity or trusted delegation metadata.

An unconfirmed effect pauses the process as `REVIEW_REQUIRED`. In the control console, open
**IA → Workflow agent executions**, select the affected root or child, inspect its unknown
call and traces, and provide a confirmed JSON result plus reconciliation evidence. Review is
authorized as the actual `ai-admin` operator and bound to both tool and request digests.
It does not execute the tool again. The same view supports cancellation. A timeout requests
cancellation and waits for effects to be reconciled before taking the native timeout branch.
Ordinary review renews the execution budget; timeout review remains cancellation.

V1 requires `retries: 0`: returned failures are final. Worker restart and Kafka redelivery resume
durable checkpoints within the same invocation. An explicit process restart after a resolved
failure creates a new invocation and can incur new effects and model usage.

## Build and enable

Use JDK 21 and Maven. Build/install the matching EventConductor reactor and plugin first:

```bash
cd /path/to/eventconductor
mvn -pl apps/orchestrator-standalone-app,modules/workflow-maven-plugin -am install
cd /path/to/ec-definitions
mvn process-resources
```

Build the orchestrator image from `apps/orchestrator-standalone-app/Dockerfile`. In ec-demo1,
build `supporting/ui-commons` first, then package `ai/ia-agent`, `ai/ia-control-plane` and
`consoles/gateway` and build their Dockerfiles. Use explicit feature tags. The existing
`deploy/build-images.sh` builds and pushes the entire demo; it is unnecessary for a local-only
feature check. The local launcher loads the four locally built images into kind after creating
`ec-demo1-local`. Set `WORKFLOW_AGENTS_LOAD_LOCAL_IMAGES=false` when using registry-hosted tags.

The feature is opt-in. On a deliberately selected test cluster, set:

```bash
export WORKFLOW_AGENTS_ENABLED=true
export WORKFLOW_AGENT_ENGINE_IMAGE=local/orchestrator:workflow-agent-step
export WORKFLOW_AGENT_IMAGE=local/ia-agent:workflow-agent-step
export WORKFLOW_AGENT_CONTROL_PLANE_IMAGE=local/ia-control-plane:workflow-agent-step
export WORKFLOW_AGENT_GATEWAY_IMAGE=local/gateway:workflow-agent-step
deploy/local/up.sh
```

`deploy/local/up.sh` creates its own kind cluster/kubeconfig and rewrites issuer URLs to
`http://auth.localhost:8800`. It deploys the whole demo, whose existing integrations may use
configured external services. Review that script and its credentials before using it. For an
already provisioned test cluster, `deploy/deploy.sh` applies the same opt-in feature.
No cluster is changed by the tests described below.

The deployment generates a service-client secret and database password, reconciles Keycloak
for both new and already imported realms, creates the runtime database/login and starts the
worker. The client has the executor role and no administrator role. Machine endpoints verify
signature, issuer, expiry, audience and client; review endpoints require an administrator JWT.
The gateway's catalogue proxy is restricted to the control console and `ai-admin`.

Point both task/workflow Git imports and IA GitOps at repositories containing these commits.
For branch testing after those branches have been made available to the cluster:

```bash
kubectl -n ec-demo1 set env deployment/ia-control-plane GITOPS_BRANCH=feat/workflow-agent-step
kubectl -n ec-demo1 set env deployment/ec-eventconductor-orchestrator \
  WORKFLOW_GITIMPORT_REPOSITORIES_0_BRANCH=feat/workflow-agent-step \
  TASKS_GITIMPORT_REPOSITORIES_0_BRANCH=feat/workflow-agent-step
```

These changes are local Git branches until explicitly published. Configure repository URLs as
well if using forks. Reconcile IA first; restart/re-import the engine after publication succeeds.
Engine definition import checks the live published catalogue. Maven validation is offline.

## Synthetic acceptance scenario

The isolated local harness in [local/README.md](local/README.md) has now been executed
successfully on the local kind cluster, with real PostgreSQL, Kafka, signed Keycloak tokens,
MCP, pgvector retrieval and HTTP A2A. It consumes local YAMLs without pushing feature branches.
It also verifies uncertain-write review and worker restart without effect replay. This is the
recommended path for trying the feature independently of the full demo.

After selecting the test kubeconfig and deploying the feature images:

```bash
export KUBECONFIG="$HOME/.local/share/ec-demo1-local/kubeconfig"
bash e2e/workflow-agents/setup-fixtures.sh
```

This explicitly installs a deterministic OpenAI-compatible fixture, an MCP fixture and a
separate, disposable pgvector database. It generates synthetic-only credentials into a Secret,
sets the three environment references and seeds one handbook rule. No paid model is called.
The embeddings are constant and validate plumbing rather than retrieval quality.
Reconcile the synthetic IA branch, then re-import the definitions branch. Start
`assess-synthetic-case` with `caseId=SYN-001`, `caseData=synthetic evidence present` using the
workflow console. Expected: completed root and evidence child, an `assessment` JSON answer
with `decision=accepted`, and persisted rounds/usage for both agents.

For the uncertain-effect scenario:

```bash
kubectl -n ec-demo1 set env deployment/workflow-fixtures FIXTURE_UNCERTAIN_WRITE=true
```

Start a new synthetic process. The fixture commits a SQLite write and drops its MCP reply.
Expected: `REVIEW_REQUIRED`, no business output, and one unknown write. Inspect
`http://workflow-mcp-mock:8080/fixture/stats` from the cluster (or port-forward its Service).
Restart only `ia-workflow-worker`; the write count must stay unchanged. Reconcile the unknown
call with `{"caseId":"SYN-001","synthetic":true,"written":true}` and evidence from that
ledger. Repeat for the child if the selected scenario reaches its write. Restore the flag to
`false` before ordinary acceptance runs. The fixture ledger persists across worker restarts;
the fixture pod/database are disposable and lose data if recreated.

## Verification and limits

Run the offline contract checks and localhost protocol fixtures:

```bash
python3 -m unittest discover -s e2e/workflow-agents -p 'test_*.py' -v
mvn -f ai/ia-control-plane/pom.xml test
mvn -f ai/ia-agent/pom.xml test
```

This implementation was tested with a writable Maven cache at `/tmp/ec-workflow-agent-m2`.
The environment's JDK lacks `ct.sym`; builds used
`-Dmaven.compiler.release= -Dmaven.compiler.source=21 -Dmaven.compiler.target=21`.
Local HTTP tests required allowing localhost sockets outside the sandbox. SQL unit tests
exercise real Flyway and transactions in H2 PostgreSQL mode. The isolated Kubernetes harness
also exercised PostgreSQL advisory locks and control-plane native snapshot/usage SQL; see
its recorded evidence. Docker's socket remains unavailable, so the Docker Compose alternative has only
been rendered and checked statically. Visual console layout has not been verified.
