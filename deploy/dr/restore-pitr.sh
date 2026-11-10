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

# Point-in-time restore from the pgBackRest repository into a NEW container
# and volume. It never touches the running primary.
#
#   restore-pitr.sh --target-time '2026-11-10 14:30:00+00'
#   restore-pitr.sh --target-name before_v49
#   restore-pitr.sh --latest
#   options: --name <container>  --set <backup label>
#
# pgBackRest picks the newest backup that precedes the target, restores it
# into a fresh volume and writes the recovery settings; PostgreSQL then replays
# archived WAL up to the target and promotes. The script waits for the
# promotion and prints how long the whole restore took -- the number to hold
# against the one-hour RTO.
#
# The copy starts with archive_mode=off, so it cannot push a second timeline
# into the repository the primary is still writing. It keeps running for
# inspection (127.0.0.1, random port, printed at the end). To make it the new
# primary, see "Promoting a restored copy" in README.md.

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

target_args=()
label=""
name="$DR_PROJECT-pitr-$(date -u +%Y%m%d%H%M%S)"
timeout="${DR_RTO_SECONDS:-3600}"
while [ $# -gt 0 ]; do
    case "$1" in
        --target-time) target_args=(--type=time "--target=$2" --target-action=promote); label="time $2"; shift 2 ;;
        --target-name) target_args=(--type=name "--target=$2" --target-action=promote); label="restore point $2"; shift 2 ;;
        --latest) target_args=(); label="latest"; shift ;;
        --name) name="$2"; shift 2 ;;
        --set) target_args+=("--set=$2"); shift 2 ;;
        *) die "unknown argument $1" ;;
    esac
done
[ -n "$label" ] || die "usage: $0 --target-time <ts with zone> | --target-name <name> | --latest [--name <container>] [--set <label>]"
[ -s "$DR_SECRETS_DIR/repo_cipher_pass" ] || die "no repository passphrase in $DR_SECRETS_DIR"
docker volume inspect "$(repo_volume)" >/dev/null 2>&1 || [ "$DR_REPO_TYPE" = s3 ] \
    || die "no repository volume $(repo_volume)"

mapfile -t mounts < <(secret_mounts)
# The DR_S3_* settings are passed through when set, as for the primary.
common=(--label "$DR_LABEL" --network "$(network)" -e "DR_REPO_TYPE=$DR_REPO_TYPE"
    -e DR_S3_ENDPOINT -e DR_S3_PORT -e DR_S3_BUCKET -e DR_S3_REGION -e DR_S3_URI_STYLE -e DR_S3_CA_FILE
    -e POSTGRES_USER=lms -e POSTGRES_HOST_AUTH_METHOD=trust
    -v "$name-data:/var/lib/postgresql/data" "${mounts[@]}")
if [ "$DR_REPO_TYPE" = posix ]; then
    # Read-only: a restore has no business writing to the repository.
    common+=(-v "$(repo_volume):/var/lib/pgbackrest:ro")
fi

log "restoring to $label into $name"
started="$(now_seconds)"
docker volume create --label "$DR_LABEL" "$name-data" >/dev/null
docker run --rm "${common[@]}" "$DR_IMAGE" \
    pgbackrest --stanza="$STANZA" --log-level-file=off --lock-path=/tmp/pgbackrest restore "${target_args[@]}"
restored="$(elapsed_since "$started")"
docker run -d --name "$name" "${common[@]}" -p 127.0.0.1::5432 "$DR_IMAGE" \
    postgres -c archive_mode=off >/dev/null
wait_promoted "$name" "$timeout"
took="$(elapsed_since "$started")"

port="$(docker port "$name" 5432/tcp | head -1 | sed 's/.*://')"
log "restored ($label) in ${took}s (files ${restored}s, replay and promotion the rest)"
log "container $name on 127.0.0.1:$port -- psql -h 127.0.0.1 -p $port -U lms lms"
echo "RESTORE_SECONDS=$took"
echo "RESTORE_CONTAINER=$name"
