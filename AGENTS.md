# AGENTS.md — working rules for this repository

This file is the contract for anyone (human or agent) writing code here. Read it
before touching the codebase.

---

## 1. What this project is

An MVP of the platform described in `README.md`. The vision document specifies
twelve modules; the code is a **modular monolith of thirteen bounded contexts
plus a shared kernel** (§4 lists them). It began as a thin order-to-bill spine
and has since grown the freight modes (full and part truckload, milk run,
rail/multimodal), warehousing, third-party-logistics client billing and
attribute-based access control. It is not a prototype — every path in scope is
real, tested, and walkable. It is also not complete — see "Deliberately
deferred" below and do not pretend otherwise in docs, comments, or commit
messages.

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
| Spring Boot | **4.1.1** | |
| Spring Modulith | **2.1.1** | |
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
- **Jackson 3, not Jackson 2.** Spring Boot 4 ships Jackson 3. Databind and
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
  optimistic locking along. Factory methods must pass `null` for it. This has
  now been hit three times, so `.github/scripts/check-conventions.sh` fails the
  build on any `@Table` entity that has an `@Id` and no `@Version`.
- **A Cucumber step expression may carry only one keyword annotation, and must
  be unique across *every* step class.** Given/When/Then are interchangeable at
  match time, so the same text under two keywords — or the same text in two
  classes — is a duplicate. Registration of a whole class then aborts, and the
  symptom is steps in an *unrelated* file reporting as "undefined". Hit three
  times: same-method duplicate, same-file duplicate, and
  `assignment is refused mentioning` meaning two different things in planning
  and execution.
- **A scheduled job carries no tenant scope, and under RLS that means it sees
  nothing.** `app_current_tenant()` returns NULL, `tenant_id = NULL` is NULL
  rather than true, and the job reads an empty table and reports success. Use
  `TenantSweep.forEachTenant`, which iterates the tenant register and does the
  work scoped. Never give a job a way to bypass row-level security.
- **`JdbcClient` cannot bind a bare `Instant`.** The PostgreSQL driver refuses
  to infer a SQL type and fails with "Can't infer the SQL type to use". Spring
  Data's converters handle it on the mapping path, so this only bites in
  hand-written statements: pass `instant.atOffset(ZoneOffset.UTC)`.
- **Bind parameters used only in `IS NULL` need an explicit cast.** Postgres has
  nothing to infer a type from and fails with "could not determine data type of
  parameter". Write `CAST(:id AS uuid) IS NULL`, not `:id IS NULL`.
- **Stale `__Accessor_` classes break a plain `verify` after a native build.**
  An earlier native or AOT build leaves `target/classes/*__Accessor_*.class`
  files behind. A later `./mvnw verify` that does not clean then fails with
  "No accessor to get property". Always `./mvnw clean verify`, and always after
  a native build.
- **The OpenAPI contract is split, and fragments must stay self-contained.**
  `api/openapi.yaml` is only the head. Parameters, responses, schemas and paths
  live in `api/spec/` (`paths-<tag>.yaml`, `schemas-<tag>.yaml`,
  `parameters.yaml`, `responses.yaml`), and a Maven antrun step
  (`bundle-openapi`) splices them into `target/openapi/openapi.yaml`, which is
  what the client generator and `OpenApiContractTest` read. Fragments keep
  `#/components/...` references, never cross-file ones: with a cross-file
  `$ref`, openapi-generator invents its own model names and the client stops
  matching the spec. Add a new schema or path to its tag's own fragment.
- **XML comments in `pom.xml` must not contain `--`.** It is illegal inside an
  XML comment, and the parse error does not point at the comment.
- **No Lombok.** Use Java records and Spring Data JDBC constructor binding.
- **No `springdoc-openapi`.** `api/openapi.yaml` is hand-authored and is the
  contract source of truth. Do not generate the spec from annotations; generate
  the *client* from the spec.

---

## 4. Architecture rules

### Module boundaries are enforced by the build

Each bounded context is a top-level package under `com.lms`:

```
identity  masterdata  order  planning  sourcing  execution  telematics  finance
controltower  milkrun  multimodal  warehouse  clientbilling  approvals  shared
```

