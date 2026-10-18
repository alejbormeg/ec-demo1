#!/usr/bin/env bash
# Takes ec1's demo back to its baseline (snapshot.sh). What it does, in order:
#   1. notes which customers and change requests ec1 has now, to know what the demo created;
#   2. stops the services and the engine;
#   3. restores the services' databases and the engine's state tables;
#   4. Salesforce: deletes the contacts and Cases the demo created (ec1's only — the org is shared
#      with the local environment) and puts the baseline's contacts back as they were;
#   5. the MDM resumes Salesforce's events from now: the demo's are not replayed;
#   6. puts back the Opera context the baseline's reservations were written under (ec-demo-run);
#   7. starts everything again.
# Opera is not touched: nothing is cancelled or deleted there. The demo is built to repeat on top of
# what it wrote (see docs/poc-acl/demo.md, «Resetear la demo»). A baseline taken before the context
# was saved with it was written under ECDEMO1. A zero (zero.sh, or reset-demo from the Demo page)
# cancels in Opera every reservation the demo wrote, the baseline's too: take a new one after it.
set -euo pipefail
cd "$(dirname "$0")"
. ./common.sh
salesforce_env
[ -f "$BASELINE/taken-at" ] || { echo "No baseline in $BASELINE: take one with snapshot.sh"; exit 1; }
echo "Resetting ec1's demo to the baseline of $(cat "$BASELINE/taken-at")"

# A baseline taken before a database was covered has no dump of it: that one is left as it is, said
# here — before anything stops — rather than found half-way, with the services down.
RESTORED=""
for db in $DATABASES; do
  if [ -n "$(baseline_dump $db)" ]; then RESTORED="$RESTORED $db"
  else echo "  $db: not in this baseline, left as it is (take a new one with snapshot.sh)"; fi
done
[ -n "$(baseline_dump $ENGINE_DB)" ] || { echo "The baseline has no dump of the engine ($ENGINE_DB): take a new one with snapshot.sh"; exit 1; }
# A dump cut short restores some tables and not others — the engine's processes without their steps,
# a service's database half-way. Refused here, with nothing stopped yet, not found after the restore.
for db in $RESTORED $ENGINE_DB; do
  dump_complete "$(baseline_dump $db)" \
    || { echo "The baseline's dump of $db is incomplete (cut short when it was taken): take a new one with snapshot.sh"; exit 1; }
done

WORK=$(mktemp -d)
psql_value customer_mdm "select id from customer" > "$WORK/customers-now"
psql_value customer_mdm "select id from change_request" > "$WORK/requests-now" 2>/dev/null || : > "$WORK/requests-now"
psql_value customer_mdm "select id from customer_notice" > "$WORK/notices-now" 2>/dev/null || : > "$WORK/notices-now"

echo "Stopping the services and the engine"
for d in $SERVICES; do kubectl -n $NS scale deploy/$d --replicas=0 >/dev/null; done
for d in $SERVICES; do kubectl -n $NS wait --for=delete pod -l app=$d --timeout=180s >/dev/null 2>&1 || true; done
sleep 5
# From here on a failure must not leave ec1 down: whatever happens, the services start again. Each
# database is restored in one transaction (psql_file), so one that fails is left as it was, not half-way.
STARTED=""
start_services() {
  [ -n "$STARTED" ] && return 0
  STARTED=1
  echo "Starting the services and the engine"
  for d in $SERVICES; do kubectl -n $NS scale deploy/$d --replicas=1 >/dev/null; done
  for d in $SERVICES; do kubectl -n $NS rollout status deploy/$d --timeout=420s | tail -1; done
}
trap 'rc=$?; if [ $rc -ne 0 ]; then echo "FAILED (exit $rc): the reset did not finish — what was not restored is as it was"; start_services || true; fi' EXIT

echo "Restoring the databases"
for db in $RESTORED; do
  psql_file $db "$(baseline_dump $db)" || { echo "  $db: NOT restored, left as it was"; exit 1; }
  echo "  $db"
done
engine_restore_sql "$(baseline_dump $ENGINE_DB)" > "$WORK/$ENGINE_DB.sql"
psql_file $ENGINE_DB "$WORK/$ENGINE_DB.sql" || { echo "  $ENGINE_DB: NOT restored, left as it was"; exit 1; }
echo "  $ENGINE_DB (state tables)"

echo "Salesforce"
psql_value customer_mdm "select id from customer" > "$WORK/customers-baseline"
psql_value customer_mdm "select id from change_request" > "$WORK/requests-baseline" 2>/dev/null || : > "$WORK/requests-baseline"
psql_value customer_mdm "select id from customer_notice" > "$WORK/notices-baseline" 2>/dev/null || : > "$WORK/notices-baseline"
psql_value customer_mdm "select json_agg(json_build_object('id', id, 'firstName', first_name, 'lastName', last_name, 'email', email, 'phone', phone, 'birthDate', birth_date, 'nationality', nationality, 'documentType', document_type, 'documentNumber', document_number)) from customer where status <> 'MERGED' and salesforce_contact_id is not null" > "$WORK/contacts-baseline.json"
python3 salesforce.py "$WORK"
# The MDM resumes Salesforce's events from now, and its poll looks from now: what happened during
# the demo has just been undone, and must not come back.
echo "delete from salesforce_cursor where name like 'pubsub%'; update salesforce_cursor set until = now() where name = 'poll';" | psql_in customer_mdm

echo "Opera"
set_opera_context "$(cat "$BASELINE/opera-context" 2>/dev/null || echo "$OPERA_CONTEXT_DEFAULT")"

start_services
rm -rf "$WORK"
echo "Done: ec1's demo is at its baseline."
