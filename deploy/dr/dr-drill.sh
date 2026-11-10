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

# Restore drill: proves, end to end, that the backups restore and that the
# recovery objectives hold. Run weekly by .github/workflows/dr-drill.yml and
# runnable locally:
#
#   deploy/dr/dr-drill.sh                     # local volume repository
#   DR_REPO_TYPE=s3 deploy/dr/dr-drill.sh     # S3 repository (SeaweedFS)
#
# It builds its own stack under its own compose project (lms-dr-drill), with
# secrets generated for the run, and removes every container and volume it
# created when it exits -- on failure too. It refuses to start if that project
# already exists, and it never touches the lms-dr stack or the dev stack.
#
# Steps:
#   1. Start the stack; apply the schema by running every Flyway migration
#      with psql, in version order. Flyway itself is not needed for this:
#      the migrations are plain SQL with no placeholders, and running them
#      directly avoids building the backend (a JDK and a Maven build) just to
#      create tables. Seed two tenants.
#   2. Create the stanza; full backup.
#   3. Marker rows in dr_drill.dr_drill_marker -- a schema the drill creates
#      itself, outside every migration: batch A, a restore point, time T,
#      batch B, WAL switch.
#   4. PITR to T, to the restore point, and to the latest WAL, each into a new
#      container: A present and B absent, twice; both present at the end.
#   5. RPO observed: a write with no forced switch, timed until archived.
#   6. Logical dump and restore round trip: every table's row count equal.
#   7. Tenant export: scoped to one tenant, manifest checksums correct.
#   8. pgbackrest check and verify; archiver has no outstanding failure.
#   9. Every restore within the RTO budget (DR_RTO_SECONDS, default 3600).
#
# Writes a log (DR_DRILL_LOG, default deploy/dr/drill-<time>.log) holding
# timings and assertions only: no backup, dump or secret is ever written there.

set -Eeuo pipefail
export DR_PROJECT="${DR_PROJECT:-lms-dr-drill}"
export DR_PG_PORT=""
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

RTO_SECONDS="${DR_RTO_SECONDS:-3600}"
RPO_SECONDS="${DR_RPO_SECONDS:-300}"
LOG="${DR_DRILL_LOG:-$DR_DIR/drill-$(date -u +%Y%m%dT%H%M%SZ).log}"
mkdir -p "$(dirname "$LOG")"
exec > >(tee -a "$LOG") 2>&1

WORK="$(mktemp -d)"
export DR_SECRETS_DIR="$WORK/secrets"
SUMMARY=()
FAILED=0
DRILL_STARTED="$(now_seconds)"

pass() {
    SUMMARY+=("PASS  $*")
    log "PASS $*"
}

fail() {
    SUMMARY+=("FAIL  $*")
    log "FAIL $*"
    FAILED=$((FAILED + 1))
}

check() {
    local what="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        pass "$what ($actual)"
    else
        fail "$what: expected $expected, got $actual"
    fi
}

cleanup() {
    local status=$?
    set +e
    log "cleaning up project $DR_PROJECT"
    mapfile -t containers < <(docker ps -aq --filter "label=$DR_LABEL")
    [ "${#containers[@]}" -eq 0 ] || docker rm -f "${containers[@]}" >/dev/null
    DR_REPO_TYPE=s3 dc down -v --remove-orphans >/dev/null 2>&1
    mapfile -t volumes < <(docker volume ls -q --filter "label=$DR_LABEL")
    [ "${#volumes[@]}" -eq 0 ] || docker volume rm "${volumes[@]}" >/dev/null
    rm -rf "$WORK"
    echo
    echo "== DR drill summary ($DR_REPO_TYPE repository, $(elapsed_since "$DRILL_STARTED")s) =="
    printf '%s\n' "${SUMMARY[@]}"
    if [ "$status" -ne 0 ] || [ "$FAILED" -ne 0 ]; then
        echo "DRILL FAILED (exit $status, $FAILED failed check(s))"
        [ "$status" -ne 0 ] || status=1
    else
        echo "DRILL PASSED"
    fi
    exit "$status"
}

if [ -n "$(dc ps -aq 2>/dev/null)" ] \
    || docker volume inspect "${DR_PROJECT}_pgdata" >/dev/null 2>&1; then
    die "compose project $DR_PROJECT already exists; remove it or set DR_PROJECT"
fi
trap cleanup EXIT

sql() {
    primary_psql -At "$@"
}

restored_sql() {
    docker exec -i "$1" psql -X -q -At -v ON_ERROR_STOP=1 -h 127.0.0.1 -U lms -d lms -c "$2"
}

