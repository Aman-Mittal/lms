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

# Shared by the DR scripts; sourced, not run. Its variables are used by the
# scripts that source it, which shellcheck cannot see from here.
# shellcheck disable=SC2034
#
# Environment:
#   DR_PROJECT      compose project name (default lms-dr). The drill uses its
#                   own, so it can never touch a stack you are running.
#   DR_SECRETS_DIR  where init-env.sh put the secrets (default ./secrets)
#   DR_REPO_TYPE    posix (default) or s3
#   DR_SOURCE_URL   logical-backup.sh and tenant-export.sh only: read from this
#                   libpq connection string (e.g. a managed database) instead
#                   of the DR stack. Passed to a throwaway container through
#                   the environment, never on a command line.

DR_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$DR_DIR/../.." && pwd)"
export DR_PROJECT="${DR_PROJECT:-lms-dr}"
export DR_SECRETS_DIR="${DR_SECRETS_DIR:-$DR_DIR/secrets}"
export DR_REPO_TYPE="${DR_REPO_TYPE:-posix}"
STANZA=lms
DR_IMAGE=lms-dr-postgres:17.7-pgbackrest
# Every container and volume a restore creates carries this label, so that it
# can be found and removed (dr-drill.sh removes them all on exit).
DR_LABEL="lms.dr.project=$DR_PROJECT"

log() {
    printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2
}

die() {
    log "ERROR: $*"
    exit 1
}

dc() {
    local profiles=()
    if [ "$DR_REPO_TYPE" = s3 ]; then
        profiles=(--profile s3)
    fi
    docker compose -p "$DR_PROJECT" -f "$DR_DIR/docker-compose.dr.yml" "${profiles[@]}" "$@"
}

# psql against the DR primary, as the bootstrap superuser.
primary_psql() {
    dc exec -T -u postgres postgres psql -X -q -v ON_ERROR_STOP=1 -U lms -d lms "$@"
}

primary_pgbackrest() {
    dc exec -T -u postgres postgres pgbackrest --stanza="$STANZA" "$@"
}

# Runs a shell script with the PostgreSQL 17 client tools, against the DR
# primary or, with DR_SOURCE_URL set, against that database. The script sees
# the connection string as "$DB" and its stdin/stdout are passed through.
pg_sh() {
    local script="$1"
    if [ -n "${DR_SOURCE_URL:-}" ]; then
        DB="$DR_SOURCE_URL" docker run --rm -i -e DB postgres:17.7 sh -c "$script"
    else
        dc exec -T -u postgres -e DB="dbname=lms user=lms" postgres sh -c "$script"
    fi
}

repo_volume() {
    echo "${DR_PROJECT}_pgbackrest-repo"
}

network() {
    echo "${DR_PROJECT}_default"
}

# Mount options giving a restore container the same secrets as the primary.
secret_mounts() {
    local name
    for name in repo_cipher_pass s3_access_key s3_secret_key; do
        printf -- '-v\n%s:/run/secrets/%s:ro\n' "$DR_SECRETS_DIR/$name" "$name"
    done
    printf -- '-v\n%s:/run/secrets/s3_tls_cert:ro\n' "$DR_SECRETS_DIR/s3_tls.crt"
}

now_seconds() {
    date +%s.%N
}

elapsed_since() {
    awk -v start="$1" -v end="$(now_seconds)" 'BEGIN { printf "%.1f", end - start }'
}

# Waits until a container accepts TCP connections (the official entrypoint's
# initialisation server listens on the socket only) and is out of recovery.
wait_promoted() {
    local container="$1" timeout="$2" deadline state
    deadline=$(( $(date +%s) + timeout ))
    while :; do
        state="$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo gone)"
        if [ "$state" != true ]; then
            docker logs --tail 40 "$container" >&2 2>&1 || true
            die "$container stopped before it was ready"
        fi
        if docker exec "$container" pg_isready -q -h 127.0.0.1 -U lms -d lms 2>/dev/null \
            && [ "$(docker exec "$container" psql -X -At -h 127.0.0.1 -U lms -d lms \
                    -c 'SELECT pg_is_in_recovery()' 2>/dev/null)" = f ]; then
            return 0
        fi
        if [ "$(date +%s)" -ge "$deadline" ]; then
            docker logs --tail 40 "$container" >&2 2>&1 || true
            die "$container was not promoted within ${timeout}s"
        fi
        sleep 1
    done
}

# Exact row counts of every table in the given schemas, as "schema.table|n",
# sorted. Run as a superuser, which row-level security does not filter.
row_counts_sql() {
    cat <<'SQL'
SELECT format('%I.%I', n.nspname, c.relname) || '|' ||
       (xpath('/row/c/text()',
              query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                           false, true, '')))[1]::text
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind IN ('r', 'p')
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
   AND n.nspname NOT LIKE 'pg_toast%'
 ORDER BY 1;
SQL
}