| Module | Role |
|---|---|
| `identity` | Tenants, org hierarchy (typed levels HQ < LOB < COUNTRY < ZONE/REGION < BRANCH < HUB, created and edited through `/org-units`), users, authentication, RBAC, audit log, and attribute-based access control (policies as per-tenant data, decided by the `@abac` bean). Depends on nothing. |
| `masterdata` | Business partners and KYC, vehicles, drivers, dispatch readiness, terminals as geofences (including HUB, WAREHOUSE and RAILHEAD categories). |
| `order` | Demand ingestion and validation: orders, lines, chargeable weight, dangerous goods, freight mode (FTL or LTL with hubs). |
| `planning` | Consignment generation (lorry receipts and LTL dockets), load building, consolidation; turns a warehouse hand-off into a consignment. |
| `sourcing` | Routing guides, cascading and round-robin allocation, offer timeouts. |
| `execution` | Trip state machine, gate log, weighbridge, dispatch gate, LTL delivery leg. |
| `telematics` | GPS ingest, geofence crossings, route deviation, stoppage detection, retention. |
| `finance` | Versioned rate cards, freight bills, tolerance matching, settlement holds, LTL zone rating and docket receivables, milk-run circuit payables, rail-leg carrier payables. |
| `controltower` | Exception tickets from people and detectors (route deviation, stoppage, ETA slip, temperature excursion, missed rail connections, skipped or late milk-run stops, receipt variances, pick shortfalls). |
| `milkrun` | Versioned multi-stop pickup routes, generated circuit runs, per-stop recording, priced completion. |
| `multimodal` | Itineraries of road and rail legs, railhead terminals, versioned rail rate cards, hand-off dwell rule, missed-connection sweep, announces released rail legs. |
| `warehouse` | ASN receipt with variances, putaway to bins, append-only stock ledger, pick/wave/pack/dispatch of handling units, back-orders, quarantine, cross-dock. |
| `clientbilling` | Third-party logistics: versioned client contracts (with transport and warehouse tax rates), service usage, storage computed from the warehouse ledger, client charges, monthly invoicing with per-line tax, credit notes against issued invoices, a client statement, client portal isolation, margins. |
| `settings` | Typed settings registered in code by their owning modules, grouped into profiles per scope (platform, organisation default, org unit, vehicle, customer, vendor, combination, user), effective-dated versions, resolution with provenance, and each person's own preferences. Depends on nothing; peers use `settings::api`. |
| `approvals` | One approvals module for every flow: requests with a frozen payload, a policy per subject type (copied onto each request), decisions applied in-process by the owning module's handler, the inbox, expiry. Depends on `identity::access` only. |
| `shared` | Kernel (open module): geometry, tenant context, errors, idempotency, audit, query `Slice`. No domain logic. |

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
- **When a module needs a synchronous answer from one that depends on it, it
  publishes an SPI in its own `api/`** and the dependent module implements it.
  `order::api` defines `OrderCancellationHook`, implemented by planning, so that
  cancelling an order can refuse (when freight has left) or cascade (to
  consignments and loads) inside the same transaction. `OrderPlanningPort` is the
  opposite direction: planning telling order what happened (lines planned, order
  fulfilled). Do not replace either with an after-commit event if the caller must
  be able to stop the operation. The same pattern is used by `planning::api`:
  `LoadInUseGuard` (execution implements it: a live trip on the load),
  `LoadCancellationHook` (sourcing implements it: withdraw the open allocation),
  and `LoadAllocationGuard` (sourcing implements it: refuse a change to a
  load's freight while an allocation is offered or awarded), so planning never
  depends on execution or sourcing.
  `order::api` also defines `OrderAmendmentGuard` (planning implements it: refuse
  while a live consignment exists), which is what lets a validated order be
  amended only before freight is cut from it.
- **Anything that needs a person to agree first goes through `approvals`.**
  Never build a second maker-checker. To add a flow: a constant in
  `approvals.api.ApprovalSubjectTypes`, its code default in
  `approvals.command.ApprovalPolicyDefaults` (the one registry; each type is
  registered as the profile setting `approvals.policy.<camelCaseType>` with that
  default, scopes unit, customer and combination), and an
  `ApprovalOutcomeHandler` bean in the owning module (which declares
  `approvals::api` and `settings::api`). The flow builds a `SettingContext` for
  its subject (unit, customer, freight mode where it knows them) and asks
  `ApprovalRequests.isRequired(type, context)` and, when true,
  `request(subject, context, action, payload, summary)` in its own transaction
  and answers 202 with the ticket; the overloads without a context resolve for
  the organisation alone. It never reads a policy value or a setting of its
  own -- `ApprovalPolicyResolver` is the only source, and resolves the setting
  for that context (a profile value is a flat JSON object: `steps` is a comma
  separated list, `dueAfter` an ISO-8601 duration; a property left out is the
  code default's). A mandatory type (`POD_REVERSAL`) stays required whatever a
  profile says, and a write that sets it false is `setting-invalid-value`.
  `GET/PUT/DELETE /approvals/policies` read and write the organisation default. The handler's `onApproved` runs in
  the deciding transaction: re-validate current state and throw the owning
  module's `BusinessRuleViolationException` when the action no longer makes
  sense (the request then stays open), and apply through an unguarded
  `MANDATORY`-propagation path -- the decider's right was the policy's
  permission, not the owning module's command permission. The application
  refuses to start if a type has no handler or a handler no default policy.
- **Order edits carry `If-Match`.** `GET /orders/{id}` returns the version as its
  `ETag`; the edit endpoints (`PUT /orders/{id}`, `PUT /orders/{id}/lines`,
  `DELETE /orders/{id}/lines/{lineId}`, `POST /orders/{id}/amend`) require it in
  `If-Match` (quoted or bare, never `*`) and a stale value is 409
  `concurrent-modification`. A draft is edited freely; a VALIDATED order with no
  consignments only through the amendment, which validates it again and audits
  `ORDER_AMENDED` with before and after. A consignment's particulars
  (`PATCH /consignments/{id}`) follow the same pattern, and load changes after
  building (taking a consignment off, un-planning, cancelling) take the load's
  `ETag` the same way; all of them parse the header with `shared.web.IfMatch`.
  Other update endpoints (terminals) still take the version in the body; the
  header form is the one to copy.
- **Events are past-tense facts** carrying identifiers and values, never
  aggregates or entities. A shared mutable object graph across a boundary
  defeats the point of having one.

### Settings are registered in code and resolved by profile

`com.lms.settings` (V52) holds every tenant-tunable value that is not a
record of its own. A module that owns a setting declares it, nowhere else:

- a `SettingKey<T>` constant (`order.close.tolerancePercent`, dotted, owner
  first) and a `SettingContributor` bean returning `SettingDefinition`s: type
  (BOOLEAN, INTEGER, DECIMAL, STRING, ENUM, DURATION, JSON with named, typed
  properties), system default, bounds, category (OPERATIONS, UI,
  NOTIFICATIONS, COMPLIANCE), and the scope types it may be set at
  (`alsoAt(...)`; ORG_DEFAULT always, PLATFORM only when asked,
  `platformOnly()` for a key tenants cannot override). `effectiveDated()`
  makes every value carry the day it takes effect; COMPLIANCE requires it.
  The registry refuses to start on a duplicate key or a default its own
  bounds refuse, so a definition defect is a boot failure, not bad data.
- the module declares `allowedDependencies = "settings::api"` and reads values
  through the `Settings` port: `effective(key, context)`, `resolve(...)` (with
  provenance) or `resolveAll(keys, context)` for several in one read. The port
  joins the caller's transaction (ADR 0006) and checks no permission.
  `setOrgDefaults(...)` writes the organisation default for a module that
  keeps its own endpoint (`/orders/close-settings`, `/exceptions/detector-settings`).
- a value that used to be a deployment property keeps the property as its
  system default (`@Value` into the contributor), so a tenant that sets
  nothing sees no change. Resolve for the record in hand, never for the
  tenant alone when the record names a unit, vehicle, customer or vendor.
  A trip's context is `execution::api`'s `TripLookupPort.scopeOf` (its unit,
  vehicle and the customers aboard); a threshold is read for the only
  customer aboard, the dispatch checklist for every one of them. Read once per
  record per request, not once per row of a batch (telematics resolves once
  per trip per ingested batch). An order's context carries its freight mode
  and a trip's its load's (`withFreightMode`, `TripScope.freightMode`), so a
  combination naming a mode applies; `SettingContext.FREIGHT_MODES` mirrors
  `order::api` `FreightMode` (settings cannot import it: order depends on
  settings) and `SettingCombinationTest` holds the two equal.

Registered so far: `order.close.*` (unit, customer);
`controltower.detector.etaSlipTolerance` and `.temperatureExcursionTolerance`
(unit, vehicle, customer); `telematics.stoppage.threshold`,
`telematics.routeDeviation.corridorMetres` (unit, vehicle, customer) and
`telematics.stoppage.radiusMetres` (unit, vehicle);
`execution.dispatch.requiredDocuments` (COMPLIANCE, dated, a JSON object of
known document types), `execution.dispatch.weightTolerancePct` and
`execution.tracking.autoDeparture` / `.autoArrival` / `.autoCompleteOnExit`
(unit, vehicle, customer); `finance.match.tolerancePercent` and
`.toleranceAbsolute` (unit, vendor); and the calendar, UI and notification
preferences; `approvals.policy.orderCancellation` and `.podReversal` (unit,
customer, combination; a JSON policy, refused for an unknown permission through
`SettingContributor.refusal`, a write-time check only the owning module can
make). COMBINATION is opted into (V55) by the tracking thresholds, the
dispatch checklist and weight tolerance, both match tolerances, the order
close tolerance, both detector tolerances and the approval policies -- not by
the tracking switches,
`order.close.autoFulfil` or any preference.

The fold is fixed. Per profile, of a key's versions the latest one effective
on the context's `asOf` (default: today in the tenant's `settings.timeZone`)
applies; an undated value applies since always. Across profiles the most
specific one the key allows wins:
PLATFORM (global < country < state) < ORG_DEFAULT < ORG_UNIT (deeper wins:
country < zone < branch < hub) < VEHICLE < CUSTOMER < VENDOR < COMBINATION
(more conditions, then the deeper unit, then the higher priority) < USER.
With none, the system default answers. Every source carries a `label` in
words with the unit's level ("Country: India", "Zone: North", "Branch:
Gurgaon") or a combination's conditions ("Branch Gurgaon + Customer Acme");
consoles show it as given.

VENDOR (V54) names a business partner of type VENDOR, as CUSTOMER names one of
type CUSTOMER; it sits next to CUSTOMER because no key a customer's context
reads is one a vendor's does (a freight bill names a vendor, a trip a
customer), so their relative order decides nothing. Every
resolution is one statement however many profiles apply, units, jurisdiction
and combinations included (`SettingsApiTest` and `SettingsHierarchyApiTest`
count it with `pg_stat_statements`). A stored value the definition now
refuses is skipped, never applied.

