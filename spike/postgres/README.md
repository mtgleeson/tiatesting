# Postgres viability spike harness (Homebrew, no Docker)

    chmod +x spike/postgres/*.sh
    spike/postgres/setup.sh       # install + start pg & toxiproxy, create tia/tiaperf, register proxy
    spike/postgres/latency.sh 0   # 0ms added RTT

Direct (seeding / 0ms baseline):  jdbc:postgresql://localhost:5432/tiaperf
Through Toxiproxy (latency runs): jdbc:postgresql://localhost:5433/tiaperf
Role/password: tia/tia

Tear down:  spike/postgres/teardown.sh
