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

# PostgreSQL 17 with pgBackRest, for continuous WAL archiving and
# point-in-time recovery. Used by docker-compose.dr.yml and by the restore
# scripts, which start throwaway copies of the same image.
#
# Debian-based rather than the Alpine image deploy/docker-compose.yml uses:
# pgBackRest is packaged by the PostgreSQL apt repository the official Debian
# image already trusts, and is not packaged for Alpine. Both the server and
# pgBackRest are pinned, so a rebuild does not silently change either.

FROM postgres:17.7

ARG PGBACKREST_VERSION=2.60.0-1.pgdg13+1

RUN apt-get update \
    && apt-get install -y --no-install-recommends "pgbackrest=${PGBACKREST_VERSION}" \
    && rm -rf /var/lib/apt/lists/* \
    && install -d -o postgres -g postgres -m 0750 /var/lib/pgbackrest /var/log/pgbackrest /var/spool/pgbackrest \
    && install -d -o root -g postgres -m 0750 /etc/pgbackrest

COPY --chmod=0755 entrypoint.sh /usr/local/bin/dr-entrypoint.sh

ENTRYPOINT ["dr-entrypoint.sh"]
CMD ["postgres"]
