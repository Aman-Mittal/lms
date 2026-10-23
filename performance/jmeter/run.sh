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

# Load-test a running deployment with Apache JMeter, then judge the result.
#
# The plan (lms-api.jmx) signs in once as the demo administrator and then
# drives a mixed workload: ten read endpoints picked at random, one write in
# ten iterations, and a readiness probe, with a think time between iterations.
# JMeter itself only measures; this script reads its result file and fails the
# run when the error rate or the 95th percentile is over a limit, so a
# regression is a red build rather than a number somebody has to read.
#
# The defaults are sized for the free Render instance (0.1 CPU, 512 MB): five
# virtual users for a minute. Raise THREADS against a bigger machine, not
# against the free tier -- it will only tell you it is a free tier.
#
# Usage:
#   BASE_URL=https://lms-platform.onrender.com DEMO_ADMIN_PASSWORD=... \
#       bash performance/jmeter/run.sh
#
# Needs bash, python3 and Apache JMeter 5.6 on PATH (or JMETER_BIN).
# Optional: THREADS, RAMP_SECONDS, DURATION_SECONDS, MAX_ERROR_PERCENT,
# MAX_P95_MS, RESULTS_DIR, DEMO_TENANT, DEMO_ADMIN_EMAIL.

set -euo pipefail

# Names a variable that must be set, without ever writing its value next to
# its name -- a secret scanner reads that shape as a hard-coded credential.
require() {
    if [ -z "${!1:-}" ]; then
        echo "run.sh: set $1" >&2
        exit 2
    fi
}
require BASE_URL
require DEMO_ADMIN_PASSWORD

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JMETER_BIN="${JMETER_BIN:-jmeter}"
THREADS="${THREADS:-5}"
RAMP_SECONDS="${RAMP_SECONDS:-10}"
DURATION_SECONDS="${DURATION_SECONDS:-60}"
MAX_ERROR_PERCENT="${MAX_ERROR_PERCENT:-1}"
MAX_P95_MS="${MAX_P95_MS:-3000}"
RESULTS_DIR="${RESULTS_DIR:-$HERE/results}"
DEMO_TENANT="${DEMO_TENANT:-demo}"
DEMO_ADMIN_EMAIL="${DEMO_ADMIN_EMAIL:-admin@demo.local}"

if ! command -v "$JMETER_BIN" >/dev/null 2>&1; then
    echo "run.sh: Apache JMeter not found (set JMETER_BIN)" >&2
    exit 2
fi

# The plan wants the URL in three parts.
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

echo "== JMeter: $BASE_URL, $THREADS users, ${RAMP_SECONDS}s ramp, ${DURATION_SECONDS}s"
"$JMETER_BIN" -n -t "$HERE/lms-api.jmx" -l "$RESULTS" -j "$RESULTS_DIR/jmeter.log" \
    -Jprotocol="$PROTOCOL" -Jhost="$HOST" -Jport="$PORT" \
    -Jtenant="$DEMO_TENANT" -JadminEmail="$DEMO_ADMIN_EMAIL" \
    -JadminPassword="$DEMO_ADMIN_PASSWORD" \
    -Jthreads="$THREADS" -JrampSeconds="$RAMP_SECONDS" -JdurationSeconds="$DURATION_SECONDS" \
    -Jjmeter.save.saveservice.output_format=csv \
    -Jjmeter.save.saveservice.response_code=true \
    -Jjmeter.save.saveservice.successful=true \
    -Jjmeter.save.saveservice.label=true \
    -Jjmeter.save.saveservice.time=true

python3 -I "$HERE/summarise.py" "$RESULTS" "$MAX_ERROR_PERCENT" "$MAX_P95_MS" \
    | tee "$RESULTS_DIR/summary.txt"
