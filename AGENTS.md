# AGENTS.md — working rules for this repository

This file is the contract for anyone (human or agent) writing code here. Read it
before touching the codebase.

---

## 1. What this project is

An MVP of the platform described in `README.md`. The vision document specifies
twelve modules; the MVP implements a **thin end-to-end spine** through eight of
them. It is not a prototype — every path in scope is real, tested, and walkable.
It is also not complete — see "Deliberately deferred" below and do not pretend
otherwise in docs, comments, or commit messages.

The hard constraint behind nearly every design decision: the backend runs on a
**Render free web service — 512 MB RAM, 0.1 CPU**, backed by a **1 GB Postgres
that expires 30 days after creation**, with **no background workers or cron
jobs available**. If a change increases baseline memory or requires new
infrastructure, it needs an ADR.

---

## 2. Toolchain

| Tool | Version | Notes |
|---|---|---|
| Java | **25 LTS** | Required, not preferred — see the note below. |
| Maven | via `./mvnw` | Wrapper is script-only — there is no committed jar. Never invoke a global `mvn`. |
| Spring Boot | **4.1.0** | |
| Spring Modulith | **2.1.0** | |
| Node | 24.x | Frontend only. |

Always run Maven through `backend/mvnw`.

---

## 3. Traps specific to this stack

These have already cost time. Do not rediscover them.

- **Java 25 is required by the native toolchain, not chosen for its features.**
  Spring Boot 4.1 pulls `native-maven-plugin` 1.1.x, which resolves GraalVM
  reachability metadata in a schema that the **Java 21 GraalVM builds cannot
  read**. Building on 21 fails with "provides a reachability-metadata schema,
  but your GraalVM installation does not" — a message that sounds like a
  corrupt cache and is actually a version floor. The JVM build works fine on
  21; only the native build breaks, so this fails late.
- **Jackson 3, not Jackson 2.** Spring Boot 4 ships Jackson `3.1.4`. Databind and
  core live under **`tools.jackson.*`**, *not* `com.fasterxml.jackson.databind`.
  Annotations are the exception — they remain `com.fasterxml.jackson.annotation`.
  Importing the `com.fasterxml` databind packages will not compile.
- **Testcontainers 2.x renamed its modules.** The artifacts are
  `testcontainers-postgresql` and `testcontainers-junit-jupiter`, not
  `postgresql` / `junit-jupiter`.
- **Spring Boot 4 split auto-configuration into per-technology modules.**
  Putting a library on the classpath is no longer enough to auto-configure it —
  you need the matching `spring-boot-starter-*`. This bites hardest with
  Flyway: `flyway-core` alone compiles, starts, and **silently never runs your
  migrations**, and the first query then fails on a missing table with an error
  that points nowhere near the cause. Use `spring-boot-starter-flyway`. Assume
  the same applies to any other technology added later.
- **Spring Security 7 removed the non-lambda DSL.** Configure `SecurityFilterChain`
  with the lambda form only.
- **Build the native image with `package`, never the bare `native:compile`
  goal.** Invoking the goal directly bypasses the Maven lifecycle, so resources
  are never copied to `target/classes`.
- **AOT processing does not bind `@ConfigurationProperties`, and this is the
  single biggest native-image trap in this project.** Spring Boot's AOT step
  genuinely starts the application context at build time, but the bean
  post-processor that binds `@ConfigurationProperties` never runs. Any bean
  whose construction depends on bound properties gets nulls. Two consequences
  are already handled, and both must stay handled:
  - `DataSourceConfig` builds the Hikari pool by reading the `Environment`
    directly. Left to `spring.datasource.*` binding, the build fails with
    "Failed to determine a suitable driver class" — an error naming the driver
    when nothing was bound at all. Passing the values as system properties or
    env vars does **not** help; the values are present, the binding is missing.
  - `PersistenceConfig.lmsJdbcDialect()` pins the dialect as a bean.
    `spring.data.jdbc.dialect` expresses the same thing but is bound, so it is
    ignored during AOT and Spring Data falls back to detecting the dialect over
    a live connection — failing with "Failed to obtain JDBC Connection".
  - The pool is created with `new HikariDataSource()` and setters, never
    `new HikariDataSource(config)`, because the latter opens the pool eagerly
    and would try to connect during the build.
