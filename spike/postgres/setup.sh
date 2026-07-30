#!/usr/bin/env bash
# Install and start Postgres 16 + Toxiproxy via Homebrew, create the tia/tiaperf role+db,
# and register the :5433 -> :5432 proxy. Idempotent. No Docker.
set -euo pipefail

brew list postgresql@16 >/dev/null 2>&1 || brew install postgresql@16
brew list toxiproxy     >/dev/null 2>&1 || brew install toxiproxy
PGBIN="$(brew --prefix postgresql@16)/bin"

brew services start postgresql@16
# wait for readiness
for i in $(seq 1 30); do "$PGBIN/pg_isready" -h localhost -p 5432 >/dev/null 2>&1 && break; sleep 1; done

# role + db (ignore "already exists")
"$PGBIN/psql" -d postgres -h localhost -tc \
  "SELECT 1 FROM pg_roles WHERE rolname='tia'" | grep -q 1 || \
  "$PGBIN/psql" -d postgres -h localhost -c "CREATE ROLE tia LOGIN PASSWORD 'tia' SUPERUSER"
"$PGBIN/psql" -d postgres -h localhost -tc \
  "SELECT 1 FROM pg_database WHERE datname='tiaperf'" | grep -q 1 || \
  "$PGBIN/psql" -d postgres -h localhost -c "CREATE DATABASE tiaperf OWNER tia"

# start toxiproxy-server (background) if not already listening on 8474
if ! curl -sf http://localhost:8474/version >/dev/null 2>&1; then
  (toxiproxy-server >/tmp/toxiproxy.log 2>&1 &) ; sleep 1
fi
# register the proxy (upstream is the LOCAL postgres)
curl -sf -X DELETE http://localhost:8474/proxies/pg >/dev/null 2>&1 || true
curl -sf -X POST   http://localhost:8474/proxies \
  -d '{"name":"pg","listen":"127.0.0.1:5433","upstream":"127.0.0.1:5432"}' >/dev/null
echo "setup complete: direct=localhost:5432, proxied=localhost:5433, admin=localhost:8474"