Org units (V55) are typed HQ 0, LOB 1, COUNTRY 2, ZONE and REGION 3, BRANCH 4,
HUB 5; `OrgUnitService` (identity, `ORG_UNIT_MANAGE`, audited, `If-Match` on
`PUT /org-units/{id}`) refuses a unit that does not rank strictly below its
parent and above its children (`org-unit-level-invalid`), a COUNTRY without
an alpha-2 `country_code` or a code on another type
(`org-unit-country-invalid`), a `state_code` on HQ, LOB or COUNTRY
(`org-unit-state-invalid`) and a taken slug (`org-unit-slug-taken`). There is
no move and the slug never changes, so a path never changes. The rank rule is
not a database constraint: trees written before V55 were never held to it and
still load and rename; only new writes (create, retype) are checked. A
context that names a unit (or an org path) but neither country nor state
takes the unit's jurisdiction for PLATFORM profiles: the nearest
`country_code` at or above it, and the nearest `state_code` beneath that
country, both read inside the one resolving statement through the unit's
ancestors by `(tenant_id, path)`.

COMBINATION profiles (V55) carry conditions instead of a scope id
(`match_org_unit_id` by subtree, `match_customer_id`, `match_vendor_id`,
`match_vehicle_id`, `match_freight_mode`; two or more) and a `priority`
(-1000..1000); `scope_id` is the profile's own id so a tenant keeps many and
the one-per-scope index stays. Conditions are checked as scope ids are (404
for a record not the tenant's, 403 for a unit outside the caller's reach,
`setting-combination-invalid` for fewer than two, an unknown mode or a
priority out of bounds). A write is refused with
`setting-combination-ambiguous` when another combination could match the
same context (no dimension where both name different values, and units, if
both name one, on one line) with as many conditions, an equally deep unit
and the same priority, and sets one of the same keys; `CombinationRules`
takes a per-tenant transaction advisory lock first so two writes cannot both
pass.