- **AOT freezes condition evaluation — never disable a feature "just for the
  build".** Spring AOT evaluates `@Conditional` at build time and bakes the
  result into the generated context; the native binary cannot re-evaluate it.
  Passing `-Dspring.flyway.enabled=false` to make the AOT step pass does not
  skip Flyway during the build, it **removes Flyway from the image forever**.
  The binary then starts fine against an already-migrated database and fails
  only against a fresh one, with `relation "event_publication" does not exist`.
  If AOT trips over a bean, fix the bean (make it lazy, pin its config) rather
  than conditioning it away.
- **Verify native builds with no database running.** If Postgres happens to be
  up on `localhost:5432`, dialect detection silently succeeds and the local AOT
  run passes while CI fails. `docker compose ... down` first, then
  `./mvnw clean process-classes spring-boot:process-aot -DskipTests` is a fast,
  honest check that does not need a full 3-minute native compile.
- **The native binary must be built with `--static-nolibc`.** A default native
  image links `libz.so.1` dynamically, and the distroless runtime base does not
  ship zlib. The container then exits immediately with
  `error while loading shared libraries: libz.so.1` — which names the loader,
  not the build flag, and looks like a broken image rather than a link option.
  Full `--static` is not used because it additionally requires a musl toolchain.
- **Every aggregate root needs `@Version`, for correctness before concurrency.**
  Spring Data JDBC chooses INSERT or UPDATE by asking whether the aggregate is
  new, and a record with a client-assigned `@Id` looks *not* new — so `save()`
  issues an UPDATE that matches no rows and **persists nothing, silently, with
  no error**. A `@Version Long` field (null ⇒ new) fixes it and brings
  optimistic locking along. Factory methods must pass `null` for it.
- **A Cucumber step expression may carry only one keyword annotation.**
  Given/When/Then are interchangeable at match time, so putting `@When` and
  `@Given` with the same text on one method registers a duplicate expression.
  That aborts registration of the rest of the class, and the symptom is later
  steps in the *same file* reporting as "undefined" while earlier ones work.
- **Bind parameters used only in `IS NULL` need an explicit cast.** Postgres has
  nothing to infer a type from and fails with "could not determine data type of
  parameter". Write `CAST(:id AS uuid) IS NULL`, not `:id IS NULL`.
- **No Lombok.** Use Java records and Spring Data JDBC constructor binding.
- **No `springdoc-openapi`.** `api/openapi.yaml` is hand-authored and is the
  contract source of truth. Do not generate the spec from annotations; generate
  the *client* from the spec.

---

## 4. Architecture rules

### Module boundaries are enforced by the build

Each bounded context is a top-level package under `com.lms`:

```
identity  masterdata  order  planning  sourcing  execution  telematics  finance  shared
```

- A context **must not** call another context's classes directly. Cross-context
  communication is `ApplicationEventPublisher` and domain events only.
- `ModularityTests` fails the build on any violation. If you find yourself
  wanting to relax it, you want an event instead.

**Every module carries a `package-info.java`** declaring
`@ApplicationModule(displayName = …, allowedDependencies = {…})`. Dependencies
are declared explicitly and narrowly — an empty `allowedDependencies` means the
module may talk to nothing but `shared`. Widening it is a design decision, so it
should be visible in a diff.

### Module layout: CQRS

Every module uses the same internal shape. Commands and queries are separated
because they have genuinely different needs: the write side must protect
invariants through aggregates, while the read side usually spans several of them
and wants exactly the columns a screen needs.

```
com.lms.<module>
├── package-info.java        @ApplicationModule — declares allowed dependencies
├── events/                  @NamedInterface("events") — the module's ONLY
│   └── …                    published surface; records, no behaviour
├── command/                 write side
│   ├── …Command.java        intent, as a record
│   ├── …CommandService.java loads an aggregate, enforces rules, saves, publishes
│   ├── domain/              aggregates and value objects
│   └── …Repository.java     Spring Data JDBC, aggregate-scoped
├── query/                   read side
│   ├── …View.java           flat projections shaped for a caller
│   └── …QueryService.java   hand-written SQL via JdbcClient
└── web/                     REST adapters, one per side
```

Rules that make the split worth having:

- **Queries never load aggregates and never write.** `query/` uses `JdbcClient`
  with explicit SQL and returns `…View` records. It does not touch repositories.
  This is what makes multi-aggregate reads cheap under Spring Data JDBC, which
  has no lazy loading by design (DOCS/adr/0002).
- **Commands never return view models.** A command handler returns an identifier
  or nothing. If a caller needs data back, it issues a query.
- **Only `events/` is importable by other modules.** It is a Modulith
  `@NamedInterface`, so peers declare `allowedDependencies = "masterdata::events"`
  and get the events without gaining access to `command`, `query` or `domain`.
  Everything else is internal and the build enforces that.
