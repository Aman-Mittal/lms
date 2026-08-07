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

# Conventions this project holds that no compiler, linter or test will catch.
#
# Every rule here exists because breaking it produced a real failure in this
# repository, or because breaking it would fail silently -- which is worse than
# failing loudly and is exactly what an automated check is for.
#
# Runs on every push and every pull request, and is runnable locally:
#
#     .github/scripts/check-conventions.sh
#
# Exits non-zero on the first category with violations, having reported all of
# them.

set -uo pipefail

cd "$(dirname "$0")/../.."

FAILURES=0
MAIN=backend/src/main/java
MIGRATIONS=backend/src/main/resources/db/migration

# Set by fail(); each section resets it so a section can report "ok" without
# depending on whether an earlier section passed.
SECTION_FAILURES=0

fail() {
    echo "::error::$1"
    FAILURES=$((FAILURES + 1))
    SECTION_FAILURES=$((SECTION_FAILURES + 1))
}

section() {
    SECTION_FAILURES=0
    echo "-- $1"
}

pass() {
    echo "  ok  $1"
}

echo "== Conventions =="

# ---------------------------------------------------------------------------
# 1. Aggregate roots must carry @Version
# ---------------------------------------------------------------------------
#
# The single most expensive mistake in this codebase, hit three times.
#
# Spring Data JDBC decides between INSERT and UPDATE by asking whether the
# entity looks new. An entity with a client-assigned @Id and no @Version always
# looks like an existing row, so save() issues an UPDATE that matches nothing --
# and returns normally. The write is silently lost. It surfaces much later as
# "the order has no lines" or "the document was never attached", nowhere near
# the cause.
#
# Join tables are exempt because they are not entities: they are written with
# plain SQL through PlanningLinks, which has no such ambiguity.

section "aggregate roots declare @Version"
UNVERSIONED=""
while IFS= read -r file; do
    grep -q "@Id" "$file" || continue
    grep -q "@Version" "$file" || UNVERSIONED="${UNVERSIONED}${file}"$'\n'
done < <(grep -rl "@Table(" "$MAIN" --include='*.java')

if [ -n "$UNVERSIONED" ]; then
    fail "@Table entities with @Id and no @Version -- save() will silently do nothing:"
    echo "$UNVERSIONED" | sed '/^$/d' | sed 's/^/      /'
else
    pass "every @Table entity with an @Id declares @Version"
fi

# ---------------------------------------------------------------------------
# 2. Migrations
# ---------------------------------------------------------------------------

section "migrations are well formed"

BAD_NAMES=$(find "$MIGRATIONS" -name '*.sql' -printf '%f\n' | grep -Ev '^V[0-9]+__[a-z0-9_]+\.sql$' || true)
if [ -n "$BAD_NAMES" ]; then
    fail "migration filenames must match V<n>__lower_snake_case.sql:"
    echo "$BAD_NAMES" | sed 's/^/      /'
else
    pass "migration filenames are well formed"
fi

# Two migrations sharing a version number is not a build error. Flyway fails at
# startup, which on Render means a deploy that goes down rather than one that
# never comes up.
DUPLICATE_VERSIONS=$(find "$MIGRATIONS" -name '*.sql' -printf '%f\n' \
    | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | uniq -d || true)
if [ -n "$DUPLICATE_VERSIONS" ]; then
    fail "duplicate Flyway version numbers -- startup will fail, not the build: $DUPLICATE_VERSIONS"
else
    pass "Flyway version numbers are unique"
fi

# ---------------------------------------------------------------------------
# 3. Every business table is protected by row-level security
# ---------------------------------------------------------------------------
#
# RLS is the tenant boundary (DOCS/adr/0005). A table created without a policy
# is not a compile error, is not a test failure, and leaks across tenants the
# first time a WHERE clause is forgotten. The one table that legitimately has
# no tenant_id is Spring Modulith's event_publication, created verbatim in V1.

