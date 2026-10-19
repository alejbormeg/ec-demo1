#!/usr/bin/env bash
set -euo pipefail
# Runs only on this Compose project's brand-new PostgreSQL volume.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 --set=runtime_password="$RUNTIME_DB_PASSWORD" <<'SQL'
CREATE ROLE agent_worker LOGIN PASSWORD :'runtime_password';
CREATE DATABASE agent_executions OWNER agent_worker;
REVOKE ALL ON DATABASE agent_executions FROM PUBLIC;
SQL
