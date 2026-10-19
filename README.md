# ec-demo1 — EventConductor on Kubernetes

A complete EventConductor deployment: the engine, a human-task front end, an identity provider,
a message broker, a database and a full observability stack — running on a CloudFleet/Hetzner
cluster, deployable from an empty cluster with one script.

The definitions live in a repository of their own,
[`ec-definitions`](https://github.com/miguelperezcolom/ec-definitions): the orchestrator, the forms
engine and the rule engine each clone it at startup and import what they find under `definitions/`,
so a process is changed by a pull request rather than by an API call.

```
                         https://ec1.mateu.io
                                 │
                        ingress-nginx (TLS, Let's Encrypt)
                                 │
                            gateway  ── requires a Keycloak token on every backend path
      ┌──────────┬───────────────┴─┬──────────┬────────┬──────┐
      │          │                 │          │        │      │
 /_workflow  /_forms          /_booking  /_content    /ai     /**
      │          │                 │          │        │      │
 orchestrator  forms            booking    content  ia-agent  shell
      │          │                 │          │        │      │
      │          │                 │          │        │   the only
      │          │                 │          │        │   page a user
      │          │                 │          │        │   loads
      └── PostgreSQL ──────────────┴──────────┘        │
      └── Redpanda (Kafka) ────────┘                   │
                                   └──── MCP ──────────┘
                       (ia-agent calls the tools the engine and booking expose)

  https://console.ec1.mateu.io  the control console ── needs the `ai-admin` realm role
                                    │
                              (same gateway)
         ┌─────────────┬───────────┴─────┬───────────────────┬─────────────┐
         │             │                 │                   │             │
      /_ia-cp       /_users      /_workflow-admin      /_forms-admin      /**
         │             │                 │                   │             │
 ia-control-plane    users         orchestrator            forms     control-shell
         │                        the same two pods that answer
    cp-postgres                   /_workflow and /_forms above, through
    its own server                a second @UI each

      also on this host: /_integrations, /_mapping, /_mdm, /_audit, /_communication,
      /_registration-rules ── see "The control console"

  https://rw.ec1.mateu.io       the same demo console, rendered by Redwood
  https://rw-console.ec1.mateu.io  the same control console, rendered by Redwood
                                ── same gateway, same backends, same Keycloak clients: the only
                                   difference is one line in each shell's pom

  https://auth.ec1.mateu.io     Keycloak (realm ec-demo1, clients demo + control-plane)
  https://grafana.ec1.mateu.io  Grafana ── Prometheus · Loki · Tempo
  https://kafka.ec1.mateu.io    Redpanda Console ── the event stream itself
  https://front.ec1.mateu.io    the hotel's front office (MRU01) ── same gateway, a host of its own
  https://doc.ec1.mateu.io      the documentation site ── basic auth, not Keycloak
```

## What is in here

The modules are grouped by system. Each is still its own Maven project and its own image, named after
its folder (`systems/erp` builds `ec-demo1-erp`); the root `pom.xml` only aggregates them, so one
`mvn -DskipTests package` builds everything.

| | |
|---|---|
| **`systems/`** | **The systems of the chain — the ones the PoC stands in for, and the hotel's front office** |
| `systems/crs/booking/` | The CRS (Rumbo's role): bookings with rooms, guests, nightly rates and payments, priced from its own catalog; a CRUD, an API, MCP tools and the worker side of the booking saga |
| `systems/erp/` | The ERP: the master of trading partners (tour operators, agencies, companies), with its own outbox |
| `systems/front-office/` | The hotel's front office (MRU01): stays, check-in, the kárdex, no shows and walk-ins |
| `systems/notices/` | The reception notices (Avisos) of a customer, a reservation or an agency, and when the desk sees them (before the arrival, check-in, in house, check-out). A reservation's and an agency's are made on the data plane's console; a customer's are Salesforce's, taken from the MDM. All of them go out on `notices`, where the front office keeps its copy |
| `systems/customer-history/` | A customer's stays in the chain, by the MDM's customer code: kept from the front office's closed stays (`StayClosed`), with the MDM's merges as aliases. The front office shows its summary when it recognises a customer at the check-in; the head office has the detail. First piece of the "vista 360" |
| `systems/loyalty/` | Riu Class, the loyalty programme — a demo of it: members, tier and points, which accrue from the closed stays. The front office asks it for a recognised customer's tier and points |
| `systems/pms/opera-mock/` | An OHIP double for the local end-to-end suite only; ec1 works against the chain's real tenant |
| **`integration/`** | **The ACL: what flows between the systems (data plane)** |
| `integration/crs-integration-service/` | The CRS-side adapter: inbox, reread, canonical reservation, event → process router |
| `integration/pms-integration-service/` | The connector: every call to OHIP, and the writes to the front office |
| `integration/mapping-service/` | The CRS → Opera code dictionary with approval, and the causes a process waits on |
| `integration/customer-mdm-service/` | The customer hub: identity resolution, cross-references, distribution; Salesforce cleans and decides |
| `integration/journey-service/` | A booking's journey across the chain, hop by hop, from its traces in Tempo, told in business words ("Ver recorrido" on the data plane). Owns no data |
| **`contracts/`** | **The language the services speak — [contracts/README.md](contracts/README.md)** |
| `contracts/contracts-*/` | One published-language library per context (reservation, customer, partner, mapping, integration, frontoffice, communication, audit, process, notices, registration); each service depends on the ones it speaks. No image |
| `contracts/schemas/` | Every Kafka topic's JSON Schema, versioned, generated from the records, with examples; producers validate against them, consumers parse their examples |
| `contracts/workers/` | The tasks each worker serves, generated from its registrations; `deploy/demo/check-contracts.sh` matches them against ec-definitions |
| **`control-plane/`** | **Who governs the flow** |
| `control-plane/integrations-service/` | One integration per hotel and its onboarding, gate by gate, to activation |
| `control-plane/audit-service/` | Who did what (F016) |
| `control-plane/communication-service/` | Notifications, recipients, the inbox and its badge |
| `control-plane/registration-rules/` | The kárdex's registration rules: which of a guest's data each destination's law requires (by the hotel's country or the hotel, nationality, age, role), kept by compliance on the control console and audited. They go out on `registration-rules`; every front office keeps its copy and applies them at check-in |
| `control-plane/users/` | Users, groups, roles and permissions, plus a gRPC endpoint that serves a user's roles and scopes |
| `control-plane/grpc-interface/` | The generated stubs for `users`' gRPC contract. Not an application; no image |
| **`consoles/`** | **What people see** |
| `consoles/gateway/` | Spring Cloud Gateway — routes the consoles and enforces the token |
| `consoles/shell/` | The data plane's shell, rendered by Vaadin (default build) and by Redwood (`-Predwood`: `io.mateu:mateu-redwood` in place of `io.mateu:mateu-vaadin`, and nothing else changed) |
| `consoles/control-shell/` | The control console's shell, on `console.ec1.mateu.io` and `rw-console.ec1.mateu.io` (the `-Predwood` build), behind the `ai-admin` role |
| **`ai/`** | **The agents** |
| `ai/ia-agent/` | The console's chat agent — an LLM that answers only by calling MCP tools |
| `ai/ia-control-plane/` | The catalogues the agent is configured from: LLMs and their credentials, MCP servers, APIs offered as MCP servers, RAG sources, and the agents that compose them |
| `ai/api-mcp/` | Serves the catalogued APIs as MCP servers, one endpoint per entry. Owns no data and no screens |
| **`supporting/`** | |
| `supporting/content/` | Content, labels and content types — a CRUD and nothing else |
| `supporting/ui-commons/` | What the UIs share, a library with no image: paging (in the database, and in memory for rows that are not in one), the signed-in user widget of the consoles and the front office, the "other systems" link tables, and the base class of the catalogue CRUDs |
| **the rest** | |
| `e2e/` | Playwright against the deployed cluster: the four consoles (`tests/`), the PoC demo end to end (`demo/`, `npm run demo`); plus `poc-acl-local/`, the PoC on one machine against `opera-mock`, and `poc-acl-demo/`, the demo's booking seed |
| `deploy/chart/eventconductor/` | The engine's Helm chart, vendored (see `VENDORED.md`) |
| `deploy/manifests/` | Keycloak, the postfix mail relay, the embeddings pod, the shells, the gateway, every service, Kafka console, ingress, certificate issuers |
| `deploy/observability/` | Helm values for Prometheus, Grafana, Loki, Tempo and Alloy |
| `deploy/deploy.sh` | The whole thing, from an empty cluster |
| `docs/`, `gitops/` | The PoC's documents; an example of the IA catalogue kept in git |

New here? **[ONBOARDING.md](ONBOARDING.md)** — access, what lives in which repository, and the
five things about this cluster that otherwise cost an afternoon.

**The documentation site** — the whole project explained, in Spanish — is in [`doc/`](doc/README.md)
(Astro + Starlight, like EventConductor's and Mateu's) and served at https://doc.ec1.mateu.io, behind
a username and password (`riu`, and `oracle` for Oracle; passwords in `deploy/.secrets/credentials.env` as `DOCS_PASSWORD` and `DOCS_ORACLE_PASSWORD`).

## Deploy

```sh
./deploy/build-images.sh TAG  # the 25 images this repo owns → Docker Hub (only when their code changed)
./deploy/deploy.sh            # everything else, idempotent
```

`build-images.sh` builds 22 modules — the two shells twice, Vaadin and Redwood — plus the
documentation site (tagged `DOCS_TAG`): 25 images, all under the one `TAG` you pass, so pass it
explicitly. A routine change is usually a few modules with a new tag each, not the whole script.

Between the two, the DNS records have to exist, pointing at the address `deploy.sh` prints after
installing the ingress controller: `ec1` and `*.ec1` under your domain. Two records rather than
five, because the wildcard covers `auth.ec1`, `grafana.ec1`, `kafka.ec1`, `console.ec1` and
whatever gets added next. The wildcard is in DNS only — each host still gets its own certificate,
issued automatically by HTTP-01 once the name resolves.

Passwords are generated on the first run into `deploy/.secrets/credentials.env`, which is
git-ignored. The demo user is `demo` / `demo`, from the realm file.

## The demo, in one pass

The demo is the CRS-PMS integration PoC: onboarding a hotel's integration, its reservations
reaching Opera and the front office, a customer's change going through Salesforce, a no-show. The
script, step by step, is [`docs/poc-acl/demo.md`](docs/poc-acl/demo.md); `deploy/demo/` has what
takes ec1 back to its baseline (`reset.sh`) or to before anything was integrated (`zero.sh`).

The engine runs only that PoC's processes, imported from
[ec-definitions](https://github.com/miguelperezcolom/ec-definitions): `alta-integracion`,
`proyectar-reserva`, `proyectar-cancelacion`, `proyectar-interlocutor` and `registrar-no-show`; and the
pms-fo side's `alta-integracion-fo`, `proyectar-estancia` and the reception up to the PMS —
`registrar-checkin`, `registrar-checkout`, `registrar-no-show-pms` (which reports the no-show on to the
CRS: `registrar-no-show`), and the desk's charges onto the PMS's folio, `registrar-cargo` and
`anular-cargo`. A
new reservation starts exactly one of them — one `proyectar-reserva` — because the CRS makes it
confirmed and with its payments in one change, one event. The engine's own
examples (`order-fulfilment`, the sagas) were removed from it on 2026-09-25.

Two places to look while it runs: **Workflow → Processes** on the control console
(`https://console.ec1.mateu.io`), and the events themselves at `https://kafka.ec1.mateu.io` —
`upstream` (what was asked of the engine), `outbox` (every state change it recorded),
`downstream` (tasks for workers). Grafana has the logs of every pod (Loki) and the engine's metrics
(Prometheus).

## The services around the engine

Five applications that are not the engine, each one a pod, each one reached through the gateway on
a path of its own. They came from the `demo/` tree of the engine's own repository; what follows is
what they are here, not what they were there.

| | path | what it is |
|---|---|---|
| `booking` | `/_booking` | The CRS the CRS-PMS integration PoC projects from (see `docs/poc-acl/`): bookings with rooms, guests, nightly rates and payments, priced from its own catalog. A CRUD, a REST API (`/bookings`, `/catalog`, OpenAPI at `/v3/api-docs`), an MCP server, and a worker. One pod on purpose: the tools the agent calls, the API the integration reads and the saga steps that confirm a booking act on the same aggregate |
| `content` | `/_content` | Content, labels and content types. A CRUD over its own database and nothing else |
| `users` | `/_users` | Users, groups, roles and permissions, plus `GetAuthInfo` over gRPC on 9191 |
| `ia-agent` | `/ai` | The chat panel's other half. It implements nothing: every answer is an MCP tool call or a refusal |
| `api-mcp` | — | Not routed by the gateway, on purpose: it calls other people's APIs with stored credentials, and an endpoint here is a way to make it do that. Reachable only from inside the namespace, which is where `ia-agent` is |

**They keep their own UIs.** Nothing about their screens is written in the shell — each declares a
`@UI` path, `ShellHome` names the same path in a `RemoteMenu`, and the gateway routes it. Adding
the fifth is those three lines and a manifest, and the path has to match in all three or the menu
renders empty.

**A database each, not a schema in the engine's.** The engine owns `workflow` through Flyway and
validates its schema against its own migration history at startup; three services running
`ddl-auto: update` inside it would be three writers with no shared history. `55-demo-db-init.yaml`
creates `booking`, `content` and `users` in the same PostgreSQL, the same way Keycloak's database
is created. They live on that PostgreSQL's volume (`hcloud-volumes`), so they survive the pod moving;
deleting the PVC is what loses them.

**Only `booking` touches Kafka.** It consumes the `booking` topic and replies on `upstream` — one
consumer group of its own, subscribed to one topic, which is the arrangement the engine's own
configuration argues for at length. It also publishes, through a transactional outbox, what
happens to its bookings on `crs-bookings` — `booking-created`, `booking-modified`,
`booking-cancelled`, keyed by booking id and carrying the booking's version, not its data — so none
leaves unless the booking was saved. `content` and `users` arrived declaring a binder and three
bindings (`consumeOutbox`, `consumeUpstream`, `consumeWorkerEvent`) and implementing none of them,
under the engine's own group names — `orchestrator-outbox`, `orchestrator-upstream`,
`worker-group`. Deployed beside a real engine that would have taken partitions away from the
orchestrator and the test worker and dropped whatever it was handed. It is gone, not disabled.

### The agent

The chat panel posts to `/ai/api/agent/stream`. The agent asks the control plane which model,
which credential, which system prompt and which MCP servers; opens a fresh connection to each of
those servers — per prompt, not pooled, because Spring AI's auto-configured client holds one
persistent SSE connection and stays broken for the life of the pod once it drops — collects
whatever tools they advertise, and hands them to the LLM.

**It holds no configuration of its own.** Two environment variables, and that is the lot: where the
control plane is, and which agent it is.

The tools are the whole of what it can do. There is no path from a prompt to this deployment that
does not go through a server that chose to expose it, and the system prompt tells it to report a
tool failure rather than answer around it.

Two things follow from it being an LLM:

- **Every prompt is billed** to whatever credential the control plane serves. That is why the
  gateway requires a Keycloak token on `/ai/**` and not only on the CRUD paths. Mateu's chat client
  sends the bearer token itself, so requiring it costs nothing.
- **The key is not generated.** `deploy.sh` writes a commented `ANTHROPIC_API_KEY=` line into
  `deploy/.secrets/credentials.env`. If it is filled in, the control plane seeds its LLM with it;
  if not, the model is catalogued with `credential: missing` and the console is where you fix that.

The seeded model is `claude-sonnet-4-5`, a generation behind what the API offers. Changing it is
a catalogue entry rather than a Deployment variable — which is most of what the control plane is
for. On ec1 the catalogues come from git: GitOps is on, from
[`ec-ia-config`](https://github.com/miguelperezcolom/ec-ia-config) (`71-ia-control-plane.yaml`), so
the seeder stands down and the model, prompts, MCP servers and documents are changed by a pull
request there. An edit in the console to a git-managed entry is reverted on the next push; the
console is for quick fixes and for entries created there (see [Configuring it from git](#configuring-it-from-git-gitops) below).

### Observing the agents

`ia-agent` and `ia-control-plane` send traces to Tempo the way the engines do (`OTEL_SERVICE_NAME`,
`OTLP_TRACING_ENDPOINT`, `TRACING_SAMPLING`), and Grafana has an **IA agents** dashboard
(`deploy/observability/dashboards/ia-agents.json`) next to **IA token usage**.

- **One trace per prompt.** `invoke_agent <agent>` is the root: its duration is the whole prompt,
  and it carries `gen_ai.agent.id`, the catalogue's LLM id and model, an outcome (`ia.outcome`:
  `success`, `refused`, `no_tools`, `error`) and the prompt's totals — `gen_ai.usage.input_tokens` /
  `output_tokens` summed over every model round trip, `ia.model.calls`, `ia.tool.calls` and
  `ia.tools.called`. Under it, `chat <model>` per round trip to the LLM (model, finish reason, its
  own `gen_ai.usage.*`) and `execute_tool <tool>` per tool call (`gen_ai.tool.name`,
  `gen_ai.tool.call.id`, `ia.tool.outcome` plus the span status, and where the tool lives:
  `ia.tool.source` = `mcp` with `ia.mcp.server` / `ia.mcp.server.url`, or `rag` with `ia.rag.id`).
  The MCP call carries the trace headers, so a server that traces (the engines) adds its span to
  the same trace; so does the control plane on resolve, RAG search and usage.
- **The conversation, in the trace — `IA_CAPTURE_CONTENT`** (`ia.observability.capture-content`).
  What was said lives in Tempo, never in Loki:

  | span | attributes |
  |---|---|
  | `invoke_agent` | `ia.user.message` (what the user wrote), `ia.agent.response` (the answer they got) |
  | `chat <model>` | `gen_ai.system_instructions`, `gen_ai.input.messages` (user, assistant tool calls, tool results), `gen_ai.output.messages` — JSON in the OpenTelemetry GenAI conventions' shape |
  | `execute_tool` | `gen_ai.tool.call.arguments`, `gen_ai.tool.call.result` |

  Three modes. **`none`** — the default in the code: no content, only tokens, durations, tools and
  outcomes. **`redacted`** — the content, with e-mails, phone numbers, card numbers (Luhn-checked),
  IBANs, DNI/NIE and passport numbers replaced by `[email]`, `[phone]`, `[card]`, `[iban]`, `[dni]`,
  `[nie]`, `[passport]` *before* export; booking and hotel codes, dates and amounts are left alone.
  It cannot see names or addresses — it masks what has a recognisable shape. **`full`** — as it is.
  Every value is cut at `IA_CAPTURE_MAX_CHARS` (16384) characters with a `…[truncated: N chars]`
  note, and Tempo's `max_attribute_bytes` is raised to 65536 in `deploy/observability/tempo.yaml`
  so Tempo (2 KB by default, silently) never cuts first.

  > **Personal data.** Anything but `none` stores customer data — names, e-mails, phones, bookings —
  > in Tempo for its retention (24h here), readable by anyone with Grafana. The manifest sets
  > **`full`** because this is a demo; for real data use `redacted`, or `none`.

  All content goes through one class, `ContentCapture`, which applies the mode and the limit.
  Spring AI's own switches therefore stay off: in 2.0.1 `spring.ai.chat.observations.log-prompt` /
  `log-completion`, `spring.ai.chat.client.observations.*` and `include-error-logging` only write to
  the *log*, and `spring.ai.tools.observations.include-content` writes a tool's arguments and result
  to the span unredacted and untruncated. The agent's INFO logs say what happened (session, message
  length, tool, server, duration, result size); the message and tool input/output are at DEBUG.
- **Where to look.** Grafana → **IA agents** → *Recent requests*: one row per prompt with agent,
  model, duration, tokens, tool calls, outcome and — when captured — the start of the prompt and
  the answer (inspect a cell for all of it); the span ID opens the whole trace. *Recent tool calls*:
  tool, agent, source/MCP server, duration, outcome, arguments and result. Or Explore → Tempo with
  TraceQL, e.g. `{resource.service.name="ia-agent" && span.ia.outcome != "success"}`.
- **Metrics**, scraped from `/actuator/prometheus`: `ia_agent_prompt_seconds` (per prompt),
  `gen_ai_client_operation_seconds` and `gen_ai_client_token_usage_total` (per model call),
  `spring_ai_tool_seconds` (per tool, `spring_ai_tool_definition_name`), all labelled with
  `gen_ai_agent_id` and with histogram buckets for p50/p95. `ia_tokens_total` still comes from the
  control plane. There is no cost panel: the LLM catalogue has no price per model.
- **The agent id is copied down, not native.** Spring AI's observations know the model and the tool
  but not the agent; an `ObservationFilter` (`AgentObservability`) takes it from the enclosing
  `invoke_agent` observation. The chat models are built by hand per credential, so they only
  observe anything because `ChatClientRegistry` hands them the `ObservationRegistry` — a model
  built without it gets the no-op one, silently.

### Watching the external APIs (Salesforce, Opera)

The Salesforce org is a Base Edition: 15,000 API calls in a rolling 24 h, shared by everyone using
it — ec1, any local stack with the same credentials, scripts, people. A local MDM polling every 20 s
once spent all of it. So every call is counted where it is made:

- **customer-mdm-service** tags each Salesforce call with its purpose (projection, change-case,
  decisions, reason, refresh, poll, merge, consolidation-read/write, notice-write, notice-poll, limits,
  token) — hourly buckets
  saved in `salesforce_api_calls`, and `salesforce_api_calls_total{purpose,outcome}` — and keeps the
  org's total from the `Sforce-Limit-Info` header (`salesforce_org_api_used/max`,
  `salesforce_budget_paused`). If no answer said it for 15 min it asks `/limits`, one call. It warns at
  startup when `mdm.poll` is under 5 min. `GET /usage/salesforce` (in-cluster only).
- **pms-integration-service** counts every OHIP call by module and endpoint with an interceptor on
  `OhipClient` (`opera_api_calls_total{service,purpose,outcome}`, 429 = `limited`) and keeps any
  rate-limit header OHIP sends. `GET /usage/opera`.
- **integrations-service** adds them up (`integrations.usage.*`, cached 15 s) and serves, under
  `/_api-usage` on every console host: the home pages' tiles (calls left, the trend
  against an hour ago, our pace, when it was seen, the pause) and the page behind them. A page view
  costs Salesforce nothing.
- Grafana: **External APIs** (`deploy/observability/dashboards/external-apis.json`); alerts in
  `deploy/observability/prometheus-rules.yaml` (org ≥ 80 % / ≥ 95 % or paused, our pace projected over
  10k/day, others spending > 5k, OHIP 429s, Opera or Salesforce not answering, a service down, a pod
  crash-looping). Alertmanager sends them to communication-service (`POST /alerts/alertmanager`,
  cluster-internal), which puts them in the administrators' inbox as PLATFORM_ALERT /
  PLATFORM_ALERT_CRITICAL and closes them when they resolve. `deploy/observability/test-rules.sh` runs
  the promtool tests.

### Propagating users to Keycloak

`users` is the source of truth for who a person is; Keycloak holds the copy that authenticates
them. Creating, editing or deleting a user here has to reach there, or the two drift — a user
edited in the console but not in Keycloak signs in under stale details, and one deleted here but
left there can still sign in at all. So every create, update and delete is propagated.

**It is identity only.** Username — which is this service's own user id — email, display name, and
whether the account is enabled. Roles and scopes are deliberately not sent: this service stays the
one place they are decided, and the gateway is meant to read them from it to enrich a token (the
gRPC half of that is [not yet wired](#notes-worth-knowing)). Copying them into Keycloak as well
would make two places that disagree.

**It goes through a transactional outbox, not a direct call.** Saving the user and calling Keycloak
cannot commit together — one is Postgres, the other an HTTP API — so the use case does not call
Keycloak at all. It writes the change to the shared outbox (`supporting/messaging`: the
`outbox_message` table, destination `identity`) *in the same transaction* as the user: either both
land or neither does, and there is no window where the user is saved but the intent to propagate is
lost. (The old `identity_outbox` table only keeps its history.) The relay, every few seconds, drains it,
delivers each change, and marks it done; failures are retried with a growing backoff and abandoned
after ten attempts so a permanently-bad change stops blocking the queue behind it. Delivered rows
are kept a week as an audit of what was propagated, then purged.

**Delivery is at-least-once, so it is idempotent.** The relay may hand the same change over twice
(delivered, then died before recording it), which is why the Keycloak side is an upsert keyed on
username — `POST` if absent, `PUT` if present, a losing `POST` race treated as an update — and a
deletion of an already-absent user is a success, not an error. Exactly-once across a database and a
foreign API is not on offer; at-least-once plus idempotent is the arrangement that is.

**The credential it uses is broad, on purpose-for-now.** The relay authenticates to the Admin API
as the realm's bootstrap admin, reusing the `keycloak-admin` Secret rather than a client of its own.
It needs only `manage-users`; the bootstrap admin can do anything. The clean version is a
confidential `users-service` client with a service account granted exactly that role, and it was
left out because it puts a client secret into the committed realm import while the bootstrap
password is a deploy-time Secret. The seam is one method in `KeycloakAdminClient`. Until then the
blast radius of this pod's credential is a Secret, not a file in git.

A user created this way has no credential yet, so it cannot sign in until one is set. That is what
[the SMTP relay](#email-and-smtp) is for: on create, the user is marked *must set password* and
Keycloak emails them the link. It is best-effort — a mail failure is logged and never fails the
create — so a user always lands in Keycloak; whether the email went is a separate, visible outcome.

### Email and SMTP

Keycloak has to send mail — the set-password link above, and password resets after — and it needs
somewhere to send it through. That somewhere is a **postfix relay**: a small, send-only pod that
exists to take mail off Keycloak and hand it to Gmail.

**It relays; it does not deliver.** Keycloak talks plain SMTP to `postfix:25` inside the cluster —
no TLS, no auth, because the hop never leaves the pod network — and postfix forwards everything to
`smtp.gmail.com:587` over an authenticated, encrypted connection. The split is the point. The one
secret, the Gmail App Password, lives only in postfix (the `postfix-relay` Secret), so the realm
import that points Keycloak at it carries **no credential and stays in version control** with
`auth: false`. And the actual delivery is Gmail's, with Gmail's reputation, rather than a cluster IP
that every provider would sink as spam.

**The App Password is pasted, not generated.** Like the Anthropic key, `deploy.sh` writes a
commented `POSTFIX_RELAY_PASSWORD=` line into `deploy/.secrets/credentials.env`. It needs a Google
account with 2-Step Verification on (a normal password will not authenticate over SMTP), and for
`miguel@mateu.io` that is the Workspace account for the domain. Without it, postfix starts but Gmail
refuses the relay and mail queues; user create/update/delete is unaffected, because that path does
not touch mail.

**DNS is what keeps the mail out of spam.** Because the envelope leaves through Gmail as an
authenticated `@mateu.io` sender, deliverability rides on the domain's existing Google email
authentication. For a Workspace domain this is normally already in place; verify, and add what is
missing:

| record | type | value | why |
|---|---|---|---|
| `mateu.io` | TXT (SPF) | `v=spf1 include:_spf.google.com ~all` | authorizes Google's servers to send as `@mateu.io` |
| `google._domainkey.mateu.io` | TXT (DKIM) | the key from Workspace → Apps → Gmail → *Authenticate email* | lets Google sign the mail so receivers can verify it |
| `_dmarc.mateu.io` | TXT (DMARC) | `v=DMARC1; p=none; rua=mailto:miguel@mateu.io` | asks receivers to report, and sets a policy once SPF/DKIM pass |

No `MX` change is needed — that governs *receiving*, and this relay only sends. If the account is a
personal `@gmail.com` with `mateu.io` as a "send as" alias rather than a Workspace domain, Gmail
rewrites the envelope sender and the `From:` will not align with these records; the Workspace domain
is the arrangement that makes the table above true.

### What is not wired yet

`booking` is born confirmed: the CRS takes a booking and confirms it in the same change, with the
payments collected as it was made, so a new booking is one `booking-created` and one
`proyectar-reserva`. There used to be a `verify-booking-payment` process, started for every new
booking, that did nothing but confirm it at once — which was a second change, a second event and a
second projection down to Opera for every booking. It is gone, from here and from
[`ec-definitions`](https://github.com/miguelperezcolom/ec-definitions). If the CRS ever has to
verify a payment for real, that is a process started for the bookings that need it, not for all.
`booking`'s worker half answers `registrar-no-show`'s `register-no-show` step, on the `booking`
topic.

### The CRS → Opera integration PoC

Seven more pods take `booking`'s events down to Opera Cloud through OHIP's Property APIs, as the
engine definitions listed in [The demo, in one pass](#the-demo-in-one-pass) (twelve, in
`ec-definitions`):

| | path | what it is |
|---|---|---|
| `erp` | `/_erp` | The ERP: the master of partners (tour operators, agencies, companies), with its own outbox. Its database is still `partners` |
| `crs-integration-service` | — | The CRS-side adapter: inbox, reread, canonical reservation, event → process router |
| `mapping-service` | `/_mapping` | CRS → Opera code dictionary with approval, the causes a process waits on, MCP tools for the mapping agent |
| `pms-integration-service` | — | **The connector**: every call to OHIP, with the version guard in a UDF and idempotent writes |
| `opera-mock` | — | An OHIP double with validation, availability and injectable faults, for the local end-to-end suite only (`e2e/poc-acl-local`). Not deployed: ec1 works against the chain's OHIP tenant |
| `communication-service` | `/_communication` | Who hears of what, and where: the recipients table routes every notification to inboxes, browsers (Web Push), e-mail and Google Chat; the inbox and its badge |
| `customer-mdm-service` | `/_customers` · `/_mdm` | The customer master (golden record, identity resolution; Salesforce cleans and decides). Two `@UI`s: **Clientes** on the data plane (`/_customers`: find a customer; its data, where it is known, its reservations in the CRS and the front office, its change requests — read-only, with links to each system) and its technical screens on the control console (`/_mdm`: golden records, consolidations) |
| `integrations-service` | `/_integrations` | One integration per hotel: its Opera connection (secret sealed) and its onboarding, gate by gate, to activation. Until then the hotel's reservations wait |

**ec1 writes to the chain's OHIP UAT tenant** (property XMAR): reservations and guest profiles, and
the desk's check-ins, check-outs and charges; partners are read there, never written. The OHIP
credentials go to `deploy/.secrets/` and a Secret, never to a values file. The plan, the cost log and the conclusions
are in [`docs/poc-acl/`](docs/poc-acl/); the path runs end to end on one machine with
[`e2e/poc-acl-local/`](e2e/poc-acl-local/README.md).

## The control console

A second console, on a host of its own: **`https://console.ec1.mateu.io`**, behind the `ai-admin`
realm role. These answer on it:

| section | pod | what it is |
|---|---|---|
| **IA** | `ia-control-plane` | The four catalogues the chat agent is configured from — below |
| **Usuarios** | `users` | Users, groups, roles and permissions. Moved here from the demo console: administering access is not part of using the product |
| **Workflow** · **Forms** | `orchestrator`, `forms` | Workflow definitions and analytics, and the form definitions with their editor. The same two pods that serve the demo console, each through a second `@UI` |
| **Integrations** · **Mapping** · **Notifications** | `integrations-service`, `mapping-service`, `communication-service` | The PoC's control plane: each hotel's integration and its onboarding; the dictionary, causes and proposals; recipients and the inbox (`/_integrations`, `/_mapping`, `/_communication`) |
| **Customers** · **Audit** | `customer-mdm-service`, `audit-service` | The customer master's technical screens (`/_mdm`) and who did what (`/_audit`) |
| **Registro** | `registration-rules` | The kárdex's registration rules (`/_registration-rules`) |

The last row is the one worth reading twice. `orchestrator` and `forms` are not deployed a second
time; each declares two `@UI`s — `/_workflow` and `/_workflow-admin`, `/_forms` and `/_forms-admin`
— and the gateway routes the plain pair on the demo host and the `-admin` pair here. Two front
doors into one pod, and the host is what decides which role is required at each.

That is how the split can exist at all: a `RemoteMenu` mounts the whole menu a pod declares and
nothing less, so a shell cannot show half of one. The engine had to offer two menus for a
deployment to be able to put them in two places.

**The routes underneath did not change.** `/workflow/definitions` is still
`/workflow/definitions` — Mateu builds a menu entry's route from the field name, and the
administration menus keep the names they were split from. A deep link, or a `[NAVIGATE:]` block the
chat agent holds, kept working across the move without being told.

**What stayed on the demo console** is the work itself: processes and step executions, form
executions and the task lists. The line is the same one that moved Usuarios — configuration and
measurement here, doing the job there.

### The catalogues

`ia-control-plane` holds the four the chat agent is configured from, and one more that is
catalogued here before anything serves it.

| catalogue | what an entry is |
|---|---|
| **LLMs** | A model this deployment may call, and the API key that pays for it |
| **MCP servers** | A server an agent may be given the tools of. Not the tools — those the server declares at connection time, and a copy here would go stale in silence |
| **APIs as MCP servers** | An existing API, with a chosen set of its operations named and described as tools. The mirror image of the row above: here the tool list *is* the entry, because nothing else knows it — the offer is composed by an operator rather than declared by a server |
| **RAG sources** | A vector store, a collection inside it, and the model that embedded it. Searchable — see below |
| **Agents** | A prompt, one LLM, and the servers and sources it may reach. The only thing a running service is ever handed |

An agent refers to its LLM, its servers and its sources by id and holds nothing of them, so a
server's URL changes in one place and every agent composed from it follows.

**Where an API-backed entry becomes a real server.** The catalogue holds the OFFER — which
operations, under which names, described how — and `api-mcp` holds the TRANSLATION: it reads the
OpenAPI document with a real parser, builds each tool's input schema from it, and makes the call.
The split is why the control plane carries no OpenAPI library and is not on the path every tool
call travels.

An operator then catalogues `http://api-mcp:8113/<entry id>/sse` as an ordinary **MCP server** and
composes an agent from it. Nothing on the agent's side learns that this entry is different from
`booking` — which is the point of serving it as MCP rather than teaching the agent a second kind of
tool.

One thing is deliberately **not** served yet: a tool that declares required roles. The catalogue
says those are checked where the call is made, and that is `api-mcp` — but the MCP transport hands
a tool handler no HTTP request, so there is nothing there to read a caller's roles from. Rather
than offer a narrowed tool with the narrowing silently dropped, `api-mcp` refuses to expose it and
says so in its log. A tool with an empty role list is served normally.

### Why a second host and not another menu

Two hosts mean two Keycloak clients, and that is the whole reason. A token minted for the demo
console is not a token for this one, so the gateway can demand `ai-admin` here and leave the demo
console alone. Both still enter through the same gateway, so there is still one place that checks
a token before any backend sees a request — and one place, `SecurityConfig.java`, where that rule
is written.

The realm defines three roles: `user` and `admin` for the demo console, and `ai-admin` for this
one. It is a role of its own and not the demo's `admin` on purpose — this host reaches the LLM API
keys and the demo's `admin` never does, so the two are separate grants and neither implies the
other. That is what lets a platform admin hold `admin` without being able to read a credential. The
`demo` user carries all three, because a single demo login is meant to reach everything; a real
deployment would split them.

**One thing `ai-admin` now grants that it did not**: reading the workflow and form definitions, and
the engine's analytics, since those moved to this host. It still cannot start, cancel or retry
anything — processes, steps and the task lists are on the demo console — so the grant is read
access to configuration, not the ability to drive the engine. If that is the wrong grant for a
deployment, the fix is a fourth role on the `onControlHost` matcher rather than a fourth host: the
paths are already separate. A new public client `control-plane` is in the realm file, with `directAccessGrantsEnabled`
off — unlike the demo client's — because trading a username and password for a token with no
browser is not a convenience anyone needs on the client that reaches the credentials.

### Why it has a database of its own

History, mostly. When the control plane was built, `postgres.localDisk: true` in
`deploy/values/eventconductor.yaml` put the engine's PostgreSQL on an `emptyDir` — local NVMe, for
the benchmark this deployment was first built for — and its data died with the pod. That was the
wrong trade for the only copy of this deployment's LLM credentials, so the control plane got a
second, small PostgreSQL of its own: `cp-postgres`, 10Gi on `hcloud-volumes`, the same storage class
Redpanda already uses.

Since 2026-09-25 the engine's PostgreSQL is on a volume too (`localDisk: false`, `hcloud-volumes`),
so both survive a pod move; `cp-postgres` stays separate.

### The credentials

Stored AES-256-GCM encrypted, with the key in the `ec-cp-crypto` secret and held nowhere else. In
the console the field is **write-only**: it shows `set` or `missing`, never the key, and saving the
edit form does not touch it — `UpdateLlmCommand` has no field for a credential, so replacing one is
a separate, confirmed action. That is not ceremony: a write-only field wired through an ordinary
update is how a working key gets blanked by someone changing a temperature.

Exactly one method in the service decrypts anything, and one endpoint serves the result:

```
GET /internal/agents/{agentId}/config     →  the resolved agent, API key in the clear
```

**It has no gateway route and must never get one.** The gateway routes `/_ia-cp/**` to this
service and nothing else; `/internal/**` on either host falls through to a shell's catch-all and
404s. Verified rather than assumed — a request for that path through the gateway reaches the
shell, not the control plane. The endpoint authenticates nothing itself, exactly like the users
service's gRPC port, and the same warning applies with more force.

What this protects: a database dump, a stolen volume snapshot, a backup, anyone with read access
to the table, and — the common case — a key appearing on a screen, in a listing, or in a log line.
What it does not protect against: someone holding both the database and `CP_CRYPTO_KEY`, which
includes anyone who can exec into the pod. That is the honest boundary.

**Rotating `CP_CRYPTO_KEY` makes every stored credential undecryptable.** Nothing re-wraps them, so
rotation means entering the keys again. `deploy.sh` generates it once into
`deploy/.secrets/credentials.env` and then leaves it alone, like the PostgreSQL passwords.

### Configuring it from git (GitOps)

The console is one way to change the catalogues; a git repo is the other. Turned on, the control
plane reads the four catalogues from YAML in a GitHub repo — one entry per file, a `kind` field
saying which catalogue — and reconciles itself to match whenever the repo changes. Off by default;
a deployment that does not set `GITOPS_ENABLED` and a repo is untouched. The schema, the layout and
the editor setup live in [`gitops/`](gitops/README.md).

**Git owns only what git created, and that is the whole of the model.** Each entry the reconciler
writes is recorded as git-managed. Remove its file and the next sync deletes it; but an entry made
in the console is in no such record, so a sync never touches it — not overwritten, not deleted, only
removable from the console. The two coexist because they are for different things: the console is
for trying a configuration out and quick fixes, the repo is the durable source. This is why there is
a small `gitops_managed` table beside the catalogues rather than a `source` column on each — the
provenance is the reconciler's concern, and keeping it out of the aggregates left them untouched.

**A push drives it, not a clock.** GitHub calls a webhook at `/cp-webhooks/github` on the control
host — public, because GitHub cannot carry an `ai-admin` token, and verified instead by HMAC over the
body with a shared secret, exactly like the engine's git webhooks. A reconcile also runs once at
startup, to catch a pod up on what it missed. The periodic poll is deliberately **off by default**:
its only job is to re-assert the repo on a timer, which would undo a console quick-fix before the
next push — so it exists for those who want drift corrected and stays out of the way otherwise.

**Secrets never enter the repo.** An LLM entry names an env var in `credentialEnv`; the control plane
resolves it from its own Secret at sync time and sets the credential through the same use case the
console does. The repo, public or private, holds only which env to read — never a key. The read
token for a private repo and the webhook's HMAC secret are the deployment's, in the `cp-gitops`
Secret; the fetch is all-or-nothing, so a half-failed read never reads as "the repo was emptied" and
deletes the catalogue.

**The seeder stands down when this is on.** Git provides the content, so seeding would only create
console-owned entries the repo then cannot manage — a confusing half-state. With GitOps enabled the
seeder logs that it is leaving the catalogues to git and does nothing.

### Resolving an agent degrades rather than fails

An agent composed months ago may name an MCP server since disabled, or one since deleted. Refusing
to serve the whole configuration would take a chat panel down over a missing tool, so the missing
pieces are dropped and reported:

```json
{ "llm": { "model": "claude-opus-5", "apiKey": "..." },
  "mcps": [ { "name": "Booking service", "url": "http://booking:8108" } ],
  "warnings": [ "MCP 'Forms engine' is disabled — skipped",
                "MCP 'ghost' is no longer in the catalogue — skipped" ] }
```

A missing or unusable **LLM** is the exception — there is no degraded mode without a model — and it
answers 409 saying which of the two it was, `disabled` or `missing its credential`, because the fix
differs. *Agents → Preview resolved configuration* runs exactly this and shows the warnings, which
is the only way a dropped server is visible before a user notices the agent got less capable.

### How the agent reads it

`ia-agent` has no model, no credential, no prompt and no server list of its own. It fetches an
agent's configuration and answers with what came back.

**Cached for 30 seconds, and the last good copy outlives the control plane.** Two separate
decisions. The cache keeps the control plane off the hot path — a burst of prompts is one fetch —
and 30 seconds is short enough that changing a model in the console lands within a prompt or two.
Serving the stale copy when a refresh fails is the more important half: a chat panel must not go
down because a catalogue is briefly unreachable, and the configuration from a minute ago is almost
certainly still right. Every stale answer is logged at warn and shows up in the pod's health
details, so "running on stale configuration" is visible rather than silent.

**Readiness follows the configuration.** A pod that has never reached the control plane reports
DOWN and stays out of the Service's endpoints — it has no model, so letting it answer would only
produce errors more slowly. Losing the control plane *later* does not do that: the pod stays UP
with a `degraded` detail. Liveness deliberately ignores all of this, because restarting a container
because another service is unreachable fixes nothing and throws away the cache that was keeping it
working.

**The readiness probe is also the refresh loop**, which is not obvious and is load-bearing: without
it, nothing would fetch until a prompt arrived, and no prompt can arrive while readiness is DOWN.
That deadlock was real, and it is what the probe now breaks.

**There is no fallback to local configuration.** A second source of truth that appears only when
the first is unreachable is how two configurations quietly diverge.

**One asymmetry in Spring AI shapes the code.** Model, temperature and max-tokens can be overridden
per request; an API key cannot — it lives inside `AnthropicApi`, which is constructor-injected into
the chat model. So a rotated credential is not a parameter change but a new client, and
`ChatClientRegistry` caches one per (provider, base URL, key). Changing a model builds nothing.

**A brand-new deployment seeds itself.** Moving the configuration out of a properties file would
otherwise mean `deploy.sh` producing a chat panel that does nothing until somebody typed four
things into a console. So `CatalogueSeeder` writes this deployment's own agent — an Anthropic LLM,
the three MCP servers, and `console-agent` composing them — **only when all four catalogues are
empty**. Not create-if-missing per entry: that would resurrect an MCP server someone deliberately
deleted, on every restart.

### Retrieval

A RAG source is not just catalogued: it can be written to and read from, and an agent composed with
one gets a tool for it.

**The retrieval happens in the control plane, not in the agent.** That is the one place in this
design where the control plane is on a data path, so it is worth saying why rather than leaving it
to be discovered. Searching needs two things — the store's connection and the embedding model's
credential — and both are already here. Doing it in the agent would mean sending a second
credential to a service that only needs an answer, and putting the same pgvector and embedding
plumbing in two modules, because ingestion needs exactly the same two things and is unambiguously
an admin action. What it costs: if the control plane is down, RAG tools fail. The chat panel does
not, because the agent caches its configuration and its MCP tools do not come through here. An
outage costs the agent its documents, not its voice.

**In the agent, a RAG source becomes a tool.** Not a prompt stuffed with context: classic retrieval
embeds the question before the model sees it and pastes the results into the system prompt, which
retrieves on every turn whether or not the question has anything to do with the documents. As a
tool, the model decides — and this agent is already told to answer only by calling tools and to
report a tool failure rather than answering around it, so an empty or unreachable source produces a
sentence saying so. An MCP tool and a RAG tool are both `ToolCallback`s, so the model sees one list.

The source's **description in the catalogue is the tool's description**, which is what the model
reads when deciding whether to search it. A vague one produces a tool that is never called.

**Only `PGVECTOR` is implemented.** The other two kinds can be catalogued and are refused with a
sentence when queried. The table, its index and the `vector` extension are created on first use,
which is what lets a source be declared before it holds anything — and is why the database user in
the connection URL needs rights to create an extension.

**Getting content in** is *Content → Ingest text* on the source: paste, and it is split, embedded
and stored. Deliberately the smallest thing that makes the catalogue demonstrable rather than a
document pipeline — no crawler, no upload, no incremental sync, and not idempotent. A source whose
content is loaded by something else is exactly what the catalogue is for.

**A fresh deploy comes up with one source that already answers.** The seeder catalogues an *Ops
handbook* source, puts it on the agent, and ingests a small bundled corpus about running this
deployment — the same first-start, only-when-empty guard as the rest of the seed, so it runs once
and does not re-add on a restart. That is why the seeded source is on the agent from the start,
where an empty one would not be: ask the chat panel an operational question and the answer comes
from the handbook. Ingestion runs in the background and retries, because the embedding pod may still
be loading its model when the control plane starts.

**Embedding is local, and multilingual, so there is no second vendor.** The `embeddings` pod runs
Hugging Face Text Embeddings Inference with `intfloat/multilingual-e5-small`, an OpenAI-shaped
`/v1/embeddings` endpoint inside the cluster — so Spanish and English both embed and this
deployment's only paid credential stays the Anthropic one. TEI needs no key; the placeholder the
catalogue carries is there only because the pgvector store refuses to embed against a blank
credential. The `model` field it is sent is ignored — TEI serves whatever model it was started
with.

The store this deployment provides is `cp-postgres`, which runs `pgvector/pgvector:pg16` — the same
PostgreSQL as before plus the extension. So configuration tables and document vectors share a
database, which is the right size for a demo; a source pointed somewhere else is a field in the
console and no code at all.

**One dependency is conspicuously absent**: `spring-ai-openai`. It is built against Spring Framework
6 and calls `HttpHeaders.addAll(MultiValueMap)`, gone in Framework 7, so on this module's Boot 4 it
compiles and then dies at the first request with a `NoSuchMethodError`. The choice was to move the
whole module back to Boot 3.4 or to write the one POST it was being used for; `/v1/embeddings` takes
a model and a list of strings and returns vectors, so it is the second. `PgVectorStore` itself is
fine on Boot 4 — it speaks JDBC, not HTTP.

### What it does not do yet

**The id fields are text, not pickers.** An agent's MCP and RAG lists are comma-separated ids, so a
typo is not refused on save — it becomes a reference the resolver drops with a warning. The preview
button is what surfaces that.

## The same consoles, rendered twice

Four consoles, two planes, two renderers:

| | Vaadin | Redwood |
|---|---|---|
| **the product** | `ec1.mateu.io` | `rw.ec1.mateu.io` |
| **the platform** | `console.ec1.mateu.io` | `rw-console.ec1.mateu.io` |

The Redwood consoles are the same two shell modules built with **`-Predwood`**: that Maven profile
puts `io.mateu:mateu-redwood` on the classpath where the default build has `io.mateu:mateu-vaadin` (and
sets the port and a " · Redwood" in the tab's title). Nothing else about them is renderer-aware.
One module, two images — `ec-demo1-shell` and `ec-demo1-shell-redwood`, `ec-demo1-control-shell`
and `ec-demo1-control-shell-redwood` — and `deploy/build-images.sh` builds both, each with a
`clean package` of its own.

That works because a `RemoteMenu` carries **UIDL, not HTML**. The orchestrator, the forms engine
and the demo services answer with a description of a screen, and whichever renderer the shell
loaded draws it. So the Redwood consoles render the same backends, through the same gateway
routes, behind the same Keycloak clients, with no change on any of them. That is the contract this
deployment exists to show, and it is hard to believe until the two are open side by side against
one cluster.

**They run beside the Vaadin ones rather than replacing them**, because either alone is just an
application. And they make Mateu's renderer conformance suite concrete: what Redwood does not
cover paints a placeholder on a real screen here instead of in a fixture.

**No Keycloak clients of their own.** `@KeycloakSecured` names the Keycloak URL, not the app's own
host, so the `demo` and `control-plane` clients serve both consoles of their plane — each lists
both hosts in its redirect URIs. And no DNS to add: the `*.ec1` wildcard already covers them, and
each certificate is issued by HTTP-01 the first time its name resolves.

**One thing to know before adding a fifth console.** The gateway's backend routes name both hosts
of their plane (`Host=a,b`) and only the catch-all is per host — that is the whole of the routing
difference. But `SecurityConfig` holds a **set** of control hosts, and a console host missing from
that set does not fail closed: its `/_ia-cp` and `/_users` requests fall through to the catch-all
rule, which permits everything. The set is the security boundary, not the ingress.

## End-to-end coverage

```sh
cd e2e && npm install && npx playwright install chromium
npm test
```

Every screen of every console, on both planes and through both renderers, against the **deployed**
cluster rather than anything the suite starts itself — only a real deployment has all four
consoles, the gateway that routes them and the Keycloak that gates them.

The same expectations are asserted four times, and that repetition is the test: a screen that
renders under Vaadin and not under Redwood is a renderer gap; one that renders on one plane and
not the other is a routing or authorisation gap. Neither is visible from a single console.

Worth knowing about what it asserts. **Status codes prove nothing here** — Mateu answers a route it
cannot resolve with a fragment reading `Not found.` and an HTTP 200 — so a screen counts as
rendered only if it put something on screen and no renderer placeholder on it. And the menu bar is
read by walking shadow roots by hand rather than with a selector, because the two renderers nest
their components differently and a selector tuned to one returns nothing on the other, which would
make a renderer gap look like a passing test.

**The demo's storyline** is a second suite, `npm run demo` (`e2e/demo/`, `demo.config.ts`): the
script of `docs/poc-acl/demo.md` on ec1, checked where the demo shows it — Opera, the front office
(`front.ec1.mateu.io`), Salesforce and the consoles. It changes only what it creates, a new MRU01
reservation and the change on its holder. Two more directories are not Playwright:
`e2e/poc-acl-local/` runs the PoC on one machine against `opera-mock`
([README](e2e/poc-acl-local/README.md)), and `e2e/poc-acl-demo/seed.py` seeds a hotel's bookings in
the CRS for the demo.

## The node topology

One pool, in hel1, and Karpenter chooses the machines. Every workload is pinned only to the region
and to `amd64` (the images are single-arch, and Hetzner's CAX line is arm64); no instance type and
no anti-affinity. On 2026-09-25 that packed all of ec-demo1 onto one cx53, with observability on its
own ccx23 in fsn1, where its volumes are.

- **No instance type.** A pin to `cx43` left every pod `Pending` the day hel1 had none to sell —
  Karpenter says "no instance type ... had a required offering", which reads like a quota and is
  stock. Nor is every type in the catalogue: there is no `ccx33`. Probe with a throwaway pod.
- **The pods that break a demo when they restart are not consolidated.** Karpenter moves pods
  whenever it finds a cheaper packing; `karpenter.sh/do-not-disrupt` on the engine's PostgreSQL,
  Redpanda, the orchestrator, Keycloak, the gateway, the front office, `cp-postgres` and the ingress
  controller keeps the node they are on. It does not stop a node that goes away for another reason.
- **Both PostgreSQLs are on volumes** (`hcloud-volumes`): the engine's since 2026-09-25 — before it
  was an `emptyDir`, local NVMe for the benchmark, and it died with its pod — and `cp-postgres`.
  Deleting a PVC is what loses data now.
- **The ingress controller is in hel1 too,** and `ec1.mateu.io` / `*.ec1` point at the hel1 load
  balancer. CloudFleet creates one per region that has nodes; a region that loses its last node
  loses its load balancer, and the DNS must not point at it.
- **The fleet's CPU limit** (40) can only be changed through the CloudFleet Fleet API — `kubectl`
  is refused, NodePools included.

The benchmark this deployment was first built for — the engine alone on dedicated-vCPU nodes, a
load generator and the throughput it measured — is in the git history before that date.

## Notes worth knowing

- **Every step names its topic.** Nothing listens on `downstream`, the default destination for a
  step that names none: the test worker that used to answer it (`worker-standalone-app`, which
  played back a `TEST_CONFIG` instead of doing the work) was removed with its `/_worker` route
  and menu, since none of the PoC's processes used it. A step without a topic now waits forever.
  The forms engine listens on `forms`, and a `USER_TASK` reaches it only by naming `topic: forms`.
- **The shell's Keycloak URL is compiled in.** Mateu writes `@KeycloakSecured` into the generated
  bootstrap page, so it cannot be an environment variable yet. Changing the hostname means
  rebuilding the shell image.
- **The gateway is what protects the backends.** The orchestrator and the forms engine only
  understand HTTP basic auth, so their UIs — which can pause definitions and cancel
  processes — would otherwise be open to anyone who typed the path. The three demo services are
  worse: they authenticate nothing at all, and one of them arrived with a `permitAll()` chain and a
  `JwtDecoder` built from a hardcoded secret, which was removed rather than deployed. The chat
  agent is worse again, because an open prompt endpoint is a bill. The gateway validates the
  realm's access token on all six prefixes before any of them sees a request; see
  `SecurityConfig.java` for the paths that stay public, and why they have to.
- **`ddl-auto: update` never drops a column.** Renaming a JPA field adds the new column and leaves
  the old one, `NOT NULL`, and every insert then fails on a column no code mentions any more. It
  happened here while building the control plane — `topK` maps to `topk`, not `top_k`, so naming it
  explicitly meant both existed. A fresh deployment never sees it; an upgraded one needs the old
  column dropped by hand. Four services here run `ddl-auto: update` and none of them ships
  migrations, which is the trade: no migration history to maintain, and renames are manual.
- **`users`' gRPC port authenticates nothing.** 9191 is on its Service and on no ingress, so it is
  reachable from inside the namespace and nowhere else — and from there anything can ask it for any
  user's roles and scopes. Nothing in this deployment calls it; the gateway validates Keycloak's
  token directly rather than enriching it from here, which is what the demo's own api-gw used it
  for. It is carried because it is half of what that service is, and it must not be exposed as it
  stands.
- **The control console's Keycloak client is compiled into its shell**, like the demo shell's:
  Mateu bakes `@KeycloakSecured` into the generated bootstrap page, so the hostname, realm and
  client id cannot be environment variables yet. Changing any of them means rebuilding
  `control-shell`.
- **One replica of everything.** The fleet is capped at 40 CPU. The engine scales
  horizontally by design — raising `replicas` in `deploy/values/eventconductor.yaml` is the only
  change needed, since orchestrator instances coordinate through PostgreSQL advisory locks and
  the outbox rather than through a leader.
- **The rule engine runs, with nothing to run.** One replica, reading `definitions/rules` from
  `ec-definitions`, which has none: no process of the PoC has a `RULE` step.
- **Traces work as of engine 2.5.0** — `eventconductor.step-over`, `eventconductor.dispatch-step`,
  `outbox relay` and the rest arrive in Tempo. It took three releases, and the last one is worth
  knowing about: the endpoint was configured under `management.otlp.tracing.endpoint`, which Boot 4
  deprecates *at level error* — the property is no longer bound and its metadata entry survives
  only to announce that. It reads back perfectly from the environment, so every check short of
  looking for the exporter bean said it was configured, including one done here. `OTEL_SERVICE_NAME`
  is set per engine as well, without which every span arrives as `unknown_service` and the three
  are indistinguishable.
- **Git webhooks reload the definitions.** A push to `master` of `ec-definitions` calls
  `/workflow/webhooks/**` and `/forms/webhooks/**` (routed publicly; the engines verify the HMAC)
  and both engines re-import within seconds. They returned 500 until engine 2.2.1, whose jars carry
  the `MethodParameters` the webhook controllers need.
- **The Kafka console is behind HTTP basic auth**, because Redpanda Console's open-source build
  has no access control of its own and anyone who reaches it can produce and delete messages, not
  just read them. Its password is generated alongside the others.
- **Keycloak runs `start-dev`** behind the ingress, which is the right shape for a demo and not
  for production. It does keep its data in PostgreSQL, so accounts survive a restart.
