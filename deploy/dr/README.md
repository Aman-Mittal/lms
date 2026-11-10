<!--
Copyright 2026 Aman Mittal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# Backup, point-in-time restore and disaster recovery

Runbook for the database. Everything here runs against the **DR stack** in
this directory -- PostgreSQL 17.7 with pgBackRest 2.60, archiving every WAL
segment -- or, for logical backups and tenant exports, against any database
you point it at. The Render deployment is different; read
[Render and managed databases](#render-and-managed-databases) first if that is
what you operate.

| Objective | Target | How it is met | Evidence |
|---|---|---|---|
| RPO (data you can lose) | **< 5 minutes** | Continuous WAL archiving with `archive_timeout=60s`: a partly filled segment is shipped within a minute even on a quiet database | The drill times an unforced write until it is archived (7-31 s in local runs); `walArchiving` health goes DOWN past `lms.dr.rpo` |
| RTO (time to restore) | **< 1 hour** | Restore = nearest backup (weekly full + daily diff + hourly incr) + at most an hour of WAL replay; the drill fails if any restore exceeds the budget | Each restore prints its duration; the drill asserts it (9-20 s for a 35 MB database in local runs) |
| Restore is real | periodic drill | `dr-drill.sh`, weekly in CI against both repository types | *DR drill* workflow log |

The drill database is small, so its timings say the procedure works, not how
long a production-sized restore takes. That scales with database size (file
copy) and with WAL since the backup (replay); re-measure with a copy of
realistic size before relying on the RTO figure.

## Files

| File | Purpose |
|---|---|
| `docker-compose.dr.yml` | The stack: `postgres` (archiving), `objectstore` (SeaweedFS, profile `s3`) |
| `Dockerfile`, `entrypoint.sh` | `postgres:17.7` + pinned pgBackRest; renders `pgbackrest.conf` from mounted secrets |
| `init-env.sh` | Generates the secrets once per machine into `secrets/` (git-ignored) |
| `backup.sh <full\|diff\|incr\|init>` | Physical backup; creates the stanza on first use |
| `crontab.example` | The schedule below, as cron lines |
| `restore-point.sh <name>` | Named restore point (before a migration, a bulk fix ...) |
| `restore-pitr.sh` | Point-in-time restore into a **new** container |
| `logical-backup.sh [dir]` | `pg_dump -Fc` + roles, sha256 files, retention |
| `restore-logical.sh <dump>` | Verify checksums, restore into a **new** container |
| `tenant-export.sh <uuid> <dir>` | One tenant's rows as CSV + manifest (offboarding, inspection) |
| `s3-bucket.sh` | Creates the bucket in the compose object store |
| `dr-drill.sh` | The restore drill |

## Setting up

```bash
bash deploy/dr/init-env.sh                                    # once per machine
docker compose -f deploy/dr/docker-compose.dr.yml up -d --build --wait
bash deploy/dr/backup.sh init                                 # stanza + archive check
bash deploy/dr/backup.sh full
```

The database listens on `127.0.0.1:5433` (`DR_PG_PORT`), trust-authenticated
like the development stack. To run the application against it, point
`DATABASE_URL` at `jdbc:postgresql://localhost:5433/lms`; Flyway migrates it.

**The repository passphrase** (`secrets/repo_cipher_pass`) encrypts every
backup (`repo1-cipher-type=aes-256-cbc`). Copy it somewhere safe and separate
from the backups, such as a password manager. Without it the repository
cannot be read; regenerating it does not help. `init-env.sh` never overwrites
an existing one. The secrets reach the containers as files under
`/run/secrets`, not environment variables, so they do not show in
`docker inspect`, and pgBackRest redacts them from its logs.

### S3-compatible repository

```bash
DR_REPO_TYPE=s3 docker compose -f deploy/dr/docker-compose.dr.yml --profile s3 up -d --build --wait
DR_REPO_TYPE=s3 bash deploy/dr/s3-bucket.sh
DR_REPO_TYPE=s3 bash deploy/dr/backup.sh init
```

Export `DR_REPO_TYPE=s3` for every script while using it. The bundled store is
SeaweedFS (Apache-2.0), serving TLS with the self-signed certificate from
`init-env.sh`; pgBackRest speaks S3 over TLS only. MinIO, the usual choice, is
not used: its images are no longer published, and its AGPL licence is outside
this project's licence policy. For a real off-site bucket, set `DR_S3_ENDPOINT`,
`DR_S3_PORT` (443), `DR_S3_BUCKET`, `DR_S3_REGION`, `DR_S3_URI_STYLE` (`host`
for AWS) and `DR_S3_CA_FILE=` (empty: system CAs) in the environment of the compose
command and of the scripts, and put that provider's keys in `secrets/s3_access_key` and `secrets/s3_secret_key`.
A repository on the same disk as the database protects against mistakes, not
against losing the machine: production needs the off-site one.

## Backups

| Form | What | When | Retention |
|---|---|---|---|
| WAL archive | Every WAL segment, pushed by `archive_command` | Continuously; at least every 60 s if anything was written | As long as a retained full needs it (`repo1-retention-archive=2`, type full) |
| Full | pgBackRest full | Weekly, Sunday 02:15 | 2 (`repo1-retention-full=2`) |
| Differential | Changes since the last full | Daily 02:15, Monday-Saturday | 7 (`repo1-retention-diff=7`) |
| Incremental | Changes since the last backup | Hourly at :45 | Until their full expires |
| Logical | `pg_dump -Fc` + `pg_dumpall --globals-only` | Daily 03:30 | 7 sets (`DR_LOGICAL_KEEP`) |

So point-in-time recovery reaches back to the older of the two retained fulls:
between one and two weeks. pgBackRest expires old backups and WAL itself after
each backup. The repository is compressed (zstd) and encrypted; block
incrementals (`repo1-block`) keep the hourly ones small.

The cron lines are in `crontab.example`; this project runs no scheduler of its
own (the free tier has none). Alert when a scheduled backup logs no "backup
finished" line, and on the health indicator below.

Logical backups complement the physical ones: they restore into any
PostgreSQL 17, survive a corrupted repository, and are what you hand to
another system. `pg_dumpall --globals-only` matters because every application
statement runs as `lms_app` (migration V3) and the dump's grants and policies
name it. Role **passwords are not dumped** (`--no-role-passwords`), so the file
is not a credential store; set them after a restore. Every artefact gets a
`.sha256` file beside it.

## Restoring

Every restore goes into a **new container and volume**. Nothing here restores
over a running database: decide to cut over only after the copy has been
checked.

### To a point in time

```bash
bash deploy/dr/restore-pitr.sh --target-time '2026-11-10 14:30:00+00'
```

Always give the time zone. pgBackRest picks the newest backup before the
target, restores it, and PostgreSQL replays WAL up to the last commit at or
before the target, then promotes. The script waits for that and prints the
duration and the port (`127.0.0.1`, random). Typical use: "a bad deploy wrote
rubbish at 14:31" -> restore to 14:30, check, then cut over or copy the good
rows back.

### To a restore point

Before anything risky -- a migration run by hand, a bulk correction:

```bash
bash deploy/dr/restore-point.sh before_v49
# ... later, if it went wrong:
bash deploy/dr/restore-pitr.sh --target-name before_v49
```

### To the latest state

After losing the primary (disk, host, volume deleted):

```bash
bash deploy/dr/restore-pitr.sh --latest
```

Replays every archived segment; data lost is what had not reached the archive,
which the RPO bounds. `--set <label>` (from `pgbackrest info`) restores from a
specific backup instead of the newest suitable one.

### Promoting a restored copy

The copy runs with `archive_mode=off`, so it can never write a second timeline
into the repository the old primary still uses. To make it the new primary:

1. Stop writers (scale the application to zero).
2. `docker compose -f deploy/dr/docker-compose.dr.yml down` -- **without**
   `-v` if the old volume may still be needed for forensics.
3. Copy the restored volume into the stack's `pgdata` volume, or point the
   stack at it, and start the stack; it archives again (new timeline).
4. `bash deploy/dr/backup.sh full` straight away: the first backup on the new
   timeline is the one future restores build on.
5. Point `DATABASE_URL` at it and start the application.

### From a logical backup

```bash
bash deploy/dr/restore-logical.sh deploy/dr/backups/logical/lms-20261110T033000Z.dump
```

Checks both sha256 files first and refuses a mismatch, then starts a fresh
PostgreSQL 17 container, creates the roles from the globals file (an "already
exists" for `lms` is expected), and `pg_restore --exit-on-error`s the dump.

### One tenant's data

```bash
bash deploy/dr/tenant-export.sh <tenant-uuid> deploy/dr/exports
```

For **tenant offboarding** (handing a customer their data) and **inspection**.
It is not a backup and cannot be restored: no schema, no sequences, no
cross-tenant references. It writes one CSV per table that has a `tenant_id`
column -- discovered from `information_schema`, so new tables are included
without editing anything -- plus `tenant`, a `manifest.csv` (table, rows,
sha256) and `export.txt`. The export runs as `lms_app` with `app.tenant_id`
set, exactly as the application does, so row-level security scopes it: a
mistake can only yield fewer rows, never another tenant's. All tables are read
in one `REPEATABLE READ` snapshot. To pull a tenant's state from a point in
the past, restore that point first and export from the copy.

`logical-backup.sh` and `tenant-export.sh` read the DR stack by default. With
`DR_SOURCE_URL` set to a libpq connection string they read that database
instead, through a throwaway `postgres:17.7` container that receives the
string through its environment, never its command line.

## The drill

```bash
bash deploy/dr/dr-drill.sh                    # local volume repository
DR_REPO_TYPE=s3 bash deploy/dr/dr-drill.sh    # S3 repository
```

About two minutes on a laptop. It uses its own compose project
(`lms-dr-drill`), its own generated secrets and a random local port, so it
cannot touch a running DR or development stack, and it refuses to start if
that project already exists. It removes every container and volume it created
on exit, failed or not.

It applies the schema by running every Flyway migration with `psql` in version
order. That is deliberately not the backend: the migrations are plain SQL, and
building the application only to create tables would put a JDK and a Maven
build in the drill's path. It then seeds two tenants, creates its own marker
table `dr_drill.dr_drill_marker` (in its own schema, created by the drill --
no migration, so the production schema is untouched), and checks:

- PITR to a time T between two batches: the first present, the second absent;
- the same by restore point, and the latest state with both;
- an unforced write reaches the archive within the RPO;
- logical dump and restore: equal row counts in every table; a corrupted dump
  is refused;
- tenant export: only that tenant's rows, manifest checksums correct;
- `pgbackrest check` and `pgbackrest verify`; no outstanding archive failure;
- every restore within the RTO (`DR_RTO_SECONDS`, default 3600).

The *DR drill* workflow runs it weekly, on demand, and on pull requests that
change this directory or the migrations, once per repository type. It uploads
the drill log only -- timings and assertions, never a backup or dump.

## Health indicator: `walArchiving`

The backend reports archiving through `/actuator/health` as component
`walArchiving` (`WalArchivingHealthIndicator`). It reads `pg_stat_archiver` and
`archive_mode`:

| State | Status |
|---|---|
| `archive_mode=off` (managed databases, Render) | **UNKNOWN**: archiving is provider-managed |
| Last archive attempt failed after the last success | **DOWN** |
| WAL written since the last archived segment, and that segment older than `lms.dr.rpo` (`LMS_DR_RPO`, default `5m`) | **DOWN** |
| Archiving on, nothing archived or failed yet (new server) | UNKNOWN |
| Otherwise, including an idle database with an old last archive | UP |

UNKNOWN does not change the aggregate status, so a managed database never
makes the service look unhealthy. The indicator is not in the readiness group:
an archiving fault shows on `/actuator/health` without taking the service out
of the load balancer. Details are hidden from callers (`show-details: never`)
and in any case hold only timings and counters, never a command, path or
repository location.

**What to alert on**

- `walArchiving` DOWN for more than 2 minutes. After a long idle period one
  write can look stale for up to `archive_timeout` (60 s) before its segment is
  shipped, so do not page on a single sample.
- A scheduled backup that did not log "backup finished".
- The weekly drill failing.
- `pgbackrest verify` reporting errors (weekly in `crontab.example`).

## Render and managed databases

The demo runs on Render's free plan against a managed PostgreSQL. **None of the
above applies there**: a managed database exposes no WAL archive to its
tenants, so pgBackRest cannot run against it, and the free plans have no
backups or point-in-time recovery at all. On that deployment:

- backups and PITR are whatever the database provider offers -- on Render,
  the paid PostgreSQL plans; elsewhere, that provider's equivalent. Choose a
  plan with PITR before holding real data;
- `walArchiving` reports **UNKNOWN** ("provider-managed"), by design;
- `logical-backup.sh` and `tenant-export.sh` work against it through
  `DR_SOURCE_URL`, run from an operator's machine. That is the only form of
  backup the free plan allows, and it is a manual one.

**Nothing in this repository copies production data into GitHub.** The
repository is public. The drill and its workflow use only data the drill
invents; no workflow connects to a deployed database; workflow artefacts are
drill logs. Never commit or upload a dump, an export or `secrets/` -- they are
git-ignored here for that reason.
