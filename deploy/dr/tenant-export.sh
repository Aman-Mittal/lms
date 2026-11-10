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

# Exports one tenant's rows, table by table, to CSV with a manifest.
#
#   tenant-export.sh <tenant-uuid> <out-dir>
#
# For tenant offboarding (handing a customer their data) and for inspecting one
# tenant's state. It is NOT a backup and cannot restore anything: there is no
# schema, no sequences and no other tenant's rows to satisfy shared references.
# Use restore-pitr.sh or restore-logical.sh to restore.
#
# The tables are discovered, not listed: every base table in `public` with a
# tenant_id column, plus `tenant` itself -- so a table added by a later
# migration is exported without anybody editing this script.
#
# The export is scoped by row-level security rather than by a WHERE clause:
# the session switches to lms_app (no superuser or owner exemption) and sets
# app.tenant_id exactly as the application does. A missing or wrong filter
# can therefore only ever yield fewer rows, never another tenant's. All tables
# are read in one REPEATABLE READ transaction, so they agree with each other.
#
# Output in <out-dir>/<tenant-uuid>-<timestamp>/:
#   <table>.csv     COPY ... CSV HEADER
#   manifest.csv    table,rows,sha256
#   export.txt      tenant, time, table count
#
# With DR_SOURCE_URL set, exports from that database instead of the DR stack.

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

tenant="${1:-}"
out="${2:-}"
if [ -z "$tenant" ] || [ -z "$out" ]; then
    die "usage: $0 <tenant-uuid> <out-dir>"
fi
# Validated here because it is interpolated into SQL below.
[[ "$tenant" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] \
    || die "not a UUID: $tenant"

ts="$(date -u +%Y%m%dT%H%M%SZ)"
dest="$out/$tenant-$ts"
mkdir -p "$dest"
chmod 700 "$dest"
umask 077

scope="BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL ROLE lms_app;
SELECT set_config('app.tenant_id', '$tenant', true) \\g /dev/null"

# "$DB" below is expanded by pg_sh's shell, not this one.
# shellcheck disable=SC2016
# 1. Which tables -- read as lms_app, so only tables it may read are listed.
mapfile -t tables < <(printf '%s\n%s\n' "$scope" "
SELECT 'tenant'
UNION
SELECT c.table_name
  FROM information_schema.columns c
  JOIN information_schema.tables t
    ON t.table_schema = c.table_schema AND t.table_name = c.table_name
 WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id'
   AND t.table_type = 'BASE TABLE'
 ORDER BY 1;
COMMIT;" | pg_sh 'psql -X -q -At -v ON_ERROR_STOP=1 -d "$DB"')
[ "${#tables[@]}" -gt 0 ] || die "no tables found"
for table in "${tables[@]}"; do
    [[ "$table" =~ ^[a-z_][a-z0-9_]*$ ]] || die "unexpected table name: $table"
done

# 2. One transaction: every table's rows and the row counts, written inside
#    the container and streamed back as a tar, so nothing but data crosses.
work="/tmp/tenant-export-$ts-$$"
{
    echo "$scope"
    counts=""
    for table in "${tables[@]}"; do
        echo "\\copy (SELECT * FROM public.$table) TO '$work/$table.csv' WITH (FORMAT csv, HEADER)"
        counts="${counts:+$counts UNION ALL }SELECT '$table', count(*) FROM public.$table"
    done
    echo "\\copy ($counts) TO '$work/_counts.csv' WITH (FORMAT csv)"
    echo "COMMIT;"
} | pg_sh "set -e; mkdir -m 700 $work; psql -X -q -v ON_ERROR_STOP=1 -d \"\$DB\" >/dev/null;
           tar -C $work -cf - .; rm -rf $work" | tar -C "$dest" -xf -

# 3. Manifest.
{
    echo "table,rows,sha256"
    while IFS=, read -r table rows; do
        echo "$table,$rows,$(sha256sum "$dest/$table.csv" | cut -d' ' -f1)"
    done < "$dest/_counts.csv"
} > "$dest/manifest.csv"
rm -f "$dest/_counts.csv"
total="$(awk -F, 'NR > 1 { s += $2 } END { print s + 0 }' "$dest/manifest.csv")"
{
    echo "tenant=$tenant"
    echo "exported_at=$ts"
    echo "tables=${#tables[@]}"
    echo "rows=$total"
    echo "purpose=offboarding or inspection; not a restorable backup"
} > "$dest/export.txt"

log "exported $total rows from ${#tables[@]} tables to $dest"
echo "$dest"
