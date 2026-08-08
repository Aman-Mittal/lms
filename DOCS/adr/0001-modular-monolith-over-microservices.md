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

# 1. Modular monolith over microservices

Status: **Accepted** · 2026-08-04

## Context

The vision document (`README.md` §4) is written in the language of
microservices: "All microservices MUST define their interfaces using OpenAPI
3.0", scaffolding CLIs for new services, service-level ephemeral environments,
Kafka or RabbitMQ between services, distributed tracing across service hops.

That target architecture is reasonable at the scale the document imagines. It is
not reachable for this MVP, for one blunt reason: the deployment target is a
**single Render free web service with 512 MB of RAM and 0.1 CPU**. A single
Spring Boot service consumes a meaningful fraction of that. Eight of them, plus
a broker, plus a service registry, cannot exist there at all.

The choice is therefore not "monolith or microservices". It is "modular monolith
now, with boundaries real enough to split later" versus "microservices never".

## Decision

One deployable unit. One Maven module. **One package per bounded context** under
`com.lms`, with **Spring Modulith** verifying the boundaries at build time.

```
com.lms.{identity, masterdata, order, planning, sourcing,
         execution, telematics, finance, shared}
```

Three rules make the boundaries meaningful rather than decorative:

1. **No direct cross-context calls.** Contexts communicate exclusively through
   domain events on `ApplicationEventPublisher`. `ModularityTests` fails the
   build if `execution` imports from `finance`.
2. **Explicit public surface.** A context's root package is its API; nested
   packages are internal and Modulith enforces that.
3. **Events carry identifiers and values, not entities.** No shared mutable
   object graph across a boundary.

The broker requirement is met by **Spring Modulith's JDBC event publication
registry** — a transactional outbox in the existing Postgres. It provides the
properties §3.11 actually asks for: durable hand-off, retry with backoff, and
republication of incomplete events after a restart. It does so with zero
additional infrastructure and zero additional memory.

## Consequences

**What this buys.** The MVP fits in the memory budget. A single transaction can
span contexts during the synchronous parts of a workflow, which removes the
distributed-saga problem entirely at this stage. Local development is
`docker compose up` with two containers. Refactoring a boundary is a package
move, not a deployment negotiation.

**What it costs — stated plainly.**

- **No independent scaling.** The telematics ingest path and the invoicing path
  scale together whether that makes sense or not. §5.1 wants the GPS pipeline to
  scale independently to 20 000 rps; this architecture cannot do that. It is the
  first thing that must be extracted when load justifies it.
- **No independent deployment or language choice.** One release cadence.
- **The outbox is not Kafka.** It gives at-least-once delivery and durable
  retry, but no partitioning, no replay from an arbitrary offset, no consumer
  groups, no stream processing. Anything needing those must wait for a real
  broker.
- **A shared database is a shared failure domain**, and it makes it easy to
  violate a boundary through SQL rather than through Java. Modulith cannot see
  a cross-context join. Reviewers must.

**The intended exit.** Contexts already communicate by event, so extraction is
tractable: replace the in-process publisher with a broker publisher for the
context being split, move its tables to their own schema, and promote its
internal API to HTTP. `telematics` is the natural first candidate — it is the
highest-volume, least-transactional context. That work is not scheduled and
should not be pre-built for.