section "new tables enable row-level security"
for file in "$MIGRATIONS"/*.sql; do
    grep -q "CREATE TABLE" "$file" || continue
    case "$(basename "$file")" in
        V1__*) continue ;;  # Modulith's own outbox table, copied from the jar.
    esac
    if ! grep -q "ROW LEVEL SECURITY" "$file"; then
        fail "$(basename "$file") creates tables but never enables row-level security"
    fi
done
[ "$SECTION_FAILURES" -eq 0 ] && pass "every table-creating migration enables row-level security"

# ---------------------------------------------------------------------------
# 4. Dependencies deliberately kept out
# ---------------------------------------------------------------------------
#
# Lombok: annotation-processor-generated code is one more thing to reason about
#   under AOT, and records cover what it would be used for.
# springdoc: reflection-heavy, and the OpenAPI spec is the hand-authored
#   contract source of truth rather than something derived from controllers.
# PostGIS: GPLv2, which the Apache-2.0 compliance rails forbid (DOCS/adr/0004).

section "excluded dependencies stay excluded"
for forbidden in lombok springdoc postgis; do
    if grep -rqi "$forbidden" backend/pom.xml; then
        fail "backend/pom.xml references '$forbidden', which this project deliberately excludes"
    fi
done
[ "$SECTION_FAILURES" -eq 0 ] && pass "no lombok, springdoc or postgis"

# ---------------------------------------------------------------------------
# 5. The ASF boilerplate header must not appear
# ---------------------------------------------------------------------------
#
# This project is Apache-2.0 licensed but is NOT an Apache Software Foundation
# project. The ASF boilerplate asserts contributor licence agreements that do
# not exist here, so using it is a false provenance claim rather than a style
# slip. Easy to introduce by copying a file from a sibling ASF repository.

section "license headers claim the right provenance"
# This script names the string in order to look for it, so it excludes itself.
ASF_HITS=$(grep -rl "Licensed to the Apache Software Foundation" \
    --include='*.java' --include='*.sql' --include='*.yml' --include='*.yaml' \
    --include='*.xml' --include='*.sh' --include='*.ts' \
    --exclude='check-conventions.sh' . 2>/dev/null || true)
if [ -n "$ASF_HITS" ]; then
    fail "ASF boilerplate header found -- this is not an ASF project, see DOCS/adr/0003"
    echo "$ASF_HITS" | sed 's/^/      /'
else
    pass "no ASF provenance claims"
fi

# ---------------------------------------------------------------------------
# 6. Pagination is keyset, never OFFSET
# ---------------------------------------------------------------------------
#
# OFFSET n makes the database walk and discard n rows before returning
# anything, so page 500 costs 500 times page 1 -- on a 0.1-CPU instance that is
# the difference between a screen and a timeout. It is also incorrect under
# concurrent writes: rows shift and pages silently skip or duplicate.

section "pagination is keyset, not OFFSET"
# Requires a bind parameter or a literal after the keyword, so that Slice's own
# Javadoc explaining why OFFSET is avoided does not trip the check that enforces
# it. Comment lines are dropped first for the same reason.
OFFSET_HITS=$(grep -rnE "OFFSET[[:space:]]+[:?0-9]" "$MAIN" --include='*.java' \
    | grep -vE "^[^:]+:[0-9]+:[[:space:]]*(\*|//)" || true)
if [ -n "$OFFSET_HITS" ]; then
    fail "OFFSET pagination found -- use com.lms.shared.query.Slice (keyset) instead"
    echo "$OFFSET_HITS" | sed 's/^/      /'
else
    pass "no OFFSET pagination"
fi

# ---------------------------------------------------------------------------
# 7. Logging goes through the logger
# ---------------------------------------------------------------------------
#
# Logs are structured JSON to stdout so an aggregator can ingest them without
# Grok patterns (vision document 4.4). A println bypasses that, and a stack
# trace printed to stderr loses the correlation id that makes it traceable.

section "no stray console output"
CONSOLE_HITS=$(grep -rnE "System\.(out|err)\.print|printStackTrace" "$MAIN" --include='*.java' || true)
if [ -n "$CONSOLE_HITS" ]; then
    fail "console output in main sources -- use SLF4J so the correlation id is carried"
    echo "$CONSOLE_HITS" | sed 's/^/      /'
else
    pass "no console output in main sources"
fi

# ---------------------------------------------------------------------------

echo
if [ "$FAILURES" -gt 0 ]; then
    echo "FAILED: $FAILURES convention violation(s)"
    exit 1
fi
echo "All conventions hold."
