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

# 2. Spring Data JDBC over JPA/Hibernate

Status: **Accepted** · 2026-08-04

## Context

The platform needs a persistence layer for a 512 MB, 0.1 CPU deployment
compiled to a GraalVM native image.

A licensing objection to Hibernate is sometimes raised and is **not** the reason
for this decision: Hibernate ORM relicensed from LGPL 2.1 to **Apache License
2.0** at version 6.0. JPA would pass this project's dependency allowlist
without difficulty. The decision is made on footprint and on native-image
behaviour, not on license.

The relevant differences:

| | Spring Data JDBC | JPA / Hibernate |
|---|---|---|
| Entity proxying | none | bytecode proxies for lazy loading |
| Metamodel | none | reflection-heavy, built at startup |
| Identity map / dirty checking | none | persistence context |
| Native-image hints | minimal | substantial, and a longer build |
| Loading semantics | you wrote the query | depends on fetch strategy and context state |

## Decision

**Spring Data JDBC** for all persistence, **Flyway** for migrations, plain SQL
via `JdbcClient` for read models that span aggregates.

The deciding argument is not only the smaller resident set. It is that Spring
Data JDBC's model — an aggregate is loaded and saved whole, with no lazy
loading, no proxies, and no persistence context spanning a request — makes
memory consumption a function of what the code explicitly asks for. Under a hard
512 MB ceiling, predictability is worth more than convenience. The same property
makes it an easier target for AOT compilation: there is far less runtime
reflection to describe to the native-image build.

It also reinforces ADR-0001. Spring Data JDBC forces aggregate boundaries to be
declared rather than discovered, which is the same discipline the module
boundaries impose.

## Consequences

**What this costs — and it is not nothing.**

- **No dirty checking.** Every mutation ends in an explicit `save()`. Forgetting
  one loses the write silently. This is the single most likely source of bugs
  arising from this decision.
- **No lazy loading, no relationship navigation.** Reads that span aggregates
  are hand-written SQL. Expect a `…ReadModel` or `…QueryRepository` beside most
  aggregates that needs a join.
- **Aggregate-scoped updates rewrite child rows.** Spring Data JDBC's default
  strategy for a one-to-many is delete-and-reinsert on save. For large
  collections this is wasteful; for `gps_ping` and other high-volume tables,
  write directly with `JdbcClient` rather than modelling them as aggregate
  children.
- **No second-level cache**, which is fine here — there is no memory to spare
  for one anyway.
- **More SQL to review.** Migrations and queries are hand-written, so schema
  mistakes are caught by tests rather than by a mapping layer.

**Mitigations in place.** Aggregates are kept small and their boundaries follow
the module boundaries. Integration tests run against real Postgres via
Testcontainers, never H2 — dialect differences and row-level security both need
the real thing. Read models live in their own classes so that a query spanning
aggregates is visible rather than incidental.

**If this proves wrong.** The reversal cost is real but bounded: it is confined
to the repository layer and the aggregate classes, since no JPA-specific
semantics leak into controllers or domain services. Migrating to JPA later would
mean annotating entities and rewriting repositories, not redesigning the domain.
