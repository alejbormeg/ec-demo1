# Working on this

What someone joining needs, in the order they need it.

## 1. Look at it first

| | | |
|---|---|---|
| Console | https://ec1.mateu.io | `demo` / `demo` |
| Control console | https://console.ec1.mateu.io | `demo` / `demo`, and the realm role `ai-admin` |
| Keycloak | https://auth.ec1.mateu.io | `admin` / *ask* |
| Grafana | https://grafana.ec1.mateu.io | `admin` / *ask* |
| Kafka console | https://kafka.ec1.mateu.io | `admin` / *ask* |
| Front office (MRU01) | https://front.ec1.mateu.io | `demo` / `demo` |
| Documentation | https://doc.ec1.mateu.io | `riu` / *ask* (`DOCS_PASSWORD`); `oracle` for Oracle (`DOCS_ORACLE_PASSWORD`) |

Only the demo user is in version control, because it is in the realm file and is meant to be
public. It reaches both consoles: it holds the realm roles `user`, `admin` and `ai-admin`, and
`ai-admin` — a role of its own, kept separate from `admin` because only this host reaches the LLM
credentials — is what the control console requires. **Every other password lives in `deploy/.secrets/credentials.env`,
which is git-ignored, and has to be sent to you out of band** — never pasted into an issue, a
commit or a chat that is logged. They are generated per deployment: a colleague who runs
`deploy.sh` against a different cluster gets different ones.

That file also holds `CP_CRYPTO_KEY`, which is not a password to anything — it is the key the
control plane's stored LLM credentials are encrypted with. Losing it loses them; see §4.

