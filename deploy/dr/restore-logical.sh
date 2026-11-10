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

# Restores a logical backup into a NEW, empty PostgreSQL 17 container -- never
# into a running database -- after verifying its checksums.
#
#   restore-logical.sh <path/to/lms-<ts>.dump> [--globals <globals.sql>] [--name <container>]
#
# The globals file defaults to globals-<ts>.sql beside the dump. Order: verify
# both checksums, start a fresh cluster, create the roles, pg_restore the
# database. The container keeps running for inspection (127.0.0.1, random
# port, printed at the end); remove it with `docker rm -f <name>` and its
# volume with `docker volume rm <name>-data`.
#
# Role passwords are not in the dump (logical-backup.sh); set them before
# pointing anything but a superuser at the restored database.

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

dump="${1:-}"
if [ -z "$dump" ] || [ ! -f "$dump" ]; then
    die "usage: $0 <dump> [--globals <file>] [--name <container>]"
fi
shift
dir="$(cd "$(dirname "$dump")" && pwd)"
base="$(basename "$dump")"
stamp="${base#lms-}"
stamp="${stamp%.dump}"
globals="$dir/globals-$stamp.sql"
name="$DR_PROJECT-logical-$(date -u +%Y%m%d%H%M%S)"
while [ $# -gt 0 ]; do
    case "$1" in
        --globals) globals="$2"; shift 2 ;;
        --name) name="$2"; shift 2 ;;
        *) die "unknown argument $1" ;;
    esac
done
[ -f "$globals" ] || die "no globals file $globals"

log "verifying checksums"
for file in "$dir/$base" "$globals"; do
    [ -f "$file.sha256" ] || die "no checksum file $file.sha256"
    (cd "$(dirname "$file")" && sha256sum --quiet -c "$(basename "$file").sha256") \
        || die "checksum mismatch for $file -- refusing to restore it"
done

started="$(now_seconds)"
docker image inspect "$DR_IMAGE" >/dev/null 2>&1 || dc build postgres
docker volume create --label "$DR_LABEL" "$name-data" >/dev/null
docker run -d --name "$name" --label "$DR_LABEL" \
    -e POSTGRES_DB=lms -e POSTGRES_USER=lms -e POSTGRES_HOST_AUTH_METHOD=trust \
    -v "$name-data:/var/lib/postgresql/data" \
    -p 127.0.0.1::5432 \
    "$DR_IMAGE" postgres >/dev/null
wait_promoted "$name" 300

log "creating roles"
# Errors for roles the new cluster already has (lms, its bootstrap superuser)
# are expected and harmless; anything else would surface in pg_restore below.
docker exec -i "$name" psql -X -q -h 127.0.0.1 -U lms -d postgres < "$globals" 2>&1 \
    | grep -v 'already exists' >&2 || true

log "restoring $base into $name"
docker exec -i "$name" pg_restore --exit-on-error -h 127.0.0.1 -U lms -d lms < "$dir/$base"

port="$(docker port "$name" 5432/tcp | head -1 | sed 's/.*://')"
took="$(elapsed_since "$started")"
log "logical restore done in ${took}s: container $name, 127.0.0.1:$port"
echo "RESTORE_SECONDS=$took"
echo "RESTORE_CONTAINER=$name"
