# Connect VS Code to the ec1 workflow engine

The connection is not deployed by editing VS Code settings. The engine needs the IDE execution
API and migration V58, and the gateway needs the `workflow-execution-api` route from this branch.
The configuration below is a deployment handoff, not evidence that ec1 has been updated.

## Connection settings

| VS Code prompt | Value after the server update |
| --- | --- |
| Command | `EventConductor: Configure Test Engine` |
| Test engine URL | `https://ec1.mateu.io/` |
| Authentication | `Bearer token` |
| Access token | A current Keycloak access token from an account with the realm role `admin` |
| Additional API key | Leave empty unless the engine administrator has configured one |

Use the account supplied by the deployment administrator to sign in to the web console. The
plugin does not exchange a username/password for a Keycloak token, and HTTP Basic is disabled
at this gateway. The workflow role `admin` is separate from `ai-admin`; the latter alone does
not authorize workflow execution. Do not infer a current password from an example in the repo.

For a manual connection without installing terminal tools, sign in at `https://ec1.mateu.io`,
open the browser developer tools' Network tab and open the process list in the console. Select
one of the console's authenticated `/_workflow/` requests and copy only the access token after
`Bearer ` in its `Authorization` request header into the plugin's hidden access-token prompt.
Do not share that token in chat, issues or screenshots. The token is stored in VS Code
SecretStorage. When it expires, obtain a current token and repeat Configure Test Engine; this
plugin version does not refresh Keycloak sessions automatically.

## Server changes for the deployment administrator

1. Build and publish an explicitly tagged orchestrator image from the engine checkout containing
   `WorkflowExecutionController` and `V58__ide_test_runs.sql` (`feat/vscode-workflow-execution`).
   The repository's ec1 values currently name `2.27.1`; changing a flag on that old image does
   not add the API. Validate the upgrade against the deployed Flyway history and preserve the
   existing database and volumes. The local test database is not a source for ec1's data.
2. Build and publish a tagged gateway image from this branch. It routes `/workflow/api/**` to
   the existing orchestrator, preserving paths, query strings and bearer authentication, and
   requires the realm role `admin` before a request can reach the engine.
3. Upgrade only the existing engine release and gateway, after reviewing the current cluster
   settings and the images. Keep ec1's existing gateway-based authentication; the overlay only
   enables IDE test runs. Do not run the full `deploy/deploy.sh` to make this narrow update.

The administrator supplies these values; they are not credentials:

```sh
# Set EC_REMOTE_CONTEXT to the existing CloudFleet context, and set both image variables
# to explicitly tagged images built from the source checkouts above.
: "${EC_REMOTE_CONTEXT:?Set the verified ec1 Kubernetes context}"
: "${EC_IDE_ENGINE_IMAGE:?Set the tagged orchestrator image}"
: "${EC_IDE_GATEWAY_IMAGE:?Set the tagged gateway image}"

kubectl --context "$EC_REMOTE_CONTEXT" -n ec-demo1 get deployment gateway ec-eventconductor-orchestrator
helm --kube-context "$EC_REMOTE_CONTEXT" -n ec-demo1 get values ec --all

# Review the release values first. Reuse them so the deployed imports, topics, credentials,
# worker configuration and database settings are retained rather than replaced with defaults.
helm --kube-context "$EC_REMOTE_CONTEXT" -n ec-demo1 upgrade ec deploy/chart/eventconductor \
  --reuse-values -f deploy/values/ide-execution.yaml \
  --set-string "orchestrator.image=${EC_IDE_ENGINE_IMAGE%:*}" \
  --set-string "orchestrator.imageTag=${EC_IDE_ENGINE_IMAGE##*:}"
kubectl --context "$EC_REMOTE_CONTEXT" -n ec-demo1 rollout status deployment/ec-eventconductor-orchestrator --timeout=180s
kubectl --context "$EC_REMOTE_CONTEXT" -n ec-demo1 set image deployment/gateway gateway="$EC_IDE_GATEWAY_IMAGE"
kubectl --context "$EC_REMOTE_CONTEXT" -n ec-demo1 rollout status deployment/gateway --timeout=180s
```

These commands do not publish an ingress directly to the unauthenticated engine. Requests
continue through the existing HTTPS ingress and the gateway's Keycloak checks. The ordinary
deployment manifest must subsequently be updated to the reviewed gateway image so a later
deployment does not revert it; this branch does not invent an unpublished image tag.

The optional `deploy/values/ide-execution.yaml` overlay is not included by the normal deployment.
It only sets `WORKFLOW_TEST_RUNS_ENABLED=true`. Starting a draft can call the real workers and
services attached to ec1, so apply it to the agreed test environment with the intended accounts.

## Verify the connection

After the server update, configure the plugin with the settings above and open
`plugins/vscode-eventconductor/examples/execution-smoke.ec` in the engine checkout. Run it from
the graph with synthetic input and confirm START and END become COMPLETED. Then test a workflow
with a waiting timer and use Stop execution; confirm the engine reports CANCELLED.

Check that anonymous requests receive 401, a valid token without `admin` receives 403, and an
authorized workflow administrator receives JSON from `/workflow/api/**`, rather than the shell's
HTML. An optional engine API key and its normal workflow authorization still apply when enabled.
Do not use the booking workflow for the first connection check: its payment, notifications and
event dependencies need a separate environment check.

Local gateway tests use a synthetic upstream to verify that start, monitoring, pagination and
cancel requests are routed correctly, with authentication and role failures blocked before
forwarding. They do not prove ec1's version, migration state, availability or credentials.