Grafana has six dashboards written for this deployment: **EventConductor** (is it keeping up,
where does the time go, is the relay the constraint), **EventConductor — nodes** (CPU per
component, throttling, the JDBC pool, GC), **Booking traces** (a booking's traces), **IA agents**
(recent prompts and tool calls), **IA token usage** (tokens per agent) and **External APIs**
(Salesforce and Opera consumption) — all in `deploy/observability/dashboards/`.

## 2. Understand what is where

To connect the VS Code execution plugin to ec1, see
[the IDE connection and deployment handoff](deploy/ide-execution/README.md). It requires the
new execution API in the engine, an opt-in flag and the gateway route; the console's login
credentials alone do not configure that connection.

Four repositories, and the split matters:

| repo | what it holds |
|---|---|
| **ec-demo1** (this one) | the deployment — chart values, manifests, the demo scripts — and the applications it builds: 22 modules grouped by system (`systems/`, `integration/`, `control-plane/`, `consoles/`, `ai/`, `supporting/`; see the README's *What is in here*) |
| [**ec-definitions**](https://github.com/miguelperezcolom/ec-definitions) | the workflow, form and rule definitions the three engines import at startup — `master` is protected, so changes go through a PR |
| [**ec-ia-config**](https://github.com/miguelperezcolom/ec-ia-config) | the IA catalogue on ec1 — LLMs, agents, MCP servers, RAG sources — which the control plane syncs from (GitOps), so changes go through a PR there too |
| [**eventconductor**](https://github.com/miguelperezcolom/eventconductor) | the engine itself. Nothing here is built from it; it runs from published images |

A process is changed by a pull request to **ec-definitions**, not by an API call. A push to its
`master` fires two webhooks and both engines re-import within seconds — you do not need to restart
anything or ask anyone.

## 3. Before you change the deployment

```sh
./deploy/build-images.sh TAG # only when an application module changed; pass the tag explicitly
./deploy/deploy.sh           # everything else, idempotent, safe to re-run
```

You need `kubectl` pointed at the CloudFleet cluster, plus `helm`, `docker` and `gh`. Ask for
cluster access first — it is not something you can grant yourself.

Five things about this cluster that will cost you an afternoon if nobody says them:

- **Both PostgreSQLs are on volumes.** The engine's was on an `emptyDir` (local NVMe, for the
  benchmark) until 2026-09-25, and died with its pod more than once; it is on a PVC now, like
  `cp-postgres`. Deleting either PVC is not recoverable.
- **The fleet's CPU limit can only be changed through the CloudFleet Fleet API.** `kubectl patch`
  on a NodePool is refused outright, and so is creating one.
- **Do not pin an instance type.** A pin to `cx43` left every pod `Pending` the day hel1 had none to
  sell, and not every type exists (there is no `ccx33`): Karpenter answers "no instance type ..."
  and creates no node, which reads like a quota. Pin the region and `amd64`, nothing else. (The
  observability stack is still pinned to `ccx23` — the one exception, and the same risk.)
- **Do not touch the `ingress-nginx` Helm release.** It holds the LoadBalancer the DNS points at.
  Reinstalling it changes the IP and every hostname breaks until DNS catches up.
- **Karpenter moves pods to consolidate nodes,** and each move is a restart. The pods a demo cannot
  lose for a minute carry `karpenter.sh/do-not-disrupt`; a new one that matters as much should too.

## 4. The chat panel needs a key you have to supply

`deploy.sh` generates every password except one. The console's chat panel is an LLM, and the
Anthropic key that pays for it is bought rather than derived, so the script writes a commented
`ANTHROPIC_API_KEY=` line into `deploy/.secrets/credentials.env` and creates the `ec-anthropic`
secret only once it is filled in.

That secret now goes to the **control plane**, not to the agent. On a brand-new deployment without
GitOps the control plane seeds an Anthropic model with it; without the key everything else still
works — the model is catalogued with `credential: missing`, and the console at
`https://console.ec1.mateu.io` fixes that under *LLMs → Anthropic → Replace credential*. No
redeploy, no restart; the agent picks it up within 30 seconds.

That is the shape of everything about the agent now: its model, its system prompt, which MCP
servers it may reach and which documents it can search are catalogue entries, not variables in a
manifest. **On ec1 those entries come from git** — GitOps is on, from **ec-ia-config** (§2), so the
seeder stands down and a change is a pull request there; an edit in the console to a git-managed
entry is reverted on the next push. The console is for quick fixes and entries created there.

RAG retrieval needs no second key: its embedding model is a Text Embeddings Inference pod in the
cluster (`12-embeddings.yaml`, a multilingual model, no credential), and a fresh deploy seeds an
*Ops handbook* source on it and ingests a small corpus.

The gateway requires a Keycloak token on `/ai/**` for the same reason it requires one on the
engine's paths, and with less margin: every prompt that reaches the agent is billed.

`deploy.sh` also generates two things you will not be asked for and must not regenerate:
`CP_POSTGRES_PASSWORD` and `CP_CRYPTO_KEY`. The second is the AES key the control plane's stored
LLM credentials are encrypted with — **rotating it makes every one of them undecryptable**, because
nothing re-wraps them, and the only way back is entering the keys again. A re-run against an
existing cluster appends whichever of these is missing and leaves every password already stored in
the cluster exactly as it was.

## 5. Known gaps

Worth knowing before you go hunting, all about the services added around the engine:

- **`users` serves gRPC on 9191 and nothing calls it.** Unauthenticated, inside the namespace only.
  Do not put it behind an ingress as it stands.
- **`ia-control-plane` serves `/internal/agents/{id}/config` with an API key in the clear.** Same
  shape of risk, higher stakes. It has no gateway route and must never get one; the protection is
  that absence plus the Service being cluster-internal.
- **The agent depends on the control plane to start serving.** A pod that has never reached it
  reports readiness DOWN, on purpose: it has no model. Losing the control plane later does *not*
  take the panel down — the agent keeps serving the last configuration it fetched and says
  `degraded` in `/actuator/health`. If the chat panel is 503, look at `ia-control-plane` and
  `cp-postgres` before looking at the agent.
