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

# Creates the pgBackRest bucket in the compose object store (SeaweedFS), once
# it is serving. pgBackRest does not create buckets itself. Idempotent.
#
#   DR_REPO_TYPE=s3 deploy/dr/s3-bucket.sh
#
# Not needed for an external S3 service, where the bucket is provisioned with
# that provider's own tooling.

set -euo pipefail
# shellcheck source=deploy/dr/lib.sh
. "$(dirname "$0")/lib.sh"

bucket="${DR_S3_BUCKET:-lms-dr}"
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{2,62}$ ]] || die "invalid bucket name $bucket"
deadline=$(( $(date +%s) + 180 ))
# The object store's own last words, for a failure report. Lines that could
# name a credential are left out.
objectstore_tail() {
    dc ps -a objectstore 2>&1 | tail -n +2
    dc logs --no-color --tail 25 objectstore 2>&1 | grep -viE 'key|secret|credential' || true
}
until dc logs objectstore 2>/dev/null | grep -q 'Start Seaweed S3 API Server'; do
    [ "$(date +%s)" -lt "$deadline" ] || die "object store did not start; last output:
$(objectstore_tail)"
    sleep 2
done
while :; do
    out="$(dc exec -T objectstore sh -c \
        "echo 's3.bucket.list' | timeout 30 weed shell -master=localhost:9333" 2>&1 || true)"
    if grep -qw "$bucket" <<<"$out"; then
        log "bucket $bucket exists"
        exit 0
    fi
    out="$(dc exec -T objectstore sh -c \
        "echo 's3.bucket.create -name $bucket' | timeout 30 weed shell -master=localhost:9333" 2>&1 || true)"
    if grep -q "created bucket $bucket" <<<"$out"; then
        log "created bucket $bucket"
        exit 0
    fi
    [ "$(date +%s)" -lt "$deadline" ] || die "could not create bucket $bucket: $(tail -1 <<<"$out")"
    sleep 3
done
