#!/usr/bin/env bash
# Copyright 2026 Aman Mittal
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Load-test with several JMeter workers at once, then judge the merged result.
#
# One JMeter JVM can only generate so much load, and on a small machine it
# competes with the thing it is measuring. Distributed mode splits the work:
# a controller tells N workers (`jmeter-server`, separate JVMs reached over
# RMI) to each run the whole plan, and collects every sample into one result
# file. The load is therefore WORKERS x THREADS virtual users, and the same
# error-rate and 95th-percentile limits as run.sh decide pass or fail.
#
# By default the workers are started here, on loopback. That exercises the real
# protocol -- RMI, remote start, merged results -- on one machine. To use real
# infrastructure, start `jmeter-server` on each machine and name them:
#
#   REMOTE_WORKERS=10.0.0.11:1099,10.0.0.12:1099 bash performance/jmeter/run-distributed.sh
#
# Usage:
#   BASE_URL=http://localhost:8080 DEMO_ADMIN_PASSWORD=... \
#       WORKERS=3 THREADS=5 bash performance/jmeter/run-distributed.sh
#
# Needs bash, python3 and Apache JMeter 5.6 (jmeter and jmeter-server next to
# each other; set JMETER_BIN to the jmeter script if it is not on PATH).
# Optional: WORKERS, THREADS (per worker), RAMP_SECONDS, DURATION_SECONDS,
# MAX_ERROR_PERCENT, MAX_P95_MS, RESULTS_DIR, WORKER_HEAP_MB, REMOTE_WORKERS,
# DEMO_TENANT, DEMO_ADMIN_EMAIL.

set -euo pipefail

# Names a variable that must be set, without ever writing its value next to
# its name -- a secret scanner reads that shape as a hard-coded credential.
require() {
    if [ -z "${!1:-}" ]; then
        echo "run-distributed.sh: set $1" >&2
        exit 2
    fi
}
require BASE_URL
require DEMO_ADMIN_PASSWORD

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JMETER_BIN="${JMETER_BIN:-jmeter}"
WORKERS="${WORKERS:-3}"
THREADS="${THREADS:-5}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
DURATION_SECONDS="${DURATION_SECONDS:-60}"
MAX_ERROR_PERCENT="${MAX_ERROR_PERCENT:-1}"
MAX_P95_MS="${MAX_P95_MS:-3000}"
WORKER_HEAP_MB="${WORKER_HEAP_MB:-256}"
RESULTS_DIR="${RESULTS_DIR:-$HERE/results}"
DEMO_TENANT="${DEMO_TENANT:-demo}"
DEMO_ADMIN_EMAIL="${DEMO_ADMIN_EMAIL:-admin@demo.local}"
BASE_RMI_PORT=1099
BASE_LOCAL_PORT=4000

if ! command -v "$JMETER_BIN" >/dev/null 2>&1; then
    echo "run-distributed.sh: Apache JMeter not found (set JMETER_BIN)" >&2
    exit 2
fi
JMETER_DIR="$(cd "$(dirname "$(command -v "$JMETER_BIN")")" && pwd)"
JMETER_SERVER="$JMETER_DIR/jmeter-server"

read -r PROTOCOL HOST PORT < <(python3 -I - "$BASE_URL" <<'PY'
import sys
from urllib.parse import urlsplit
u = urlsplit(sys.argv[1])
print(u.scheme, u.hostname, u.port or (443 if u.scheme == "https" else 80))
PY
)

mkdir -p "$RESULTS_DIR"
RESULTS="$RESULTS_DIR/results.jtl"
rm -f "$RESULTS"

PIDS=()
cleanup() {
    for pid in "${PIDS[@]:-}"; do
        [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
    done
}
trap cleanup EXIT

# Is something listening on this loopback port?
listening() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

if [ -n "${REMOTE_WORKERS:-}" ]; then
    REMOTES="$REMOTE_WORKERS"
    WORKERS="$(awk -F, '{print NF}' <<<"$REMOTE_WORKERS")"
else
    [ -x "$JMETER_SERVER" ] || { echo "run-distributed.sh: $JMETER_SERVER not found" >&2; exit 2; }
    REMOTES=""
    for i in $(seq 0 $((WORKERS - 1))); do
        rmi=$((BASE_RMI_PORT + i))
        local_port=$((BASE_LOCAL_PORT + i))
        # SSL is switched off: this is loopback, and the keystore it would need
        # is the part of distributed mode that most often stops a first run.
        HEAP="-Xms64m -Xmx${WORKER_HEAP_MB}m" "$JMETER_SERVER" \
            -Dserver_port="$rmi" -Dserver.rmi.localport="$local_port" \
            -Djava.rmi.server.hostname=127.0.0.1 -Dserver.rmi.ssl.disable=true \
            -Jserver.rmi.ssl.disable=true \
            > "$RESULTS_DIR/worker-$i.log" 2>&1 &
        PIDS+=("$!")
        REMOTES="${REMOTES:+$REMOTES,}127.0.0.1:$rmi"
    done
    for i in $(seq 0 $((WORKERS - 1))); do
        rmi=$((BASE_RMI_PORT + i))
        for _ in $(seq 1 60); do
            listening "$rmi" && break
            sleep 1
        done
        listening "$rmi" || { echo "run-distributed.sh: worker $i did not start" >&2; tail -20 "$RESULTS_DIR/worker-$i.log" >&2; exit 1; }
    done
fi

echo "== JMeter, distributed: $BASE_URL, $WORKERS workers x $THREADS users = $((WORKERS * THREADS)), ${RAMP_SECONDS}s ramp, ${DURATION_SECONDS}s"
echo "   workers: $REMOTES"

# Every property goes to the controller (-J) and to each worker (-G): the plan
# runs on the workers, and that is where it reads them.
PROPS=(
    "protocol=$PROTOCOL" "host=$HOST" "port=$PORT"
    "tenant=$DEMO_TENANT" "adminEmail=$DEMO_ADMIN_EMAIL" "adminPassword=$DEMO_ADMIN_PASSWORD"
    "threads=$THREADS" "rampSeconds=$RAMP_SECONDS" "durationSeconds=$DURATION_SECONDS"
)
ARGS=()
for prop in "${PROPS[@]}"; do
    ARGS+=("-J$prop" "-G$prop")
done

# -X shuts the remote workers down when the test ends.
"$JMETER_BIN" -n -t "$HERE/lms-api.jmx" -l "$RESULTS" -j "$RESULTS_DIR/jmeter.log" \
    -R "$REMOTES" -X -Jserver.rmi.ssl.disable=true "${ARGS[@]}" \
    -Jjmeter.save.saveservice.output_format=csv \
    -Jjmeter.save.saveservice.response_code=true \
    -Jjmeter.save.saveservice.successful=true \
    -Jjmeter.save.saveservice.label=true \
    -Jjmeter.save.saveservice.time=true

python3 -I "$HERE/summarise.py" "$RESULTS" "$MAX_ERROR_PERCENT" "$MAX_P95_MS" \
    | tee "$RESULTS_DIR/summary.txt"