Writes: unknown keys (`setting-unknown`), keys outside their scopes
(`setting-scope-not-allowed`), platform-only keys at a tenant scope
(`setting-not-overridable`), bad values (`setting-invalid-value`) and an
undated value for a dated key (`setting-effective-date-required`) are 409s
with every refused key in `errors`. A dated version on or before today is in
force and is never changed or removed (`setting-value-in-force`); a change is
a new version dated today or later (`setting-effective-date-past` otherwise).
One profile per scope (`setting-profile-scope-taken`); edits carry `If-Match`;
the organisation default and a profile holding a version in force are not
deleted. Every change is audited (resource type `SettingProfile`) with only
the versions that changed, named `key` or `key@date`.

PLATFORM profiles have no tenant (`tenant_id` NULL: the one deliberate
exception to the rule below). Every tenant reads them; row-level security lets
a row with no tenant be written only in a transaction that called
`set_config('app.platform_write', 'on', true)`, which only
`SettingProfileService` does, after checking `PLATFORM_SETTINGS_MANAGE`. That
permission is the platform operator's; no migration grants it, and only the
bootstrap (demo) administrator holds it, through the seeder's grant of every
permission. Do not grant it to a tenant role.

USER profiles are a person's own: `GET/PATCH /settings/me`, no permission, never
another person's, and invisible to the administrative endpoints.

### Idempotency is required on every state-changing endpoint

Retries are normal here, not exceptional: the free instance sleeps after 15
minutes and clients retry the request that wakes it, telematics ingest runs
over unreliable mobile links, and the Modulith event registry delivers **at
least once** and republishes incomplete events after a restart.

- Command endpoints accept an `Idempotency-Key` header and go through
  `IdempotencyService`. The claim is an `INSERT`, not check-then-insert — the
  primary key settles the race between concurrent retries instead of leaving it
  to chance.
- **Bad input answers a 4xx problem document, never 500.** `GlobalExceptionHandler`
  extends `ResponseEntityExceptionHandler`, so malformed or empty bodies, wrong
  types, missing parameters, unrouted paths and wrong methods are 400, 404, 405,
  406 or 415 with a stable `code` (`malformed-request`, `missing-parameter`,
  `invalid-parameter`, `validation-failed`, `not-found`, `method-not-allowed`,
  ...); the catch-all is for genuinely unexpected errors only and says nothing
  about them. 401 and 403 from the security chain are problem documents too
  (`ProblemSecurityHandlers`): a rejected token's reason is never echoed (expiry
  is the one exception, because a client needs it to refresh).
