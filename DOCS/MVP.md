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

# MVP status

`README.md` is the vision document: twelve modules, an enterprise-scale
multi-modal logistics platform. This file records what is **actually built**,
what is **measured**, and what is **not there yet**. Nothing below is
aspirational — if it says it works, it was run.

## What runs today

A Spring Boot 4.1 modular monolith on Java 25, compiled to a GraalVM native
image, deployable to Render's free tier.

| Measurement | Value | How it was obtained |
|---|---|---|
| Native startup | **0.643 s** | `Started LmsApplication in …`, native binary, cold container |
| Resident memory (idle) | **70.65 MiB / 512 MiB (13.8%)** | `docker stats`, container capped at 512 MB |
| Container image | **146 MB** | distroless base + static binary |
| Native compile time | **~3 min** | GraalVM CE 25.2.4, 16 GB build host |
| JVM startup (comparison) | ~6 s | same code, `jvm` Dockerfile target |
| JVM resident memory | ~172 MiB | same code, JVM target |

The startup figure is what makes the free tier viable: the instance sleeps after
15 minutes of inactivity, and a sub-second wake is the difference between a
usable demo and a minute of staring at a loading page.

## Implemented

**Platform foundations**
- Modular monolith with **build-enforced boundaries** (Spring Modulith).
  A context reaching into a peer's internals fails the build.
- Transactional outbox via Modulith's JDBC event registry — durable async
  domain events, retry, and republication on restart, with no broker.
- Spring Data JDBC + Flyway. No JPA, no PostGIS, no Kafka, no Redis.

**Multi-tenancy (vision §2.1)** — three layers, and the third is verified
- `tenant_id` on every table, first in every index.
- `TenantContext` bound from the JWT by a servlet filter.
- **Postgres row-level security**, enforced via `SET LOCAL app.tenant_id` and
  `SET LOCAL ROLE lms_app` at transaction start.
- N-level org hierarchy (HQ → LOB → Region → Branch → Hub) with materialised
  paths and strictly top-down visibility.

**Identity and access (vision §3.1)**
- RS256 JWT access tokens; opaque, hashed, rotating refresh tokens.
- Tenant-scoped login; per-tenant unique emails.
- RBAC schema with a seeded permission vocabulary; append-only audit log table
  with before/after JSONB snapshots (enforced by database rules, not
  convention).

**Master data (vision §3.2)**
- Business partners with the full KYC lifecycle; BLACKLISTED is terminal, so
  reinstating a partner is a deliberate act with its own record rather than a
  status edit.
- Vehicles with payload and volume capacities and statutory documents; drivers
  with licence class, expiry and hours of service.
- **Dispatch readiness** as a port other contexts call: it answers against the
  date a trip would *depart*, not today, because a certificate valid now but
  lapsing before departure is not a valid certificate. Every blocking reason is
  returned at once.
- Terminals as geofences — polygon or point-radius — with overlap refused
  within a functional category and permitted across categories, since a
  warehouse inside a port is a normal arrangement.

**Demand and planning (vision §3.3, §3.4)**
- Chargeable weight as `max(dead weight, volumetric weight)`, with the divisor
  stored per line because it is a commercial term: changing a default must not
  retrospectively alter what an existing order was billed.
- Dangerous-goods validation in both directions — a regulated class without a
  UN number is an undeclared shipment; a UN number on ordinary cargo is a
  mis-keyed line. Every problem is reported at once.
- The material compatibility matrix, applied per grouping key rather than
  across the whole order: materials bound for different destinations will never
  share a vehicle, and refusing that order would invent a rule the business
  does not have.
- Consignment generation grouped by consignee and destination, **idempotent by
  construction** — only unplanned lines are drawn and each is marked as taken,
  so a retried request after a timeout issues no second lorry receipt.
- Load building with three hard refusals: capacity in both dimensions, the
  dangerous-goods certification of the individual vehicle, and compatibility
  against everything already on the lorry. The capacity rule is also a database
  CHECK constraint, so it holds whichever code path writes the row.

