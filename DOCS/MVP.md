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

A Spring Boot 4.1 modular monolith on Java 25 (thirteen bounded contexts and a
shared kernel; see `AGENTS.md` section 4), compiled to a GraalVM native image,
deployable to Render's free tier. The REST API is contract-first: `api/openapi.yaml`
is the head and the paths and schemas live in `api/spec/`, spliced together at
build time.

### One measured deployment check

This is a **point-in-time local measurement**, not a production figure and not
a benchmark. On 2026-10-21 the container image built from `main` at `9649b05`
(every feature on `main` at the time, all 28 migrations) was run with
`--memory=512m --cpus=0.1` (Render's free web service limits) against a fresh
PostgreSQL, using the blueprint's environment:

| Measurement | Value |
|---|---|
| Image size | 123.5 MB |
| Ready from an empty database, including all 28 migrations and the demo seed | 22 s |
| Resident memory, idle | ~87 MiB of 512 MiB |
| `deploy/smoke.sh` | all checks green |
| 240 signed-in list requests, 10 concurrent, across twelve endpoints including the part-load, rail and access-policy ones | all 200 OK; p50 194 ms, p95 492 ms |
| Resident memory after that load | ~90 MiB |
| Out-of-memory kills | none |
| Database size afterwards | 12 MB |

An earlier run of the same check on `c7fe1d9` (21 migrations) gave 15 s to
ready, ~82 MiB idle and a p95 of 663 ms; the difference is the seven further
migrations and the heavier endpoint mix, not a regression to chase.

Nothing else in this document is a timing or memory figure. Earlier versions
of this file quoted a 0.643 s native start, a 146 MB image and JVM comparisons;
those were not backed by anything the repository can reproduce and have been
removed. The instance still sleeps after 15 minutes of inactivity, so the first
request after a quiet spell pays the start-up cost above.

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

**Sourcing (vision §3.5)**
- Contractual routing guide per lane and vehicle type, walked in rank order,
  cascading on rejection *or* on a response SLA that lapses. Round-robin fair
  share over a calendar month is a second implementation of the same strategy
  interface, shaped so the deferred reverse auction is a new bean rather than a
  change to any caller.
- An exhausted cascade is **terminal and needs a human**. A load nobody will
  take is a commercial problem, and re-offering it automatically would spin
  against unwilling vendors while the freight sits.
- Vendor eligibility is checked at offer time, not trusted from when the guide
  was written: a guide is a standing arrangement and outlives the standing of
  the vendors in it.

**Trip execution (vision §3.6)**
- Nine-state trip machine (the eight of §3.6.1 plus CANCELLED) as an explicit
  transition table. A dispatched trip cannot be cancelled — bringing freight
  back is a new movement, not a status change.
- Gate-in/gate-out log, weighbridge tare and gross with the payload computed and
  **stored**, and the origin dwell that detention will be billed on.
- Dispatch is gated on four things at once, reported together: the trip's own
  statutory documents, the vehicle's and driver's documents *against the
  departure date*, hazmat certification when the load carries dangerous goods,
  and the weighed payload against the manifest within tolerance.

**Telematics (vision §3.7)**
- Batched ingest with a sanity filter: a point implying an impossible speed, or
  a lost fix reading as null island, is rejected without costing the other
  points in the same flush. Points are processed in *device* order, not arrival
  order, so a buffered flush cannot make the jump filter compare against the
  future.
- Geofence crossings drive the trip machine. Leaving the origin puts a trip in
  transit; entering the destination marks it arrived. A vehicle sitting on a
  boundary generates one crossing, not one per ping, because presence is a set
  comparison rather than a per-point test.
- Route deviation against the planned corridor, opened once and closed on
  return. Retention collapses a finished trip's raw points into a simplified
  polyline — what makes a 1 GB database survive a fleet.

**Rating and settlement (vision §3.9)**
- Rate cards are **versions**, not rows to edit. A trip is priced against the
  card in force on the day it was *dispatched*, and the card used is recorded on
  the bill — so a dispute raised in March is argued from January's card rather
  than from today's numbers. The immutability is a database trigger, not a
  service rule: a rule only in application code is one a migration or a console
  session walks straight past.
- Accessorials on top of the slab linehaul: detention on the dwell *beyond* the
  free allowance, a fee per drop after the first, and a fuel surcharge applied
  to the linehaul alone — detention is a lorry standing still and burns no
  diesel.
- Every bill carries its arithmetic line by line. A total with no breakdown is
  unarguable, and the total is always the sum of the lines shown beneath it.
- Tolerance matching is a percentage **and** an absolute floor, whichever is
  greater. The percentage alone disputes a rounding difference on a small bill;
  the absolute alone waves through a five-figure gap on a large one. An
  underclaim beyond tolerance is disputed too — it is not free money, it means
  the two sides' understanding of the rate has diverged.
- A lane with no card produces an UNPRICED bill that says so, rather than
  silence. A trip with no bill looks exactly like one nobody has got to yet.

**Exception and control tower (vision §3.8)**
- Tickets against a trip walk RAISED, ASSIGNED, UNDER_INVESTIGATION, RESOLVED,
  CLOSED as an explicit transition table. Severity is fixed by the category
  (accident, theft and breakdown are P1; route deviation, extended stoppage and
  temperature excursion P2; ETA delay and document mismatch P3), never picked.
- The vehicle's position is **captured at raising**: its own telematics fix if
  under thirty minutes old, else the reporter's coordinates, else the stale fix
  with its true timestamp, else an explicit NONE. A ticket never implies it
  knows more than it does.
- Detectors raise tickets with nobody pressing anything, idempotently on the
  detection -- the outbox delivers at least once: route deviation (P2) from
  telematics, missed rail connections from multimodal, skipped and late
  milk-run stops, warehouse receipt variances and pick shortfalls. A ticket
  raised from the last four may concern an itinerary, a circuit, an ASN or an
  outbound order rather than a trip; one with no trip never holds settlement.
- **P1 and P2 tickets freeze settlement** of the trip: finance refuses to match
  or settle its bill until a manager closes the ticket. The hold is finance's
  own table, fed by events, with commuting upserts so a close that overtakes
  its raise after a restart still releases the money. Two critical tickets are
  two holds.
- Closing a critical ticket is refused to whoever resolved it. Closing is what
  releases a vendor's money, and a release one person can both justify and
  approve is not a control.

**Access policies (vision §3.1)** — attribute-based access control on RBAC
- A permission says what kind of action; a policy says how far. Policies are
  per-tenant rows, decided by the `@abac` bean from `@PreAuthorize`, read
  against attributes carried in the caller's token and the record's own
  (amount, owning unit, vendor, partner, freight mode).
- Every tenant starts with default policies (invoice approval limit, closing a
  money-holding exception, organisational-subtree writes for partners, vehicles,
  orders, loads, trips, drivers, terminals, routing guides, milk-run planning,
  warehouse setup, client contracts, rail
  tariffs, inventory adjustment, and for client billing: running the billing
  run, recording usage, computing storage and issuing credit notes, each limited
  to the caller's own organisational unit and below). They bite only on an attribute a user
  actually carries, so a user with none keeps what their permissions allowed.

**Freight modes: FTL and LTL (vision §3.3, §3.4)**
- An order is full truckload or part load. A part-load order names its origin
  and destination hubs, and both must really be HUB terminals.
  Its consignments are dockets in their own `DKT-` series; full truckloads keep
  `LR-` lorry receipts. RAIL is not bookable as a plain order mode.
- An LTL load consolidates dockets from many customers gathered at one origin
  hub and bound for one destination hub; capacity, dangerous goods and
  compatibility rules apply as for any load. A docket then travels twice: line
  haul hub to hub, then a delivery load from the destination hub.
- Rating: terminals belong to zones, rate cards are versioned zone-to-zone and
  immutable, and each docket gets its own receivable from its own chargeable
  weight (per-kg rate, minimum weight, cubic-foot volumetric weight, docket
  charge, out-of-delivery-area surcharge). The carrier's freight bill for the
  vehicle is a separate document.

**Milk runs (vision §3.4)**
- Versioned routes: an ordered list of supplier pickups, each with a window
  measured from departure, ending at a plant, with a schedule and a price (a
  fixed rate per run plus a fee per supplier visited).
- Circuits are generated ahead in-process, per tenant, at startup and on a
  fixed delay, idempotent per route and day. A circuit is carried by a trip;
  geofence crossings record each stop's arrival and departure, with manual
  recording when the tracker is silent. Skipped stops are not paid.
- The priced, completed circuit becomes the carrier's payable in finance.

**Rail and multimodal itineraries (vision §3.4)**
- An itinerary is ordered legs: ROAD legs are execution trips the module
  follows but never drives; RAIL legs are bookings that move by reported
  terminal milestones (placed, loaded, departed, arrived, released), since rail
  is not tracked by GPS.
- Rail runs between two RAILHEAD terminals. Rail carriers publish versioned
  rate cards per railhead pair; a rail leg planned without a cost is priced from
  the card in force on its planned departure day. The itinerary reports its
  cost by carrier.
- The hand-off rule: a leg cannot start before its predecessor has arrived and
  the transfer dwell has passed. A rail departure that breaks it is refused; a
  road dispatch cannot be refused from here, so it is recorded as an early
  start. A predecessor that arrives too late, or never, makes a missed
  connection (an in-process sweep catches the second case), which the control
  tower turns into a ticket.

**Warehousing**
- Terminals of category WAREHOUSE gain bins of fixed capacity and SKUs. Goods
  arrive on an ASN and are counted at the dock, recording short, excess and
  damaged quantities; they are put away to bins, picked against outbound orders
  (singly or in a wave), packed into handling units and dispatched.
- Inventory is an append-only ledger of stock movements; quantity at a location
  is derived, never edited, and the database refuses negative stock, an overfull
  bin and any edit of a movement. A correction is a new adjustment.
- A pick that cannot be filled stays owed as a back-order, filled by a later
  pick into a further handling unit, or is cancelled (`cancel-back-order`),
  which closes the order as BACK_ORDER_CANCELLED. Waves are listed per
  warehouse and status (`GET /api/v1/pick-waves`). Damaged stock sits in
  quarantine until an
  adjustment releases or writes it off. A cross-docked receipt skips the bins.
- A dispatched handling unit with a destination, consignee and weighed gross
  becomes a full-truckload consignment in planning, ready to go on a load.

**Third-party logistics client billing**
- A client is a CUSTOMER partner holding a versioned, immutable client
  contract. Completed full-truckload trips become transport charges for each
  contracted client aboard (part-load dockets are billed as docket receivables
  instead, never twice); warehousing arrives as idempotent service usage
  (storage pallet-days, handling in and out, value-added services).
- A month is invoiced once per client however often the run is repeated, a
  billed month is closed (late charges go on the next invoice), and an invoice
  carries a line per charge that adds up. Each client sees only its own
  charges and invoices; margins (client charge against the carrier's bill) are
  for staff.
- Tax: a contract version carries a transport tax rate and a warehouse tax rate
  (percent). Each invoice line stores its taxable amount, rate and tax, rounded
  half-up to two decimals per line; the invoice total is taxable plus tax.
- Credit notes: an issued invoice is never edited. A credit note against it is
  either one pre-tax amount, spread over the lines in proportion to what remains
  creditable on each, or explicit per-line amounts. Tax is reversed at each
  line's rate, a line can never be credited beyond what it charged (the service
  checks and a database trigger holds the limit), and issuing needs its own
  permission and is audited.
- Storage from the ledger: instead of reported pallet-days, the units of the
  client's own SKUs held at the end of each day are read from the warehouse
  ledger (quarantined stock counts as held), converted to pallets with the
  contract's units-per-pallet, rounded up per day, and recorded once per client
  and day so the computation is idempotent. `storage-computations` runs it and
  `storage` reads the days.
- Statement: `GET /api/v1/client-billing/clients/{clientId}/statement` lists the
  client's invoices (debit), credit notes and payments (credit) oldest first
  with a running balance over the whole history; a negative balance is a client
  in credit.
- Payments: `POST /api/v1/client-billing/clients/{clientId}/payments` records
  money received (amount, the day it arrived, the client's remittance
  reference). It is held against the client, not an invoice, so no receipt is
  matched to the invoices it settles; the reference is unique per client, so a
  receipt entered twice is refused. Needs `CLIENT_BILLING_RUN` in the caller's
  subtree, is audited, never edited or removed.

**Geospatial (vision §3.2.3, §3.7.2)** — `com.lms.shared.geo`, no PostGIS
- Ray-casting point-in-polygon, haversine distance and bearing, polygon overlap
  (crossing *and* containment), point-to-polyline distance for route deviation,
  bounding boxes as an index substitute.
- Unit tests covering the cases that actually bite: vertex-on-ray,
  point-on-edge, concave polygons, antipodal points, degenerate segments,
  self-intersecting rings, lon/lat order.

## Verified, not assumed

`./mvnw clean verify` runs **681 tests** (the count Surefire reported on
2026-10-21), all green. They include **305 acceptance scenarios over 7,571 steps**, which call
each context's services in-process, and **8 end-to-end scenarios over 102
steps**, which go over HTTP through the client generated from the contract.
Both run against real PostgreSQL via Testcontainers, never
H2, which has no row-level security and so cannot test the property most worth
testing.

`spine.feature` is the one that matters most: a single scenario walking an
order through validation, consignment generation, load building, a routing
guide whose first-ranked vendor declines, the compliance gate refusing a
dispatch and then permitting it, three GPS coordinates moving the trip with
nobody touching it, and a freight bill priced against the rate card in force
on the dispatch date and matched against the vendor's invoice. It is composed
entirely from the steps the individual features already use -- a walk needing
its own vocabulary would be exercising a parallel implementation rather than
the one those features describe.

The end-to-end suite (`src/test/resources/e2e/`) signs in as the seeded demo
administrator and uses nothing but the generated client: the original order to
paid-bill walk and a theft-hold scenario, plus one scenario each for a
part-load docket priced by zone (LTL), a milk-run circuit billed to its
carrier, a road-rail-road itinerary costed by carrier, a warehouse receipt with
a variance handed off to planning, and a 3PL client's taxed monthly invoice, a credit note against it and the
client statement.

The Cucumber suite (`src/test/resources/features/`) is the readable
specification. Its most important scenario runs a query with **no tenant
predicate at all** and asserts the result is still confined to one tenant, plus
its companion asserting that an *unscoped* query returns nothing rather than
everything. Tenant isolation is demonstrated, not configured and hoped for.

That suite has already earned its keep. It caught six real defects that all
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
5. **Every scheduled job was a silent no-op.** A scheduled thread carries no
   tenant scope, so under row-level security `tenant_id = app_current_tenant()`
   evaluates against NULL — which is NULL, not true. The job read an empty
   table and reported success. The idempotency prune had done nothing since the
   day it was written. Jobs now iterate tenants and work *scoped*, the same way
   request handling does, rather than bypassing RLS; `sourcing.feature` proves
   it by clearing the scope before invoking the real sweeper.
6. **The audit trail could not write a snapshot.** `AuditTrail` binds through
   `JdbcClient`, which is plain JDBC and does not consult Spring Data's
   converters — so a `Json` value reached the driver as an unknown type and the
   insert failed. Nothing noticed for as long as every caller passed nulls; the
   first before-and-after snapshot, written by rate-card publishing, broke the
   Background of every finance scenario at once. The values are now bound as
   text and cast in SQL.

## Not built yet

Deferred, with interfaces left open. Do not read the vision document as a
description of current behaviour:

The Angular console and its Playwright suite · reverse auction · OCR · ERP
sync · SAML/OIDC and MFA · TSP route sequencing · automatic multimodal route
search (a person assembles an itinerary's legs) · notifications · i18n ·
cold-storage archival.

Left open by the freight-mode, warehouse and billing work, on purpose:

- Rail carriers are not settled through finance freight bills.
- A warehouse hand-off consignment has no sales order, so client billing skips it.
- A payment is recorded against the client, not matched to invoices, so there
  is no per-invoice "paid" or "overdue" state and no ageing report.
- Of the control-tower detectors, stoppage, ETA and temperature are not built.

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
cd backend && ./mvnw clean verify
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
blueprint are ready, and the container was exercised under the free tier's
memory and CPU limits locally (see the measurement above), but creating the
service requires a Render account. If free instances turn out to be
build-from-repo only, the fallback is the `jvm` Dockerfile target, which
has not been measured here.

Also note: Render's free Postgres **expires 30 days after creation**.
Migrations and seed data are idempotent and run unattended on startup so that
re-provisioning is a one-command operation.