- **Events are past-tense facts** carrying identifiers and values, never
  aggregates or entities. A shared mutable object graph across a boundary
  defeats the point of having one.

### Idempotency is required on every state-changing endpoint

Retries are normal here, not exceptional: the free instance sleeps after 15
minutes and clients retry the request that wakes it, telematics ingest runs
over unreliable mobile links, and the Modulith event registry delivers **at
least once** and republishes incomplete events after a restart.

- Command endpoints accept an `Idempotency-Key` header and go through
  `IdempotencyService`. The claim is an `INSERT`, not check-then-insert — the
  primary key settles the race between concurrent retries instead of leaving it
  to chance.
- A retry receives **the original response**, not a 409. The client is retrying
  precisely because it never learned the first outcome.
- Reusing a key with a different body is rejected: that is a client defect, and
  replaying an unrelated response would hide it.
- A failed request **releases** its claim, or the key is poisoned and the client
  can never succeed.
- **Every event listener must be safe to run twice.** At-least-once delivery
  means this is a correctness requirement, not a nicety. Make the handler's
  effect naturally idempotent (upsert, state-machine guard) rather than relying
  on delivery counts.

### Queries must be indexed, paginated and bounded

On 0.1 CPU there is no headroom to absorb a bad query plan.

- **`tenant_id` comes first in every index**, matching how row-level security
  and every query filter.
- **Keyset pagination, never `OFFSET`.** Use `Slice` in `shared.query`.
  `OFFSET n` makes the database walk and discard n rows, so page 500 costs 500
  times page 1, and concurrent inserts shift every subsequent offset so rows are
  silently skipped or repeated. Cursors do neither. The trade — no total count,
  no jump to page N — is accepted deliberately.
- **Every list endpoint is bounded.** `Slice.MAX_LIMIT` caps what a client can
  ask for regardless of what it requests.
- **New queries need a supporting index**, and `QueryPlanTest` asserts it: it
  runs `EXPLAIN` and fails on a sequential scan over a tenant-scoped table. A
  plan that is fine against ten test rows and catastrophic against a million is
  otherwise invisible until production.
- Sort keys must be unique and stable, or paired with the primary key to break
  ties — a cursor on a non-unique column loses or repeats rows.

### Correlation ids and structured logs

Vision document 4.4. `CorrelationIdFilter` runs at highest precedence, ahead of
security, so that rejected requests are still traceable — those are the ones
someone will go looking for.

- An inbound `X-Correlation-Id` is honoured but **validated** before it reaches
  a log line or the audit trail; an unvalidated header there is log injection
  and forged audit entries. Malformed values are replaced, never rejected —
  observability must not be able to fail a request.
- The id is echoed on the response, put in the MDC alongside `traceId`/`spanId`,
  and recorded on every `audit_log` row.
- Logs are **structured JSON on stdout** in deployed environments
  (`LOG_FORMAT=ecs`), using Boot 4's built-in support — no
  `logstash-logback-encoder` to license-audit or configure for native image.
  Left plain in development, where readable beats parseable.

### There is no message broker

Spring Modulith's JDBC event publication registry is the transactional outbox.
It gives at-least-once delivery, retry, and republication of incomplete events
on restart. Do not add Kafka, RabbitMQ, or Redis — they do not fit the memory
budget and the registry covers the MVP's needs.

### There is no PostGIS

PostGIS is GPLv2 and would compromise the Apache-2.0 distribution (see
`DOCS/adr/0004`). Geometry is plain Java in `com.lms.shared.geo.GeoUtils`, with
polygons stored as JSONB alongside precomputed bounding-box columns for index-
assisted candidate filtering. Add geometry operations there, with unit tests.

### Every periodic job runs in-process

Render's free tier has no workers or cron. Scheduled work uses `@Scheduled` in
the web service. **The free instance sleeps after 15 minutes of inactivity**, so
a wall-clock schedule is not reliable — any job that must not be skipped also
has to run once at startup.

### Multi-tenancy is defence in depth

1. `tenant_id UUID NOT NULL` on every business table, first in every index.
2. `TenantContext` populated from the JWT by a servlet filter.
3. Postgres **row-level security** as the fail-safe, driven by
   `SET LOCAL app.tenant_id` issued at transaction start.

Never write a query that relies solely on layer 2. A missing `WHERE` clause must
degrade to *no rows*, never to another tenant's rows.

