# e2e

Playwright against the deployed cluster (ec1). Three suites:

| Command | What | Writes? |
|---|---|---|
| `npx playwright test` | every screen of the four consoles (`tests/`) | no — run it any time |
| `npm run demo` | the demo's storyline through the internal APIs (`demo/`), then a reset | yes |
| `DEMO_E2E_RESET=1 npm run demo:ui` | **the whole demo through the UI** (`demo-ui/`): reset + flows 1–8 | **wipes the demo data** |

## The demo through the UI (`demo-ui`)

One command, from `e2e/`:

```sh
set -a; . ../deploy/.secrets/credentials.env; set +a   # DEMO_PASSWORD (never printed)
DEMO_E2E_RESET=1 npm run demo:ui
```

It runs in its own config (`demo-ui.config.ts`, `testDir ./demo-ui`), so the normal suite never picks
it up, and it refuses to start without `DEMO_E2E_RESET=1`. It takes about an hour. Every checkpoint's
screenshot is attached to the report (`npx playwright show-report demo-ui-report`). The steps are
serial: when one fails, the steps after it are reported as not run, and the error says what never happened.

What it does, all through the consoles (Redwood by default):

0. **Reset** from the control plane's *Demo* page (⋯ «Resetear la demo…» → Sí), confirmed in the inbox
   task «Confirmar el reset de la demo» (Claim → «Sí, resetear…» → Complete); waits for the Demo page to
   show the reset done. The reset cancels in Opera (a shared UAT) the reservations the demo wrote, and nothing else, then moves to a new Opera context.
1. **Onboarding MRU01 → XMAR**: «+ 10 reservas demo», PMS → front office and CRS → PMS integrations, the
   dictionary («Ask the agent» + Approve), backfill, activate.
2. **Returning customer**: a new booking for the holder of a demo booking (same name and phone, another
   email) → a duplicate customer. Then, in the front office, «Escanear» on the holder of the ORIGINAL
   booking and then on the NEW one: the MDM consolidates them (Customers → Consolidations, vía SCAN) and
   merges the two Salesforce contacts itself. Asserts the consolidation and that both bookings end up
   with the same customer code. (The front office does not show the code on an arriving stay, so it is
   read on the CRS booking's «In other systems».)
3. **Front office data change** → «Pendiente de Salesforce» → a Case (Clientes → Solicitudes de cambio).
   The Case is approved by a **test fixture**: Salesforce's UI needs MFA, so the spec reads the cluster
   secret `ec-salesforce` with kubectl at runtime (never printed) and sets `Decision__c = Aprobada` as a
   steward would; then it waits for the decision to come back to the MDM and the front office. Without
   kubectl access the step is skipped, saying so, after asserting the Case exists.
4. **No show** of a booking arriving today. 5. **Walk-in** (the front office's guided process).
6. **Opera outage** from the Demo page (warning threshold 2 min, on, a booking retries, the inbox's
   RETRYING_TOO_LONG notice, off — always switched off, even on failure — written once).
7. **Modify and cancel** that booking from the CRS. 8. **New rate plan EMPLEADOS-27** (Call center →
   Catalogue → Rate plans; skipped with a message if the screen is not deployed), the booking waits
   MISSING_MAPPING, the agent proposes 432040HLXMU, only that one is approved.

**Opera's availability moves** (shared UAT): when Opera refuses a room type (RSV00138, a PMS_REJECTED
cause), the spec changes the booking's room type through its edit page and tries the next one — the
order is `DEMO_ROOM_TYPES` (default `JS-SEA,JS-SIDESEA,JS-STD,JS-ACCESSIBLE,JS-STD-KING`).

Environment:

| Variable | Default | |
|---|---|---|
| `DEMO_E2E_RESET` | — | must be `1` |
| `DEMO_PASSWORD` | — | the Keycloak `demo` user's (from `deploy/.secrets/credentials.env`) |
| `DEMO_RENDERER` | `redwood` | `vaadin` switches the console hosts (best effort: written for Redwood) |
| `DEMO_DATA_HOST`, `DEMO_CONTROL_HOST`, `DEMO_FRONT_HOST` | `rw.ec1…`, `rw-console.ec1…`, `front.ec1…` | |
| `DEMO_ROOM_TYPES` | see above | the room types to try against RSV00138 |
| `DEMO_NAMESPACE` | `ec-demo1` | where the `ec-salesforce` secret is |

Selectors marked `// UNVERIFIED (probe denied)` were written from the source and the 2026-10-04 run's
page text, not checked against the deployed UI; each step's header comment lists them. The first real
run confirms them.
