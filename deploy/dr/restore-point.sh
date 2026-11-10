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

# Creates a named restore point on the DR primary, e.g. before a migration or a
# bulk correction, so the database can later be restored to exactly that
# moment with `restore-pitr.sh --target-name <name>`.
#
#   restore-point.sh <name>
#
# Then switches WAL, so the restore point reaches the archive now rather than
# within archive_timeout. Names should be unique; if one is reused, recovery
# stops at the first point with that name.

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

name="${1:-}"
[[ "$name" =~ ^[A-Za-z0-9_.:-]{1,63}$ ]] || die "usage: $0 <name>  (letters, digits, _ . : - ; at most 63)"

lsn="$(primary_psql -At -v name="$name" <<'SQL'
SELECT pg_create_restore_point(:'name');
SQL
)"
primary_psql -At -c 'SELECT pg_switch_wal()' >/dev/null
log "restore point '$name' at LSN $lsn, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "$lsn"
