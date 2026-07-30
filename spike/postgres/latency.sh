#!/usr/bin/env bash
# Configure the Toxiproxy latency toxic. Usage: ./latency.sh <rtt_ms>
set -euo pipefail
RTT="${1:?usage: latency.sh <rtt_ms>}"
ADMIN=http://localhost:8474

# remove any existing latency toxic, then add the requested one
curl -sf -X DELETE "$ADMIN/proxies/pg/toxics/latency_downstream" >/dev/null 2>&1 || true
if [ "$RTT" -gt 0 ]; then
  curl -sf -X POST "$ADMIN/proxies/pg/toxics" \
    -d "{\"name\":\"latency_downstream\",\"type\":\"latency\",\"stream\":\"downstream\",\"attributes\":{\"latency\":$RTT,\"jitter\":0}}" >/dev/null
fi
echo "toxiproxy pg proxy: localhost:5433 -> localhost:5432, added RTT=${RTT}ms"
