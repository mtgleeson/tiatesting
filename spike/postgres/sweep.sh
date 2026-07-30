#!/usr/bin/env bash
# Seed once (direct 5432), then profile through Toxiproxy (5433) at each RTT band.
# Assumes spike/postgres/setup.sh has been run. No Docker.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GRADLE="./gradlew --console=plain -q"
DIRECT=jdbc:postgresql://localhost:5432/tiaperf
PROXIED=jdbc:postgresql://localhost:5433/tiaperf

echo "seeding (direct, 0ms)..."
$GRADLE :tia-core:generateLargeTiaDb -Purl=$DIRECT -Puser=tia -Ppassword=tia \
  -PtestSuites=1000 -PsourceMethods=50000 -PavgClassesPerSuite=936 -PavgMethodsPerClass=6 -Pbranch=main

for RTT in 0 2 20 50; do
  "$HERE/latency.sh" "$RTT" >/dev/null
  echo "=== RTT=${RTT}ms (via toxiproxy 5433) ==="
  $GRADLE :tia-core:profileSelectTests -Purl=$PROXIED -Puser=tia -Ppassword=tia \
    -Pbranch=main -PdiffFiles=20 -Piterations=3 -PfullLoad=false | grep -E "Phase 3|result"
done

# Reset the toxic to 0ms so the proxy is left clean for whatever runs next.
"$HERE/latency.sh" 0