**Bind the tenant scope BEFORE the transaction opens.** This is the single
easiest way to break the platform, and the symptom never points at the cause.
`TenantAwareTransactionManager` publishes the scope onto the connection *while
starting the transaction*, so this is wrong:

```java
@Transactional                                   // transaction starts here, scope is empty
public Result doWork() {
    return TenantContext.callWith(id, path, ...); // too late; connection already scoped to ""
}
```

and this is right:

```java
TenantContext.callWith(id, path, () ->           // scope first
    transactions.execute(status -> ...));        // transaction second
```

Written the wrong way round, every query silently matches nothing. When this
happened in `AuthService`, the visible symptom was "invalid tenant, email or
password" on a perfectly valid login. Where a method must establish its own
scope, use an injected `TransactionTemplate` rather than `@Transactional` —
the ordering then can't be got wrong by accident, and it sidesteps
self-invocation proxy problems too. The same rule applies in tests; see
`TestFixtures`.

**Two Postgres exemptions defeat RLS, and both are silent.**
- *Owners* are exempt unless the table is `FORCE`d. V2 forces every tenant-scoped
  table. The one exception is `tenant` itself, deliberately left un-forced so the
  `SECURITY DEFINER` login lookup can still read it — forcing it breaks all
  authentication.
- *Superusers* are exempt unconditionally, and `FORCE` cannot help. The official
  `postgres` container makes `POSTGRES_USER` a superuser, so docker-compose and
  Testcontainers both connect as one. Every policy would be inert exactly where
  you most need to catch a leak. `V3__application_role.sql` creates the
  unprivileged `lms_app` role and the transaction manager issues
  `SET LOCAL ROLE lms_app`, which drops both exemptions per transaction.

The acceptance suite proves isolation rather than assuming it: `identity.feature`
runs a query with **no tenant predicate at all** and asserts the result is
confined to the scoped tenant, and that an unscoped query returns nothing.

---

## 5. Licensing — non-negotiable

This repository is Apache-2.0 licensed. **It is not an Apache Software
Foundation project.** Do not use the ASF boilerplate header
("Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements…") — that header asserts ASF provenance and
contributor-agreement assignment that do not apply here.

Every source file carries this header, comment-styled for the file type:

```
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
```

Enforcement runs in CI and will fail the build:

- **Apache RAT** over the whole repo (`.rat-excludes` controls exclusions).
- **`license-maven-plugin`** denies any compile/runtime dependency under GPL,
  AGPL, SSPL, CDDL, EUPL, or MPL.
- **`license-checker`** on the frontend, allowlist
  `MIT;Apache-2.0;BSD-2-Clause;BSD-3-Clause;ISC;0BSD`.

Before adding *any* dependency, check its license. A copyleft dependency is not
a judgement call to make in a PR — it needs an ADR.

---

## 6. Commands

```bash
# Backend
cd backend
./mvnw verify                      # unit + integration tests + license gate
./mvnw test -Dtest=ModularityTests # boundary check alone
./mvnw -Pnative -DskipTests package  # native image (GraalVM 25, ~6-8 GB RAM, ~3 min)

# Whole stack locally
docker compose -f deploy/docker-compose.yml up

# License audit over the repo (same command CI runs)
java -jar apache-rat-0.17.jar --input-exclude-file .rat-excludes -- .
```

---

## 7. Testing expectations

- Unit tests mock all external I/O.
- Integration tests use **Testcontainers Postgres** — never an in-memory
  database. H2 does not have row-level security, so it cannot verify the thing
  most worth verifying.
- `LogisticsSpineAcceptanceTest` walks the entire order-to-invoice path and is
  the definition of done for the MVP. Changes that break it are not finished.
- Assert on behaviour, not on log output.

---

## 8. Deliberately deferred

Not built, and interfaces are left open for them. Do not describe these as
implemented: reverse auction / spot bidding, OCR pipeline, ERP synchronisation,
SAML/OIDC SSO and MFA, multi-leg modal planning, TSP route sequencing, control
tower / exception management, notification engine, i18n, cold-storage archival.

The vision document's §5.1 target of 20 000 GPS pings/second is a **production**
NFR. The free-tier deployment sustains roughly 50–100/second. Say so plainly
wherever throughput is discussed.

---

## 9. Documentation rules

- Architecture decisions go in `DOCS/adr/NNNN-kebab-title.md`. Record the
  decision *and* what it costs.
- Performance numbers in `README.md` must be **measured**, not estimated. If a
  number has not been observed on the real deployment, do not publish it.
