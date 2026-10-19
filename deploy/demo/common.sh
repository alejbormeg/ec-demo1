# Shared by snapshot.sh and reset.sh: where the baseline lives, what it covers, how to reach the database.
NS=ec-demo1
BASELINE=${EC_DEMO_BASELINE:-$HOME/.local/share/ec-demo1/demo-baseline}

# The services' databases, whole: their state is the demo's state. (content, users and keycloak are not.)
DATABASES="audit booking communication crs_integration customer_history customer_mdm front_office integrations loyalty mapping notices partners registration_rules"
# The engine's database only by its state tables: processes, their steps, the forms' tasks, locks,
# overrides, and the domain events taken in (inbound_event_receipt, 2.26+: the dedup that keeps an event
# from starting a process twice — it goes with the processes it started). Its history — logs, the outbox,
# the task dedup store — is gigabytes and not the demo's state; and what is set up, not run — the event
# triggers of events.yaml (event_trigger), the paused schedules and triggers — stays, as the definitions do.
ENGINE_DB=workflow
ENGINE_TABLES="process_entity step_execution_entity form_execution_entity process_lock process_lock_waiter resource_entity process_index task_override inbound_event_receipt"

# What stops while the state is put back, and starts again after.
SERVICES="audit-service booking communication-service crs-integration-service customer-mdm-service front-office integrations-service mapping-service erp notices customer-history loyalty pms-integration-service registration-rules ec-eventconductor-orchestrator ec-eventconductor-forms"

pg_pod() { kubectl -n $NS get pod -o name | grep eventconductor-postgres | head -1; }
# psql against one database, reading SQL from stdin
psql_in() { kubectl -n $NS exec -i "$(pg_pod)" -- sh -c "psql -U \"\$POSTGRES_USER\" -v ON_ERROR_STOP=1 -q -d $1"; }

# Moving a file between here and the database pod. kubectl exec's stream to and from ec1 is slow (a few
# KB/s) and can end early while still exiting 0: a baseline restore that way put the engine's processes
# back without their steps (COPY step_execution_entity cut at a row: «missing data for column status»).
# So nothing big crosses as a bare stream any more: files go compressed and are compared by checksum on
# both sides — again, a few times, if they have to — before anything uses them.
pod_cksum() { kubectl -n $NS exec "$(pg_pod)" -- cksum "$1" | awk '{print $1, $2}'; }
local_cksum() { cksum < "$1" | awk '{print $1, $2}'; }
pod_get() { # pod_get <file in the pod> <local file>
  local try
  for try in 1 2 3; do
    kubectl -n $NS exec "$(pg_pod)" -- cat "$1" > "$2" || true
    [ "$(local_cksum "$2")" = "$(pod_cksum "$1")" ] && return 0
    echo "  $(basename "$2"): came back incomplete (attempt $try)" >&2
  done
  return 1
}
pod_put() { # pod_put <local file> <file in the pod>
  local try
  for try in 1 2 3; do
    kubectl -n $NS exec -i "$(pg_pod)" -- sh -c "cat > '$2'" < "$1" || true
    [ "$(pod_cksum "$2")" = "$(local_cksum "$1")" ] && return 0
    echo "  $(basename "$1"): arrived incomplete (attempt $try)" >&2
  done
  return 1
}

# pg_dump ends every dump it finishes with this line: one without it was cut short, and restoring it
# restores some tables and not others. Takes a compressed dump, or a plain one from an older baseline.
DUMP_TRAILER='-- PostgreSQL database dump complete'
dump_complete() { gzip -dcf "$1" 2>/dev/null | tail -n 5 | grep -qF -- "$DUMP_TRAILER"; }

# The baseline's dump of a database: <db>.sql.gz, or <db>.sql from a baseline taken before they were
# compressed. Prints nothing when it has none.
baseline_dump() {
  if [ -f "$BASELINE/$1.sql.gz" ]; then echo "$BASELINE/$1.sql.gz"
  elif [ -f "$BASELINE/$1.sql" ]; then echo "$BASELINE/$1.sql"; fi
}

