#!/usr/bin/env bash
#
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

# Smoke test: is a running deployment actually usable?
#
# Not a test of behaviour -- the Cucumber suites do that -- but of wiring: the
# process is up, migrations ran, the contract is served, the demo tenant was
# seeded, a real sign-in works, and every module's read side answers. Each of
# those has failed independently at some point in this project's life, and
# each fails in a way a health check alone does not see (a native binary whose
# JSON serialisation lacks reflection metadata is perfectly healthy).
#
# One script, three callers:
#   * SmokeScriptTest runs it against the application in the test suite;
#   * the Native image workflow runs it against the freshly built binary;
#   * the Smoke workflow runs it against the Render deployment on demand.
#
# Usage:
#   BASE_URL=https://lms-platform.onrender.com DEMO_ADMIN_PASSWORD=... bash deploy/smoke.sh
#
# Needs bash, curl and python3 -- all present on GitHub's runners.

set -euo pipefail

BASE_URL="${BASE_URL:?set BASE_URL, e.g. http://localhost:8080}"
DEMO_TENANT="${DEMO_TENANT:-demo}"
DEMO_ADMIN_EMAIL="${DEMO_ADMIN_EMAIL:-admin@demo.local}"
DEMO_ADMIN_PASSWORD="${DEMO_ADMIN_PASSWORD:?set DEMO_ADMIN_PASSWORD}"
# A free Render instance takes about a minute to wake; allow for two.
READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-150}"

BASE_URL="${BASE_URL%/}"
FAILURES=0

pass() { echo "  ok    $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }

# Status code of a request; the body goes to the file named by $1.
status_of() {
    local out="$1"; shift
    curl -sS -o "$out" -w '%{http_code}' --max-time 30 "$@" || echo "000"
}

BODY=$(mktemp)
trap 'rm -f "$BODY"' EXIT

echo "== Smoke: $BASE_URL"

# --- 1. The process is up and ready -------------------------------------------
deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
until [ "$(status_of "$BODY" "$BASE_URL/actuator/health/readiness")" = "200" ]; do
    if [ "$SECONDS" -ge "$deadline" ]; then
        fail "readiness did not report UP within ${READY_TIMEOUT_SECONDS}s"
        exit 1
    fi
    sleep 3
done
pass "readiness is UP"

# --- 2. The contract is served from the binary --------------------------------
if [ "$(status_of "$BODY" "$BASE_URL/openapi.yaml")" = "200" ] && grep -q '^openapi: 3' "$BODY"; then
    pass "openapi.yaml is served"
else
    fail "openapi.yaml is not served"
fi

# --- 3. Nothing is readable without a token -----------------------------------
code=$(status_of "$BODY" "$BASE_URL/api/v1/partners")
if [ "$code" = "401" ]; then
    pass "an anonymous read is refused with 401"
else
    fail "an anonymous read answered $code, expected 401"
fi

# --- 4. The seeded administrator can sign in ----------------------------------
# The request body is built by python so that a password containing quotes or
# backslashes cannot break the JSON -- and is never echoed.
LOGIN=$(DEMO_TENANT="$DEMO_TENANT" DEMO_ADMIN_EMAIL="$DEMO_ADMIN_EMAIL" \
        DEMO_ADMIN_PASSWORD="$DEMO_ADMIN_PASSWORD" python3 -c '
import json, os
print(json.dumps({"tenantCode": os.environ["DEMO_TENANT"],
                  "email": os.environ["DEMO_ADMIN_EMAIL"],
                  "password": os.environ["DEMO_ADMIN_PASSWORD"]}))')
code=$(status_of "$BODY" -X POST -H 'Content-Type: application/json' --data "$LOGIN" \
       "$BASE_URL/api/v1/auth/login")
if [ "$code" != "200" ]; then
    fail "sign-in answered $code"
    echo "== Smoke FAILED: cannot continue without a token"
    exit 1
fi
TOKEN=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["accessToken"])' "$BODY")
PERMISSIONS=$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1])).get("permissions") or []))' "$BODY")
pass "the demo administrator signed in holding $PERMISSIONS permissions"

# --- 5. Every module's read side answers --------------------------------------
# One cheap, bounded read per context. A 500 here is usually a query or a
# serialisation that works on the JVM and not in the native image.
for path in \
    "/api/v1/users" \
    "/api/v1/partners?limit=1" \
    "/api/v1/vehicles?limit=1" \
    "/api/v1/drivers?limit=1" \
    "/api/v1/terminals?limit=1" \
    "/api/v1/compliance/expiring" \
    "/api/v1/orders?limit=1" \
    "/api/v1/loads?limit=1" \
    "/api/v1/allocations?limit=1" \
    "/api/v1/trips?limit=1" \
    "/api/v1/telematics/deviations" \
    "/api/v1/freight-bills?limit=1" \
    "/api/v1/exceptions?limit=1" \
    "/api/v1/exceptions/summary"; do
    code=$(status_of "$BODY" -H "Authorization: Bearer $TOKEN" "$BASE_URL$path")
    if [ "$code" = "200" ] && python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$BODY" 2>/dev/null; then
        pass "GET $path"
    else
        fail "GET $path answered $code"
    fi
done

echo
if [ "$FAILURES" -gt 0 ]; then
    echo "== Smoke FAILED: $FAILURES check(s)"
    exit 1
fi
echo "== Smoke passed"
