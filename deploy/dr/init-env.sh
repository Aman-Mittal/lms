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

# Generates the secrets the DR stack needs, once per machine, into
# DR_SECRETS_DIR (default deploy/dr/secrets, git-ignored). Existing files are
# kept: regenerating repo_cipher_pass would make every existing backup in the
# repository unreadable.
#
#   repo_cipher_pass     pgBackRest repository encryption passphrase
#   s3_access_key        object-store access key   } only used with
#   s3_secret_key        object-store secret key   } DR_REPO_TYPE=s3
#   s3_config.json       the object store's identity file
#   s3_tls.crt/.key      self-signed certificate for the object store
#
# Copy repo_cipher_pass somewhere safe and separate from the backups (a
# password manager): a repository without its passphrase is noise.

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

mkdir -p "$DR_SECRETS_DIR"
chmod 700 "$DR_SECRETS_DIR"
umask 077

random() {
    openssl rand -base64 "$1" | tr -dc 'A-Za-z0-9' | head -c "$2"
}

new() {
    local file="$DR_SECRETS_DIR/$1"
    if [ -s "$file" ]; then
        log "keeping existing $1"
        return 1
    fi
    log "generating $1"
}

if new repo_cipher_pass; then random 96 64 > "$DR_SECRETS_DIR/repo_cipher_pass"; fi
if new s3_access_key; then random 48 20 > "$DR_SECRETS_DIR/s3_access_key"; fi
if new s3_secret_key; then random 96 40 > "$DR_SECRETS_DIR/s3_secret_key"; fi

if new s3_config.json; then
    cat > "$DR_SECRETS_DIR/s3_config.json" <<JSON
{
  "identities": [
    {
      "name": "pgbackrest",
      "credentials": [
        {
          "accessKey": "$(cat "$DR_SECRETS_DIR/s3_access_key")",
          "secretKey": "$(cat "$DR_SECRETS_DIR/s3_secret_key")"
        }
      ],
      "actions": ["Admin", "Read", "Write", "List", "Tagging"]
    }
  ]
}
JSON
fi

if new s3_tls.crt; then
    openssl req -x509 -newkey rsa:2048 -nodes -days 825 \
        -subj "/CN=objectstore" -addext "subjectAltName=DNS:objectstore" \
        -keyout "$DR_SECRETS_DIR/s3_tls.key" -out "$DR_SECRETS_DIR/s3_tls.crt" 2>/dev/null
fi

# Compose mounts these into containers as bind mounts with host ownership; the
# containers read them as root. Nothing here is readable by other host users.
chmod 600 "$DR_SECRETS_DIR"/*
log "secrets are in $DR_SECRETS_DIR"