# Runs a restore script, records its duration, returns the container name.
RESTORE_TIMES=()
restore() {
    local what="$1" output seconds container
    shift
    output="$("$@")"
    seconds="$(sed -n 's/^RESTORE_SECONDS=//p' <<<"$output")"
    container="$(sed -n 's/^RESTORE_CONTAINER=//p' <<<"$output")"
    RESTORE_TIMES+=("$what=$seconds")
    pass "$what restored and promoted in ${seconds}s"
    LAST_RESTORE="$container"
}

remove_restore() {
    docker rm -f "$1" >/dev/null
    docker volume rm "$1-data" >/dev/null
}

wait_archived() {
    local wal="$1" deadline=$(( $(date +%s) + ${2:-120} ))
    until [ "$(sql -c "SELECT coalesce(last_archived_wal >= '$wal', false) FROM pg_stat_archiver")" = t ]; do
        [ "$(date +%s)" -lt "$deadline" ] || return 1
        sleep 1
    done
}

markers_in() {
    restored_sql "$1" "SELECT count(*) FILTER (WHERE batch = 'A') || ',' || count(*) FILTER (WHERE batch = 'B') FROM dr_drill.dr_drill_marker"
}

# --- 1. stack and schema -----------------------------------------------------
log "drill: project $DR_PROJECT, repository $DR_REPO_TYPE, RTO budget ${RTO_SECONDS}s"
bash "$DR_DIR/init-env.sh"
dc build postgres
if [ "$DR_REPO_TYPE" = s3 ]; then
    dc up -d objectstore
    bash "$DR_DIR/s3-bucket.sh"
    pass "object store up, bucket created"
fi
dc up -d --wait postgres

started="$(now_seconds)"
migrations=0
while IFS= read -r migration; do
    primary_psql -1 < "$migration" >/dev/null
    migrations=$((migrations + 1))
done < <(find "$REPO_ROOT/backend/src/main/resources/db/migration" -name 'V*__*.sql' | sort -V)
pass "schema applied: $migrations migrations in $(elapsed_since "$started")s"

TENANT_A=d7d10000-0000-4000-8000-00000000000a
TENANT_B=d7d10000-0000-4000-8000-00000000000b
sql >/dev/null <<SQL
INSERT INTO tenant (id, code, name) VALUES ('$TENANT_A', 'drill-a', 'Drill A'), ('$TENANT_B', 'drill-b', 'Drill B');
INSERT INTO org_unit (id, tenant_id, slug, path, name, unit_type)
VALUES (gen_random_uuid(), '$TENANT_A', 'hq', '/hq/', 'Drill A HQ', 'HQ'),
       (gen_random_uuid(), '$TENANT_A', 'north', '/hq/north/', 'Drill A North', 'REGION'),
       (gen_random_uuid(), '$TENANT_B', 'hq', '/hq/', 'Drill B HQ', 'HQ');
SQL

# --- 2. stanza and full backup ----------------------------------------------
bash "$DR_DIR/backup.sh" init
started="$(now_seconds)"
bash "$DR_DIR/backup.sh" full
pass "full backup in $(elapsed_since "$started")s"

