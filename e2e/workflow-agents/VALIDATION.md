# Implementation verification

Verified on 2026-10-16 using synthetic data and local HTTP endpoints.

| Check | Result |
| --- | --- |
| EventConductor full engine suite | 1,173 tests passed |
| EventConductor full Maven plugin suite | 61 tests passed |
| Later engine changes | Focused persistence, review, timeout, retry policy and wire tests passed |
| Workflow graph | Vite build and TypeScript passed; served bundle and IDE copies rebuilt |
| Browser catalogue authentication | Same-origin bearer sent; cross-origin endpoint rejected before fetch |
| IA control plane | 48 tests passed |
| IA agent, including existing chat | 133 tests passed |
| Gateway | 10 tests passed, including anonymous/demo/admin catalogue access |
| Definitions repository | All 66 definitions validated with matching plugin |
| Python contracts and HTTP fixtures | 10 tests passed |
| Synthetic IA YAMLs | All six passed catalogue JSON schema |
| Deployment | Helm rendered, YAML parsed, shell/embedded scripts syntax checked |
| Image preflight | Registry ports accepted; missing tags/digests rejected |

Runtime checks include real localhost HTTP A2A and provider calls, RS256 JWT verification,
provider thought-signature replay, recovery after a temporary delegation outage, uncertain
effects and digest-bound review, timeout reconciliation, multi-partition Kafka rewind and
transactional Flyway/H2 persistence. An undeliverable usage backlog cannot starve status replies.
Tests use a Kafka consumer test double rather than a running broker.

The later isolated Kubernetes run on `ec-demo1-local-control-plane` passed the full synthetic
happy path and idempotent invocation, root and child uncertain-write reconciliation, and worker
restart without replay. This exercised PostgreSQL advisory locks, encrypted snapshot native SQL,
usage receipts, Flyway, Kafka and real signed Keycloak JWTs. PostgreSQL confirmed two completed
processes, four completed agent executions, two snapshots, twelve usage receipts and fully
delivered reply/usage outboxes. See [local/evidence/2026-10-16-local-kubernetes.json](local/evidence/2026-10-16-local-kubernetes.json).

The live test exposed missing operator subjects when the local Keycloak client omitted the
`basic` scope. The final configuration includes it; the runtime also rejects missing/blank
JWT subjects and reviewers before changing durable state. Signed-token and domain regression
tests pass. Acceptance checks require persisted reviewer identity and evidence, actual MCP
case data, the seeded nonempty RAG passage, the verified peer result and explicit workflow
input selection.

Not executed: visual console verification, paid providers and real integrations. The local
Docker socket was inaccessible; the Compose alternative was only rendered and checked statically.

Reproduce Maven commands with `-Dmaven.repo.local=/tmp/ec-workflow-agent-m2` in this environment.
Its incomplete JDK installation also required `-Dmaven.compiler.release=` plus source/target21.
Local HTTP tests need localhost sockets. These environment-specific flags are not required by
the implementation when using a complete JDK21 installation.
