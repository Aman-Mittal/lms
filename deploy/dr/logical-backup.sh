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

# Logical backup: a custom-format dump of the lms database plus the cluster's
# roles, each with a sha256 file beside it, then prunes old sets.
#
#   logical-backup.sh [out-dir]
#
# out-dir defaults to $DR_BACKUP_DIR or deploy/dr/backups/logical (git-ignored).
# Produces, for one timestamp:
#   lms-<ts>.dump            pg_dump -Fc
#   globals-<ts>.sql         pg_dumpall --globals-only --no-role-passwords
#   *.sha256                 one checksum file per artefact
#
# Roles matter: every application statement runs as lms_app (V3), and the
# grants and policies in the dump name it, so a dump restored without its
# roles fails at the first GRANT. Role passwords are deliberately not dumped:
# the file would then be a credential store. Set them again after a restore.
#
# Keeps the newest $DR_LOGICAL_KEEP sets (default 7). With DR_SOURCE_URL set,
# dumps that database (e.g. a managed one) instead of the DR stack.
#
# These files hold every tenant's data. Keep them off shared drives and never
# commit them: this repository is public.

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

out="${1:-${DR_BACKUP_DIR:-$DR_DIR/backups/logical}}"
keep="${DR_LOGICAL_KEEP:-7}"
mkdir -p "$out"
chmod 700 "$out"
umask 077
ts="$(date -u +%Y%m%dT%H%M%SZ)"
dump="lms-$ts.dump"
globals="globals-$ts.sql"

started="$(now_seconds)"
# "$DB" is expanded by pg_sh's shell, not this one.
# shellcheck disable=SC2016
pg_sh 'pg_dumpall --globals-only --no-role-passwords -d "$DB"' > "$out/$globals.partial"
# shellcheck disable=SC2016
pg_sh 'pg_dump -Fc -d "$DB"' > "$out/$dump.partial"
# Renamed only once complete, so a crash never leaves a plausible-looking dump.
mv "$out/$globals.partial" "$out/$globals"
mv "$out/$dump.partial" "$out/$dump"
(
    cd "$out"
    sha256sum "$dump" > "$dump.sha256"
    sha256sum "$globals" > "$globals.sha256"
)
log "logical backup $out/$dump ($(du -h "$out/$dump" | cut -f1)) in $(elapsed_since "$started")s"

# Retention: newest $keep dumps by name (the timestamp sorts); remove the rest
# together with their globals and checksums.
mapfile -t old < <(find "$out" -maxdepth 1 -name 'lms-*.dump' -printf '%f\n' | sort -r | tail -n +"$((keep + 1))")
for stale in "${old[@]}"; do
    stamp="${stale#lms-}"
    stamp="${stamp%.dump}"
    rm -f "$out/lms-$stamp.dump" "$out/lms-$stamp.dump.sha256" \
        "$out/globals-$stamp.sql" "$out/globals-$stamp.sql.sha256"
    log "pruned logical backup $stamp"
done

echo "$out/$dump"
