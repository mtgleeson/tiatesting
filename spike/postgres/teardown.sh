#!/usr/bin/env bash
set -euo pipefail
PGBIN="$(brew --prefix postgresql@16)/bin"
curl -sf -X DELETE http://localhost:8474/proxies/pg >/dev/null 2>&1 || true
pkill -f toxiproxy-server 2>/dev/null || true
"$PGBIN/psql" -d postgres -h localhost -c "DROP DATABASE IF EXISTS tiaperf" 2>/dev/null || true
"$PGBIN/psql" -d postgres -h localhost -c "DROP ROLE IF EXISTS tia" 2>/dev/null || true
brew services stop postgresql@16 || true
echo "torn down"