# The script that puts back the engine's state tables: empties them and loads the baseline's rows. One
# script, so that it runs as one transaction (psql_file): the processes never come back without their steps.
engine_restore_sql() { # engine_restore_sql <baseline dump>
  # Only the state tables this engine has: a fresh install of the orchestrator has no task_override (ec1's
  # came with an older version), and truncating a table that is not there fails the whole restore.
  local wanted existing
  wanted=$(printf "'%s'," $ENGINE_TABLES); wanted=${wanted%,}
  existing=$(psql_value "$ENGINE_DB" "select string_agg(table_name, ',') from information_schema.tables where table_schema = current_schema() and table_name in ($wanted)")
  echo "truncate ${existing:-$(echo $ENGINE_TABLES | tr ' ' ',')};"
  gzip -dcf "$1"
}

# Runs a SQL script (compressed or not) in the pod against a database in ONE transaction, stopping at
# the first error: all of it is applied, or none of it. The script is sent as a file, checked, first.
psql_file() { # psql_file <db> <local script>
  local remote="/tmp/ec-demo-restore-$1.sql.gz" gz
  gz=$(mktemp)
  gzip -dcf "$2" | gzip -c > "$gz"
  pod_put "$gz" "$remote" || { rm -f "$gz"; return 1; }
  rm -f "$gz"
  kubectl -n $NS exec "$(pg_pod)" -- sh -c "gzip -dc '$remote' | psql -U \"\$POSTGRES_USER\" -v ON_ERROR_STOP=1 -q -1 -d $1 > /dev/null; rc=\$?; rm -f '$remote'; exit \$rc"
}

# one value, or one column
psql_value() { kubectl -n $NS exec "$(pg_pod)" -- sh -c "psql -U \"\$POSTGRES_USER\" -Atc \"$2\" -d $1"; }

# Salesforce's client credentials, for the scripts that touch the org. Taken from the environment when
# set, else from the cluster's ec-salesforce secret — the one the MDM uses — so a reset does not stop
# half-way, with the services already down, for want of three exports.
salesforce_env() {
  local key
  for key in SF_DOMAIN SF_CLIENT_ID SF_CLIENT_SECRET; do
    if [ -z "${!key:-}" ]; then
      export "$key=$(kubectl -n "$NS" get secret ec-salesforce -o jsonpath="{.data.$key}" | base64 -d)"
    fi
  done
}

# The context the CRS locator goes under in Opera, in the ec-demo-run ConfigMap pms-integration takes its
# environment from. Each run to zero writes under a context of its own — earlier runs' reservations are
# cancelled, not deleted, and keep their locators — so a new
# locator that happens to repeat an old one does not find — and update — an old run's reservation.
# Without the ConfigMap the service uses ECDEMO1, the context of everything written before there was one.
OPERA_CONTEXT_DEFAULT=ECDEMO1
opera_context() {
  local ctx
  ctx=$(kubectl -n "$NS" get configmap ec-demo-run -o jsonpath='{.data.OPERA_EXTERNAL_SYSTEM}' 2>/dev/null || true)
  echo "${ctx:-$OPERA_CONTEXT_DEFAULT}"
}
# The Opera «Custom Reference» a run's reservations carry: what a pms-fo integration of scope CHAIN
# (the default, «only ours») searches Opera by. Per run as the context is, so a new run's front office
# gets that run's reservations and not an earlier run's; the baseline's context keeps EC-DEMO1, the
# reference everything was written with before there was one per run.
opera_custom_reference() {
  if [ "$1" = "$OPERA_CONTEXT_DEFAULT" ]; then echo "EC-DEMO1"; else echo "$1"; fi
}
# Sets both; takes effect when pms-integration-service starts.
set_opera_context() {
  local reference
  reference=$(opera_custom_reference "$1")
  kubectl -n "$NS" create configmap ec-demo-run --from-literal=OPERA_EXTERNAL_SYSTEM="$1" \
    --from-literal=OPERA_CUSTOM_REFERENCE="$reference" \
    --dry-run=client -o yaml | kubectl -n "$NS" apply -f - >/dev/null
  echo "  Opera context: $1 (custom reference $reference)"
}