**Geospatial (vision §3.2.3, §3.7.2)** — `com.lms.shared.geo`, no PostGIS
- Ray-casting point-in-polygon, haversine distance and bearing, polygon overlap
  (crossing *and* containment), point-to-polyline distance for route deviation,
  bounding boxes as an index substitute.
- 49 unit tests covering the cases that actually bite: vertex-on-ray,
  point-on-edge, concave polygons, antipodal points, degenerate segments,
  self-intersecting rings, lon/lat order.

## Verified, not assumed

`./mvnw verify` runs **147 tests**, including **56 Cucumber scenarios over 704
steps**, all green, against real PostgreSQL via Testcontainers — never H2,
which has no row-level security and so cannot test the property most worth
testing.

The Cucumber suite (`src/test/resources/features/`) is the readable
specification. Its most important scenario runs a query with **no tenant
predicate at all** and asserts the result is still confined to one tenant, plus
its companion asserting that an *unscoped* query returns nothing rather than
everything. Tenant isolation is demonstrated, not configured and hoped for.

That suite has already earned its keep. It caught three real defects that all
passed compilation and looked correct:

1. **RLS was completely inert.** The `postgres` container makes `POSTGRES_USER` a
   superuser, and superusers bypass row-level security unconditionally — so
   every policy was decorative in development and in CI, exactly where a leak
   must be caught. Fixed with a restricted `lms_app` role.
2. **Forcing RLS on the `tenant` table broke all authentication**, because
   `FORCE` also applies to the owner that the login lookup runs as.
3. **`@Transactional` bound the tenant scope too late**, so every login failed
   with "invalid credentials" for reasons nowhere near the credentials.
4. **Order lines silently did not persist.** A record with a client-assigned
   `@Id` and no `@Version` looks like an existing row to Spring Data JDBC, so
   the insert became an UPDATE matching nothing — and reported success. It
   surfaced as "this order has no lines to validate", nowhere near the cause.
   This failure mode has now been hit three times, on three different
   aggregates, which is why `.github/scripts/check-conventions.sh` now fails
   the build on any `@Table` entity with an `@Id` and no `@Version`.

## Not built yet

Deferred, with interfaces left open. Do not read the vision document as a
description of current behaviour:

**The REST layer.** Only `/auth` exists. Every other context is driven through
its command and query services by the Cucumber suite; `api/openapi.yaml` and
the controllers it describes are written together, not retrofitted, so neither
exists yet.

sourcing and load allocation · trip execution and gate operations · telematics
ingest · rating and invoicing · the Angular console and its Playwright suite ·
reverse auction · OCR · ERP sync · SAML/OIDC and MFA · multi-leg planning ·
control tower · notifications · i18n.

**Throughput.** Vision §5.1 targets 20,000 GPS pings/second. That is a
production NFR. This deployment is a single 0.1-CPU instance and will sustain
roughly 50–100/second. The gap is architectural, not a tuning matter.

## Running it

```bash
# Whole stack, JVM mode (fast rebuilds)
docker compose -f deploy/docker-compose.yml up

# The artefact that actually ships
docker compose -f deploy/docker-compose.yml --profile native up

# Tests
cd backend && ./mvnw verify
```

Requires Java 25 (the native toolchain floor, not a preference) and Docker.
See `AGENTS.md` for the traps — several cost real time and are documented so
they cost nobody else any.

## Deploying

CI builds the native image on a GitHub Actions runner and publishes it to GHCR
as a **public** package; Render pulls it with no registry credential at all.
`deploy/render.yaml` is the blueprint.

**One step remains unverified**: Render's documentation does not explicitly
confirm image-backed deploys on the *free* instance type. The image and
blueprint are ready; creating the service requires a Render account. If free
instances turn out to be build-from-repo only, the fallback is the `jvm`
Dockerfile target, at roughly 6 s cold start instead of 0.6 s.

Also note: Render's free Postgres **expires 30 days after creation**.
Migrations and seed data are idempotent and run unattended on startup so that
re-provisioning is a one-command operation.
