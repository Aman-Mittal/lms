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

# Physical backup of the DR primary with pgBackRest.
#
#   backup.sh full    weekly
#   backup.sh diff    daily (changes since the last full)
#   backup.sh incr    hourly (changes since the last backup of any type)
#   backup.sh init    create the stanza and check archiving (first run)
#
# Creates the stanza first if it does not exist, so the first `full` on a new
# stack just works. pgBackRest itself expires backups and WAL past the
# retention in pgbackrest.conf (two fulls) after every backup. A diff or incr
# with no full to build on is promoted to a full by pgBackRest.
#
# Cron, on the host running the stack (see crontab.example):
#   15 2 * * 0    deploy/dr/backup.sh full
#   15 2 * * 1-6  deploy/dr/backup.sh diff
#   45 * * * *    deploy/dr/backup.sh incr

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

type="${1:-}"
case "$type" in
    full | diff | incr | init) ;;
    *) die "usage: $0 <full|diff|incr|init>" ;;
esac

# Idempotent: succeeds when the stanza already exists and matches the cluster.
primary_pgbackrest stanza-create >/dev/null

if [ "$type" = init ]; then
    primary_pgbackrest check
    log "stanza $STANZA ready"
    exit 0
fi

started="$(now_seconds)"
primary_pgbackrest backup --type="$type"
log "$type backup finished in $(elapsed_since "$started")s"
primary_pgbackrest info --output=text | sed -n '1,40p' >&2
