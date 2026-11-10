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

# Entrypoint of the pgBackRest image. Renders /etc/pgbackrest/pgbackrest.conf
# from the mounted secrets, then hands over:
#
#   * `postgres ...`  -> the official image's docker-entrypoint.sh, unchanged;
#   * anything else   -> run as the postgres user (e.g. `pgbackrest restore`
#     into a fresh volume, which is how restore-pitr.sh uses this image).
#
# The configuration is written into the container's own filesystem, never into
# a volume, so the repository passphrase is not persisted next to the backups it
# protects. Without the passphrase secret no configuration is written at all;
# the image is then a plain PostgreSQL 17 (restore-logical.sh uses it so).
#
# Settings (environment):
#   DR_REPO_TYPE     posix (default: the pgbackrest-repo volume) or s3
#   DR_S3_ENDPOINT   default objectstore (the compose service)
#   DR_S3_PORT       default 8333
#   DR_S3_BUCKET     default lms-dr
#   DR_S3_REGION     default us-east-1
#   DR_S3_URI_STYLE  default path
#   DR_S3_CA_FILE    default /run/secrets/s3_tls_cert; empty = system CAs
# Secrets (files, never environment variables, so they stay out of `docker
# inspect`): /run/secrets/repo_cipher_pass, and for s3 also
# /run/secrets/s3_access_key and /run/secrets/s3_secret_key.

set -euo pipefail

SECRETS=/run/secrets
CONF=/etc/pgbackrest/pgbackrest.conf

secret() {
    tr -d '\r\n' < "$SECRETS/$1"
}

render_conf() {
    local repo_type="${DR_REPO_TYPE:-posix}"
    umask 027
    {
        echo "[global]"
        echo "repo1-cipher-type=aes-256-cbc"
        echo "repo1-cipher-pass=$(secret repo_cipher_pass)"
        # Two full backups; differentials for a week; WAL kept for as long as
        # any retained full backup needs it -- so PITR reaches back to the
        # older of the two fulls (one to two weeks on the weekly schedule).
        echo "repo1-retention-full-type=count"
        echo "repo1-retention-full=2"
        echo "repo1-retention-diff=7"
        echo "repo1-retention-archive-type=full"
        echo "repo1-retention-archive=2"
        # Bundling small files and block-level incrementals keep hourly
        # incrementals small.
        echo "repo1-bundle=y"
        echo "repo1-block=y"
        echo "compress-type=zst"
        echo "process-max=2"
        echo "start-fast=y"
        echo "log-level-console=info"
        echo "log-level-file=detail"
        case "$repo_type" in
            posix)
                echo "repo1-type=posix"
                echo "repo1-path=/var/lib/pgbackrest"
                ;;
            s3)
                echo "repo1-type=s3"
                echo "repo1-path=/lms"
                echo "repo1-s3-endpoint=${DR_S3_ENDPOINT:-objectstore}"
                echo "repo1-storage-port=${DR_S3_PORT:-8333}"
                echo "repo1-s3-bucket=${DR_S3_BUCKET:-lms-dr}"
                echo "repo1-s3-region=${DR_S3_REGION:-us-east-1}"
                echo "repo1-s3-uri-style=${DR_S3_URI_STYLE:-path}"
                echo "repo1-s3-key=$(secret s3_access_key)"
                echo "repo1-s3-key-secret=$(secret s3_secret_key)"
                local ca="${DR_S3_CA_FILE-$SECRETS/s3_tls_cert}"
                if [ -n "$ca" ]; then
                    # A certificate is public, but the mounted one keeps the
                    # host's owner and mode 600, which postgres may not be
                    # able to read. A readable copy sits beside the config.
                    install -o root -g postgres -m 0644 "$ca" "$(dirname "$CONF")/s3-ca.crt"
                    echo "repo1-storage-ca-file=$(dirname "$CONF")/s3-ca.crt"
                fi
                ;;
            *)
                echo "DR_REPO_TYPE must be posix or s3, not '$repo_type'" >&2
                exit 64
                ;;
        esac
        echo
        echo "[lms]"
        echo "pg1-path=${PGDATA:-/var/lib/postgresql/data}"
        echo "pg1-socket-path=/var/run/postgresql"
        # The bootstrap superuser of this stack is lms (POSTGRES_USER), and
        # there is no `postgres` role to fall back to.
        echo "pg1-user=${POSTGRES_USER:-lms}"
    } > "$CONF.tmp"
    chown root:postgres "$CONF.tmp"
    chmod 0640 "$CONF.tmp"
    mv "$CONF.tmp" "$CONF"
}

if [ "$(id -u)" = 0 ] && [ -r "$SECRETS/repo_cipher_pass" ]; then
    render_conf
fi

if [ "${1:-}" = postgres ] || [ "${1#-}" != "${1:-}" ]; then
    exec docker-entrypoint.sh "$@"
fi
if [ "$(id -u)" = 0 ]; then
    exec gosu postgres "$@"
fi
exec "$@"