- **Jackson 3 sets `FAIL_ON_NULL_FOR_PRIMITIVES` on**, so a record with an `int`
  or `boolean` sent `{}` fails before bean validation runs. The default is turned
  off in `application.yaml`; where a silent default would be unsafe (a `boolean
  enabled` that would switch a policy off) the field is boxed and `@NotNull`.
- **Lifecycle transitions are audited in the transition's own transaction**
  (`TransitionAudit`, `MANDATORY`, batched; or `AuditTrail.recordAtomically`), so a
  rolled-back transition leaves no audit row. `AuditTrail.record` (`REQUIRES_NEW`)
  is only for entries that must outlive a failure, and see the pool hazard under
  section 8.
- **Lost races answer 409, never 500.** `GlobalExceptionHandler` maps a stale
  `@Version` write to 409 `concurrent-modification` and a unique violation (after
  a check-then-insert) to 409 `duplicate-resource`; the client re-reads and
  retries. Problem documents carry a fixed detail and never a constraint, table
  or SQL text. Other integrity violations (a foreign key, say) stay 500.
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

**A request holds at most one pooled connection at a time** (DOCS/adr/0006).
The registry marks each listener's publication in `REQUIRES_NEW` transactions.
`ReleasingEventListenerFactory` therefore holds `@TransactionalEventListener`
processing back until the committing thread has returned its connection; the
listeners still run on the request thread, in order, before the response. Do not
open a `REQUIRES_NEW` transaction in a request path, and do not bring Spring's
own listener factory back. `EventPublicationPoolApiTest` guards the rule against
a pool of 5.

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
`tenant_isolation.feature` does the same for twelve more tables (read, update,
delete and move-into-another-tenant all refused, as `lms_app`), and
`TenantIsolationApiTest` and the `e2e/tenant_isolation.feature` scenarios prove
over HTTP that tenant B gets 404 for tenant A's order, consignment, load, trip,
allocation, freight bill, exception ticket, ASN, outbound order, handling unit
and client invoice, and sees none of their rows in a listing.

**A foreign key is checked as the table owner, so row-level security does not
stop an id in a request body from naming another tenant's row.** This was a real
hole: tenant B could POST a partner with tenant A's `orgUnitId` and get 201, a
row of B's pointing into A's organisation (and confirming that the unit exists).
Organisational units are now refused centrally in `PolicyDecisionPoint.permitsIn`
(403, identical for a foreign and a nonexistent unit). Every other id a service
takes from a body must be looked up under RLS (which makes a foreign id read as
absent) and never trusted because the database accepted the insert. Only the org
unit path and the ids listed in `ForeignReferenceApiTest` are covered by tests
today; the rest are listed under known defects.

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
./mvnw clean verify                # unit + integration + e2e tests + license gate (always clean: see §3)
./mvnw test -Dtest=ModularityTests # boundary check alone
./mvnw -Pnative -DskipTests package  # native image (GraalVM 25, ~6-8 GB RAM, ~3 min)

# Whole stack locally
docker compose -f deploy/docker-compose.yml up

# Backup/restore drill: pgBackRest PITR, logical round trip (runbook: deploy/dr/README.md)
bash deploy/dr/dr-drill.sh

# License audit over the repo (same command CI runs)
java -jar apache-rat-0.18.jar --input-exclude-file .rat-excludes -- .

