#!/usr/bin/env bash
# Takes ec1 back to before anything was integrated, to walk the onboarding again step by step:
#   - the services lose what the integration made: reservations, integrations (crs-pms and pms-fo, with
#     the pms-fo cursor), mappings, the MDM's customers, the front office's guests, stays and the PMS
#     catalogue it was given, notifications, the reception notices, audit, the engine's processes;
#   - what is set up, not integrated, stays: the ERP's partners, the front office's rooms and
#     catalogs, the registration rules, the process definitions, content, users and Keycloak;
#   - Salesforce loses every contact and the MDM's Cases (change requests and reception notices: a
#     contact with a Case cannot be deleted).
# Opera loses the reservations the demo wrote, this run's and every earlier one's (opera-cancel-ours.py,
# as reset-demo's cancel-earlier-runs does from the Demo page): nothing else there is touched. Each run
# still gets a context of its own for the CRS locator in the reservations' external references — ECDEMO1-<MMddHHmm>,
# UTC, in the ec-demo-run ConfigMap — and a new random locator that repeats an old one does not find,
# and update, an old run's reservation. Within a run the integration still finds what it wrote by
# locator (under the run's context), so walking the onboarding again writes no reservation twice.
# Partners are found by their CorporateId and guest profiles through their reservation: no context.
# The run's reservations also carry it as Opera's «Custom Reference» (OPERA_CUSTOM_REFERENCE, same
# ConfigMap): a pms-fo integration of the default scope, CHAIN, brings to the front office only those.
#
# The demo's baseline is left alone, but reset.sh would bring the old state back: take a new one
# with snapshot.sh once the onboarding has been walked again.
set -euo pipefail
cd "$(dirname "$0")"
. ./common.sh
salesforce_env

echo "Stopping the services and the engine"
for d in $SERVICES; do kubectl -n $NS scale deploy/$d --replicas=0 >/dev/null; done
for d in $SERVICES; do kubectl -n $NS wait --for=delete pod -l app=$d --timeout=180s >/dev/null 2>&1 || true; done
sleep 5

echo "Cancelling in Opera the reservations of the demo's runs"
python3 ./opera-cancel-ours.py XMAR --yes || echo "  (some were not cancelled: see above)"

echo "A new Opera context for this run"
set_opera_context "ECDEMO1-$(date -u +%m%d%H%M)"

# Only the tables that exist: a service deployed before the version that added a table (or a database not
# created yet) would otherwise stop the script half-way, with every service down.
wipe() {
  local db=$1; shift
  local wanted; wanted=$(printf "'%s'," "$@"); wanted=${wanted%,}
  local tables; tables=$(psql_value "$db" "select string_agg(table_name, ',') from information_schema.tables where table_schema = current_schema() and table_name in ($wanted)" 2>/dev/null || true)
  if [ -z "$tables" ]; then echo "  $db: nothing of $* (not there yet)"; return 0; fi
  echo "truncate $tables cascade;" | psql_in "$db" && echo "  $db: ${tables//,/ }"
}
echo "Emptying what the integration made"
wipe booking booking_entity crs_booking catalog_rate_plan crs_intake_pause outbox_message
wipe partners outbox_message
wipe crs_integration inbox_entry outbox_message
wipe integrations integration backfill_run fo_integration fo_backfill_run outbox_message
wipe mapping cause mapping_entry partner_profile waiter waiter_cause outbox_message
wipe communication inbox_item inbox_seen notification resolution
wipe customer_mdm customer customer_source customer_xref customer_document consolidation change_request customer_notice inbox_entry outbox_message
wipe front_office guest guest_kardex guest_preference stay stay_add_on stay_companion stay_incident folio folio_line pms_catalogue pms_catalogue_sync command_inbox walk_in check_in_ops forced_check_in stay_invoice folio_line_pms pax_registration_data customer_nationality customer_notice stay_notice_ack pax_recognition pax_scan arrival_briefing pax_kardex folio_payment folio_credit outbox_message inbox_entry
echo "update room set occupancy = 'FREE';" | psql_in front_office
wipe notices notice outbox_message
wipe customer_history customer_stay customer_alias
wipe loyalty member accrual
wipe audit audit_record
wipe $ENGINE_DB $ENGINE_TABLES log_message_entity outbox_message_entity received_task sync_invocation

echo "Salesforce"
python3 - <<'EOF'
import json, sys, urllib.parse, urllib.request
from pathlib import Path
sys.path.insert(0, str(Path.cwd().parents[1] / "integration/customer-mdm-service/salesforce"))
import deploy
instance, access, _ = deploy.token()
def call(method, path):
    req = urllib.request.Request(instance + "/services/data/v67.0" + path, method=method,
                                 headers={"Authorization": "Bearer " + access})
    with urllib.request.urlopen(req) as r:
        raw = r.read()
        return json.loads(raw) if raw else None
def ids(soql):
    page = call("GET", "/query?q=" + urllib.parse.quote(soql))
    found = [r["Id"] for r in page["records"]]
    while not page.get("done", True):
        page = call("GET", page["nextRecordsUrl"].split("/services/data/v67.0", 1)[1])
        found += [r["Id"] for r in page["records"]]
    return found
# Two hundred records a call (sObject Collections), not one: the org's daily API allowance counts calls.
# allOrNone=false answers 200 even when a record is not deleted: what failed is said, not swallowed.
def delete(records):
    failed = 0
    for i in range(0, len(records), 200):
        for r in call("DELETE", "/composite/sobjects?allOrNone=false&ids=" + ",".join(records[i:i + 200])):
            if not r["success"]:
                failed += 1
                print(f"  NOT deleted {r['id']}: {'; '.join(e['message'].strip() for e in r['errors'])}")
    return len(records) - failed
# A contact with a Case is not deleted: the MDM's Cases go first, notices (MdmAvisoId__c) included.
cases = delete(ids("SELECT Id FROM Case WHERE MdmRequestId__c != null OR MdmAvisoId__c != null"))
contacts = delete(ids("SELECT Id FROM Contact"))
print(f"  deleted {cases} Case(s) and {contacts} contact(s)")
EOF
# The MDM resumes Salesforce's events from now: the deletions just made are not the MDM's to replay.
echo "delete from salesforce_cursor where name like 'pubsub%'; update salesforce_cursor set until = now() where name = 'poll';" | psql_in customer_mdm

echo "Starting the services and the engine"
for d in $SERVICES; do kubectl -n $NS scale deploy/$d --replicas=1 >/dev/null; done
for d in $SERVICES; do kubectl -n $NS rollout status deploy/$d --timeout=420s | tail -1; done
echo "Done: ec1 is at zero."