# --- 3. markers --------------------------------------------------------------
sql >/dev/null <<'SQL'
CREATE SCHEMA dr_drill;
CREATE TABLE dr_drill.dr_drill_marker (
    id         bigserial PRIMARY KEY,
    batch      text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
INSERT INTO dr_drill.dr_drill_marker (batch) SELECT 'A' FROM generate_series(1, 100);
SQL
bash "$DR_DIR/restore-point.sh" drill_after_a >/dev/null
sleep 1
# The database's clock, not this host's: it is the clock commit timestamps use.
TARGET_TIME="$(sql -c "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00'")"
sleep 2
sql -c "INSERT INTO dr_drill.dr_drill_marker (batch) SELECT 'B' FROM generate_series(1, 100)" >/dev/null
SWITCHED="$(sql -c 'SELECT pg_walfile_name(pg_switch_wal())')"
wait_archived "$SWITCHED" || die "WAL segment $SWITCHED was not archived"
pass "batch A, restore point, T=$TARGET_TIME, batch B, WAL archived"

# --- 4. point-in-time restores ----------------------------------------------
restore "PITR to time T" bash "$DR_DIR/restore-pitr.sh" --target-time "$TARGET_TIME"
check "PITR to time: batch A present, batch B absent" "$(markers_in "$LAST_RESTORE")" "100,0"
check "PITR to time: tenants" "$(restored_sql "$LAST_RESTORE" 'SELECT count(*) FROM tenant')" "2"
remove_restore "$LAST_RESTORE"

restore "PITR to restore point" bash "$DR_DIR/restore-pitr.sh" --target-name drill_after_a
check "PITR to restore point: batch A present, batch B absent" "$(markers_in "$LAST_RESTORE")" "100,0"
remove_restore "$LAST_RESTORE"

restore "PITR to latest" bash "$DR_DIR/restore-pitr.sh" --latest
check "PITR to latest: batches A and B present" "$(markers_in "$LAST_RESTORE")" "100,100"
remove_restore "$LAST_RESTORE"

# --- 5. RPO as observed ------------------------------------------------------
sql -c "INSERT INTO dr_drill.dr_drill_marker (batch) VALUES ('C')" >/dev/null
CURRENT_WAL="$(sql -c 'SELECT pg_walfile_name(pg_current_wal_lsn())')"
started="$(now_seconds)"
if wait_archived "$CURRENT_WAL" "$RPO_SECONDS"; then
    pass "RPO: an unforced write reached the archive in $(elapsed_since "$started")s (objective ${RPO_SECONDS}s)"
else
    fail "RPO: an unforced write was not archived within ${RPO_SECONDS}s"
fi

# --- 6. logical round trip ---------------------------------------------------
DUMP="$(bash "$DR_DIR/logical-backup.sh" "$WORK/logical")"
pass "logical backup written with checksums"
restore "logical restore" bash "$DR_DIR/restore-logical.sh" "$DUMP"
expected="$(row_counts_sql | primary_psql -At)"
actual="$(row_counts_sql | docker exec -i "$LAST_RESTORE" psql -X -q -At -h 127.0.0.1 -U lms -d lms)"
tables="$(wc -l <<<"$expected")"
if [ "$expected" = "$actual" ]; then
    pass "logical round trip: row counts equal in all $tables tables"
else
    fail "logical round trip: row counts differ"
    diff <(echo "$expected") <(echo "$actual") || true
fi
remove_restore "$LAST_RESTORE"
tamper="$WORK/tampered"
mkdir -p "$tamper"
stamp="$(basename "$DUMP" .dump)"
stamp="${stamp#lms-}"
cp "$DUMP" "$DUMP.sha256" "$(dirname "$DUMP")/globals-$stamp.sql" "$(dirname "$DUMP")/globals-$stamp.sql.sha256" "$tamper/"
echo corrupt >> "$tamper/$(basename "$DUMP")"
if refusal="$(bash "$DR_DIR/restore-logical.sh" "$tamper/$(basename "$DUMP")" 2>&1)"; then
    fail "a dump that fails its checksum was restored"
elif grep -q 'checksum mismatch' <<<"$refusal"; then
    pass "a dump that fails its checksum is refused"
else
    fail "a corrupted dump failed for another reason: $(tail -1 <<<"$refusal")"
fi

# --- 7. tenant export --------------------------------------------------------
EXPORT="$(bash "$DR_DIR/tenant-export.sh" "$TENANT_A" "$WORK/export")"
check "tenant export: org_unit rows for tenant A only" \
    "$(awk -F, '$1 == "org_unit" { print $2 }' "$EXPORT/manifest.csv")" "2"
check "tenant export: tenant row" "$(awk -F, '$1 == "tenant" { print $2 }' "$EXPORT/manifest.csv")" "1"
if grep -rq "$TENANT_B" "$EXPORT"; then
    fail "tenant export contains another tenant's id"
else
    pass "tenant export contains no other tenant's rows"
fi
bad=0
while IFS=, read -r table _ sum; do
    [ "$(sha256sum "$EXPORT/$table.csv" | cut -d' ' -f1)" = "$sum" ] || bad=$((bad + 1))
done < <(tail -n +2 "$EXPORT/manifest.csv")
check "tenant export: manifest checksums ($(($(wc -l < "$EXPORT/manifest.csv") - 1)) tables)" "$bad" "0"

# --- 8. repository health ----------------------------------------------------
if primary_pgbackrest check >/dev/null; then pass "pgbackrest check"; else fail "pgbackrest check"; fi
if primary_pgbackrest verify >/dev/null; then pass "pgbackrest verify"; else fail "pgbackrest verify"; fi
primary_pgbackrest info
check "archiver: no failure newer than the last success" \
    "$(sql -c 'SELECT last_failed_time IS NULL OR last_failed_time < last_archived_time FROM pg_stat_archiver')" "t"

# --- 9. RTO ------------------------------------------------------------------
for entry in "${RESTORE_TIMES[@]}"; do
    seconds="${entry##*=}"
    if awk -v s="$seconds" -v b="$RTO_SECONDS" 'BEGIN { exit !(s <= b) }'; then
        pass "RTO: ${entry%%=*} ${seconds}s <= ${RTO_SECONDS}s"
    else
        fail "RTO: ${entry%%=*} ${seconds}s exceeds ${RTO_SECONDS}s"
    fi
done