# Project conventions no compiler or test catches (same script CI runs)
.github/scripts/check-conventions.sh
```

### What CI enforces, and where

`ci.yml` runs on **every push to every branch** and on pull requests. Anything
judgeable from a single commit belongs here: build, unit tests, the Cucumber
suite against Testcontainers Postgres, module boundaries, project conventions,
RAT headers, and the dependency licence gate.

`pr-checks.yml` runs on pull requests only, and holds what needs the pull
request's metadata or a diff against the base branch:

| Check | Why it cannot be a push check |
|---|---|
| Applied migrations unchanged | Needs the base branch to know which migrations already existed |
| Dependency review | Reports only what the PR *adds*, and reads the advisory database |
| Behaviour change carries a test | Needs the diff to see that domain code moved and tests did not |

The migration check is the load-bearing one. Flyway validates a checksum per
applied script, so editing a migration that has already run does not fail the
build — it fails at startup on every database that already ran it, which
includes production, after the image has been built and pushed.

---

## 7. Testing expectations

- Unit tests mock all external I/O.
- Integration tests use **Testcontainers Postgres** — never an in-memory
  database. H2 does not have row-level security, so it cannot verify the thing
  most worth verifying.
- The Cucumber suite under `backend/src/test/resources/features/` is the
  readable statement of what the platform guarantees. A domain rule with no
  scenario is a rule nobody can point at, and `pr-checks.yml` fails a pull
  request that changes `command/` or `domain/` code without touching a test.
- A single feature walking the entire order-to-invoice path is the definition
  of done for the MVP. `e2e/order_to_bill.feature` is that feature, run over
  HTTP (below); the acceptance suite covers each context's rules in-process.
- API tests (`*ApiTest`) drive the running application over HTTP through a
  Feign client **generated from `api/openapi.yaml`** at build time
  (`openapi-generator-maven-plugin`, test scope only). `OpenApiContractTest`
  proves every served path is in the spec; the generated client proves the
  spec is *usable* -- a renamed field or an undocumented enum value fails to
  deserialise there. Feign refuses a null body argument, so endpoints whose
  body is optional are called with an empty one.
- **End-to-end Cucumber** (`src/test/resources/e2e/`, runner `RunEndToEndTest`,
  glue `com.lms.e2e`) walks order to paid bill entirely over HTTP with the
  generated client, signed in as the seeded demo administrator. Beside
  `order_to_bill.feature` there is one feature per freight mode and billing
  flow (`ltl_docket`, `milk_run`, `rail_itinerary`, `warehouse_flow`,
  `client_billing`), each with its own step class and each tagged (`@ltl`,
  `@milkrun`, `@rail`, `@warehouse`, `@clientbilling`) so one can be run alone
  with `-Dcucumber.filter.tags=@rail`. `HttpWorld` is the shared plumbing
  (sign-in, salted codes, shifted geography, a trip ready to dispatch); step
  texts must stay unique across every class in the package. Each mode keeps to
  its own latitude band so scenarios of different modes cannot overlap terminals. It is separate
  from the acceptance suite on purpose: in-process steps cannot notice a
  controller mapping a field wrongly or a spec naming a field the server never
  sends. The demo tenant is shared by every scenario, so codes are salted and
  each scenario shifts its geography -- terminals of one category may not
  overlap, and the suite must not dodge that rule.
- **Smoke**: `deploy/smoke.sh` is the one smoke check. `SmokeScriptTest` runs it
  against the application in every build, the native-image workflow runs it
  against each binary, and the *Smoke (deployed)* workflow runs it against
  Render. Change the script, not three copies of it.
- **Cancelled rows must not hold a unique key.** `allocation_load_unique` (V33)
  and `trip_live_load_unique` (V34) are partial indexes that ignore CANCELLED
  rows, so a load can be offered or given a trip again; keep the cancelled row as
  history rather than deleting it.
- **Integration tests at the HTTP and database level.** Cucumber acceptance
  scenarios call services in-process; anything about transactions, triggers or
  the wire needs a Spring test on Testcontainers Postgres (see
  `OrderLifecycleIntegrationTest`: a trigger forces a failure mid-cascade and
  the test asserts nothing was left half-done) or an HTTP end-to-end scenario
  (`e2e/lifecycle.feature`). Reproduce a defect with a failing scenario before
  fixing it.
- **Contention and isolation tests** run against real Postgres over HTTP:
  `ConflictHandlingApiTest` (12 threads, same unique key or same aggregate;
  exactly the expected winners, every loser a 409, no 500) and
  `TenantIsolationApiTest`, built on the shared `ApiTestKit` (HTTP, tokens, a
  start-gated `race()`) and `TenantSeeds` (one row of everything per tenant).
  A race test must release all threads together or it measures nothing.
- **Performance** tests carry `@Tag("performance")` and are excluded from the
  default build; run them with `./mvnw verify -Pperformance` (also weekly in
  the *Performance* workflow, or on a PR labelled `performance`). Assert
  relative properties -- a deep keyset page costs what the first page costs --
  over absolute timings, which vary by machine. Count statements with
  `pg_stat_statements` rather than timing them: `LifecycleBulkPerformanceTest`
  asserts that completing a load and cancelling an order cost about one
  statement per consignment at 40 and at 400, so an N+1 shows up as a ratio.
  `WriteContentionPerformanceTest` puts 20 threads on one aggregate and one
  unique key for 15 rounds and asserts only relative properties (no 500, no
  hang, median loser latency a small factor of the winner's, contended cost
  within a factor of uncontended). Restart tests start a pod with
  `SpringApplicationBuilder.run("--key=value")`: builder `.properties()` are
  defaults that `application.yaml` silently overrides.
- **JMeter** (`performance/jmeter/`): `lms-api.jmx` signs in once and drives a
  mixed workload (ten reads at random, one write in ten iterations, a readiness
  probe). `run.sh` runs it and `summarise.py` fails the run on error rate or
  95th percentile; limits are env vars. It targets a deployment, so it lives
  in the on-demand *JMeter (deployed)* workflow, not in the default build.
  Sized for the free tier (5 users); raise load against bigger machines only.
- Assert on behaviour, not on log output.

---

## 8. Deliberately deferred

**Known defects, found by the specification audit and not yet fixed**
- **`AuditTrail.record` and `recordFor` still take a second connection.** They run
  in `REQUIRES_NEW`; with the free-tier pool of 5, requests queued on a row lock
  can leave the lock holder unable to get a connection for its audit row. Every
  command path now audits through `TransitionAudit` or `AuditTrail.recordAtomically`,
  which join the caller's transaction; only the login path (`AuthService`, via
  `recordFor`) still uses the new-transaction form, on purpose, so a failed login
  is recorded. New code must use the atomic form.
- Two audit mechanisms now exist side by side: `TransitionAudit` (batched, for
  bulk transitions) and `AuditTrail.recordAtomically` (single). They should be
  one.
- Org units cannot be moved or deleted, and nothing yet enforces the rank rule
  on trees written before V55 (see the settings section).
- Telematics is deliberately not audited (a stoppage or deviation already
  becomes a control-tower ticket with its own history). Order coverage changes
  (`ORDER_PARTIALLY_PLANNED`, `ORDER_FULLY_PLANNED`) and LTL docket movements
  (`CONSIGNMENT_IN_TRANSIT`, `_ARRIVED_AT_HUB`, `_OUT_FOR_DELIVERY`,
  `_DELIVERED`, one batched row-set per movement) are audited through
  `TransitionAudit`. `ORDER_PARTIALLY_PLANNED` has no scenario: planning draws
  every plannable line in one call, so a partial state is not reachable through
  the services today.
- Cancelling an order or allocation withdraws sourcing work, but a trip already
  raised on a load is refused (`load-has-active-trip`) rather than cancelled:
  cancel the trip first.
- Existing databases may hold AWARDED allocations on CANCELLED loads from before
  the lifecycle fixes; cancelling such an allocation is a no-op on the load.
- Cross-tenant body ids: `ForeignReferenceApiTest` covers order customer and
  origin terminal, order-line consignee and destination terminal, load origin
  terminal and vehicle, routing-guide terminals, trip, allocation, exception,
  consolidation and ASN references, rate-card vendor and terminals, LTL zone
  terminals, client portal users, exception assignees and a user's partner
  attribute, and the organisational unit of a rate card (`orgUnitId` on
  `POST /tariffs`, refused by `PolicyDecisionPoint.permitsIn`). **No test**:
  warehouse bin and SKU ids and milk-run, rail and itinerary ids, which are
  looked up through repositories or ports under RLS but not proved.

**Deferred by design**

Not built, and interfaces are left open for them. Do not describe these as
implemented: reverse auction / spot bidding, OCR pipeline, ERP synchronisation,
SAML/OIDC SSO and MFA, TSP route sequencing (a milk run's stop order is
authored, not optimised), notification engine, i18n, cold-storage archival.
Multimodal itineraries exist, but the planner does not search for them: a
person assembles the legs.

Within the control tower (§3.8), tickets are raised automatically from route
deviation and stoppage (telematics), missed connections (multimodal), skipped
and late milk-run stops, receipt variances and pick shortfalls (warehouse).
The stoppage detector is ping-driven: a trip in transit that reports no
movement (within `telematics.stoppage.radiusMetres`, default
`lms.telematics.stoppage-radius-m`, 100) for `telematics.stoppage.threshold`
(default `lms.telematics.stoppage-threshold-minutes`, 45) outside every
terminal geofence raises one EXTENDED_STOPPAGE ticket, and a device that stops
reporting altogether is therefore not caught. The ETA and temperature detectors
(V35) run off `TripEtaProjected` (one per trip per ingested batch) and
`TemperatureReported` (one per probe reading) events and judge only a trip with
a `trip_watch` row, which the desk sets through
`PUT /api/v1/exceptions/trip-watches/{tripId}` (committed arrival, temperature
band). Tolerances are settings (`controltower.detector.etaSlipTolerance`,
`controltower.detector.temperatureExcursionTolerance`, both also per unit,
vehicle and customer, resolved for the trip), defaulting to `lms.controltower.eta-slip-minutes` (60) and
`lms.controltower.temperature-excursion-minutes` (15); V52 copied the old
`detector_setting` rows into organisation-default profiles and nothing reads
that table any more. One unclosed ticket per
trip and kind is enforced by `exception_ticket_open_detector_idx`.

Gaps the freight-mode, warehouse and billing work left open on purpose:

- A rail leg's freight bill is raised when the leg is RELEASED (V32, keyed on
  `itinerary_leg_id`, expected amount the leg's recorded cost) and then follows
  the ordinary match, dispute and approval path. Settlement holds are per trip,
  so a control-tower hold does not freeze a rail leg's bill.
- Document numbering is `identity::numbering` (`DocumentNumbers`): `next(type,
  orgUnitId)` joins the caller's transaction (`MANDATORY`) and returns empty when
  no active series covers the unit, in which case keep the document's built-in
  numbering; `release(type, number, reason)` is safe to call twice. Never read
  or advance `number_series_counter` from another module, and never draw a
  number in a transaction of its own (ADR 0006).
- A proof of delivery decides money, not trip completion (V47). A completed trip
  whose consignments are not all proved clean gets a `PROOF_OF_DELIVERY` row in
  `settlement_hold`, lifted by the last clean proof or by
  `POST /freight-bills/{id}/proof-hold/release` (`SETTLEMENT_HOLD_RELEASE`, reason
  audited); a line haul is never held. A client's trip transport is invoiceable
  once each of its consignments has a confirmed proof or a manual mark, unless
  the contract bills on dispatch. The completion's hold decision and a proof's
  re-evaluation take a per-trip advisory lock and re-read proofs under it, so a
  hold never outlives clean proofs whichever commits first.
- Credit limits (V46) live in `masterdata`; `order` and `clientbilling` reach
  them through `masterdata::api` (`CustomerCreditPort`), and masterdata reads
  open order demand and receivables through the SPIs it publishes there
  (`OpenOrderDemandSource`, `ReceivablesSource`) so it never depends on either.
  Exposure is open orders at the contract transport rate on weight not yet
  charged, plus unbilled charges, plus unpaid invoices net of credit notes and
  payments -- each kilogram counted once, nothing reset on a calendar, an order
  without a contract counted as zero. It is checked at order validate, amend
  and line add or replace and at the client billing run, in the caller's
  transaction after its write and under a per-customer advisory lock. The hard
  limit refuses (`credit-limit-exceeded`); the soft limit warns
  (`CREDIT_SOFT_LIMIT_EXCEEDED`, `CreditSoftLimitExceeded`). An override
  (`CREDIT_OVERRIDE`) passes one named action up to a stated exposure, once,
  within 24 hours. A limit changes only by maker-checker: `CREDIT_LIMIT_REQUEST`
  asks, a different holder of `CREDIT_LIMIT_APPROVE` decides, both within
  approval limits. A suspended or blacklisted customer gets no new or changed
  orders (`customer-not-trading`); the billing run checks headroom only.
- Order close (V50) is the order module's: `OrderCloseService` asks planning
  through `order::api::OrderFreightProbe` what the freight of each line is doing,
  and planning tells it of partial deliveries (`OrderPlanningPort.recordPartlyDelivered`)
  as well as complete ones. A close never touches consignments, so a short- or
  force-closed order can still have freight moving, proved and billed; it
  leaves credit exposure's open-order term at once. CLOSED is terminal. The
  tolerance is by weight of freight delivered and applies only to fully planned
  orders, because planning still cuts every line of an order in one call: an
  unplanned remainder cannot exist at delivery time, so it is not what the
  tolerance measures. Quantities are not mixed across units of measure anywhere.
  The tolerance and the automatic-fulfilment switch are settings
  (`order.close.tolerancePercent`, `order.close.autoFulfil`) resolved for the
  order's unit and customer, so a customer's profile beats the tenant's; V52
  copied `order_close_setting` into organisation-default profiles and the table
  is read by nothing (kept until no deployment can roll back to code that
  reads it).
- A line is open only while it is neither closed nor cancelled (V51,
  `OrderLine.isOpen`). Planning, coverage, credit exposure and every close rule
  count open lines only: a cancelled line never blocks a close or the automatic
  one, and is not closed with its order; a closed line cannot be cancelled
  (`order-line-already-closed`) nor a cancelled one closed
  (`order-line-already-cancelled`). Cancelling one line reaches planning through
  `order::api::OrderLineCancellationHook`.
- Taking a consignment off a load before it leaves goes through one primitive,
  `planning.command.LoadRelease.takeOff`, whether by hand
  (`DELETE /loads/{id}/consignments/{cid}`) or by cancelling the consignment or
  its order line: drops renumbered 1..n, totals restated from what stays aboard,
  `LOAD_CONSIGNMENT_REMOVED` on the load. They differ only in an emptied load:
  removal refuses to empty a PLANNED load (`load-would-be-empty`), cancellation
  cancels the load it empties. An order's cancellation uses `LoadRelease.release`
  over every touched load, with the same renumbering and restating.
- A warehouse hand-off consignment has no sales order, so trip charges skip it;
  client billing instead charges the hand-off itself from `warehouse::events`
  (`ClientHandOffListener`): handling out (a pallet per unit) and transport (gross
  kg), for the client owning the SKUs inside, split by units held when a unit mixes
  owners, keyed `HANDOFF:<unit>:<client>:<service>` so a redelivery charges once.
  Owners without a client contract, and unowned SKUs, are not billed. Transport on
  such a charge has no `trip_id`, so the margin shows no carrier cost for it.
- A client payment is recorded against the client, not matched to an invoice
  (V30). The ageing endpoint applies payments to the oldest invoice first, a
  convention rather than the client's remittance; with no payment terms on a
  contract it counts days since issue, so nothing is "past due".

The vision document's §5.1 target of 20 000 GPS pings/second is a **production**
NFR. The free-tier deployment sustains roughly 50–100/second. Say so plainly
wherever throughput is discussed.

---

## 9. Documentation rules

- Architecture decisions go in `DOCS/adr/NNNN-kebab-title.md`. Record the
  decision *and* what it costs.
- Performance numbers in `README.md` must be **measured**, not estimated. If a
  number has not been observed on the real deployment, do not publish it.
