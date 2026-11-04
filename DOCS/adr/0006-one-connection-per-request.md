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

# 6. One pooled connection per request, and how to size the pool

Status: **Accepted** · 2026-11-04

## Context

The free deployment runs a Hikari pool of **5** connections (`DB_POOL_MAX`,
`deploy/render.yaml`), a 10-second connection timeout, and up to 20 Tomcat
request threads. Five is right for 0.1 CPU: more connections buy contention, not
throughput. But it only works if no request ever needs two connections at once.
If one does, a handful of concurrent requests can each hold one and wait for a
second until the pool times out. Every queued request then answers 500
"connection is not available".

Every request that published an event to a listener the Spring Modulith
registry tracks needed two connections (issue #191):

- Spring runs `AFTER_COMMIT` listeners from the transaction's `afterCompletion`
  callback, *before* the connection is returned to the pool.
- The registry wraps each tracked listener with `markProcessing`,
  `markCompleted` and, on failure, `markFailed`. Each runs in
  `REQUIRES_NEW` (Modulith 2.1), which suspends the still-bound connection and
  borrows another. `ListenerFailureRecorder` does the same on the failure path.

Validating an order, dispatching or completing a trip, generating consignments,
and from now on raising an order all publish to such a listener (the webhook
outbox at least).

## Decision

**A request holds at most one pooled connection at a time.**

`TenantAwareTransactionManager` counts, per thread, the transactions that are open
and those that are completing. `ReleasingEventListenerFactory` replaces Spring's
`@TransactionalEventListener` factory. Its adapters are Spring's own, except
that processing requested *while a transaction on the thread is completing* is
queued (`AfterConnectionRelease`). The transaction manager runs the queue in
`doCleanupAfterCompletion`, once the thread's last connection is back in the
pool. The listeners therefore still run:

- on the request thread, in the same order, and before the response is written;
- with at-least-once delivery unchanged: the publication is still written to
  `event_publication` inside the publishing transaction, and retry, backoff and
  dead-letter parking work as before;
- each with its own transaction, scoped to the tenant as a request is. Before
  the change, a listener's `REQUIRED` template quietly joined the already
  committed transaction and ran without the request's `SET LOCAL` role and
  tenant.

A listener's own events are processed when its transaction releases its
connection, before the next queued listener runs, as they were before.

### Rejected alternatives

- **Asynchronous listeners** (`@ApplicationModuleListener`, or `@Async` on a
  bounded executor). These release the request's connection, but they move
  listeners off the request thread. Effects such as the freight bill raised on
  trip completion or the webhook outbox row would no longer exist when the
  response is returned. Every caller and test that reads them straight away
  would race. They would also add an executor whose threads compete for the same
  five connections.
- **Writing publication state in the publishing transaction.** Modulith already
  records the publication there. The completion marks happen after commit by
  design, and Modulith 2.1 has no setting that drops their `REQUIRES_NEW`.
- **A larger pool.** It hides the defect: it only moves the request count at
  which the pool starves.

## Sizing rule

With one connection per request at peak:

- `DB_POOL_MAX` is the number of database transactions that can run at once.
  The rest queue on the pool, waiting up to the 10-second connection timeout.
  It does not have to match `TOMCAT_THREADS_MAX`. A request that waits longer
  than the timeout answers 500, so size for the queue to drain within it.
- In-process jobs (event retrier, webhook dispatcher, telematics and
  control-tower sweeps, idempotency pruning) take one connection each while
  they run, from the same pool. Keep `DB_POOL_MAX` at least 2 above the number of
  jobs that can overlap, so that requests still progress.
- Across every instance, `instances × DB_POOL_MAX` plus migrations and any
  console sessions must stay below the database's `max_connections` minus its
  reserved slots. Check the figure on the database itself
  (`SHOW max_connections;`) rather than trusting a plan page. The application
  needs a direct, session-mode connection (`SET ROLE`, `SET LOCAL`), so a
  transaction-mode pooler cannot multiply it.
- On the free tier: one instance, pool of 5. Raise the pool only with the CPU.

### Keeping the rule

- No `REQUIRES_NEW` in a request path. `AuditTrail.record` and `recordFor` are
  the remaining exception: the login path uses them on purpose, so that a failed
  sign-in is still recorded (AGENTS.md §8).
- After-commit listeners must not be invoked outside `ReleasingEventListenerFactory`.
  Do not register Spring's `TransactionalEventListenerFactory` with a lower
  order.
- `EventPublicationPoolApiTest` releases twice the pool size of concurrent
  event-publishing requests against a pool of 5. It fails if any of them needs a
  second connection.

## Consequences

- A connection is held only while there is SQL to run: about 1/N of the pool per
  request rather than 2/N.
- Listener processing is slightly further from Spring's model: the
  `SynchronizationCallback`s of a `TransactionalApplicationListener` see
  `postProcessEvent` before a deferred listener has run. Nothing in the
  application registers one.
- The transaction manager now carries a little per-thread state: two counters
  and a list, which is empty whenever the thread holds no connection. It dies with
  the request's virtual thread.
