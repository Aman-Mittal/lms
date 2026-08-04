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

# 5. Row-level multi-tenancy with Postgres RLS

Status: **Accepted** · 2026-08-04

## Context

§2.1 is emphatic: "Data from Tenant A MUST be structurally isolated from Tenant
B at the database schema or row level." It permits either strategy.

Schema-per-tenant gives stronger isolation but multiplies the object count in a
**1 GB free Postgres instance**, multiplies Flyway's migration work by the
tenant count, and makes cross-tenant platform queries awkward. Database-per-
tenant is impossible — the free plan allows one database per workspace.

Row-level it is. The real question is how to make row-level isolation
trustworthy, because its well-known failure mode is a single query missing its
`WHERE tenant_id = ?` clause and silently returning another tenant's data.

§2.1 also requires an N-level organisational hierarchy (HQ → LOB → Region →
Branch → Hub) with strictly top-down visibility: a Region user sees all Branches
beneath it and no sibling Region.

## Decision

### Three layers, and the third one is the point

1. **Schema.** `tenant_id UUID NOT NULL` on every business table, positioned
   **first in every index** so tenant-scoped queries are index-served.

2. **Application.** A `TenantContext` holding the tenant id, populated by a
   servlet filter from the authenticated JWT's `tenant` claim, and applied by
   repositories.

3. **Database — the fail-safe.** Postgres **row-level security** policies on
   every business table, comparing `tenant_id` against
   `current_setting('app.tenant_id')`. A `TenantAwareTransactionManager`
   extending `JdbcTransactionManager` overrides `prepareTransactionalConnection()`
   to issue `SET LOCAL app.tenant_id = ?` at transaction start.

Layers 1 and 2 are the normal path. Layer 3 exists because layer 2 will
eventually be forgotten. **With RLS in place, a query missing its tenant
predicate returns zero rows instead of another tenant's rows** — the failure
becomes a visible bug rather than a silent data breach. That asymmetry is the
entire justification for the added complexity.

`SET LOCAL` is used rather than `SET` deliberately: it is scoped to the
transaction, so a pooled connection cannot carry one tenant's setting into
another tenant's request.

### Organisational hierarchy

`org_unit` carries `parent_id` plus a **materialised path** (`/hq/north/branch-7/`)
indexed with `text_pattern_ops`. Top-down visibility is:

```sql
WHERE org_path LIKE :callerPath || '%'
```

This deliberately avoids both the `ltree` extension (one more thing to install
on a disposable database) and a recursive CTE on the request path.

## Consequences

**What this costs.**

- **Table owners bypass RLS, and on Render the application *is* the owner.**
  Postgres exempts a table's owner from its own policies. Render's free plan
  provides a single role that owns the schema and runs the application, so
  `ENABLE ROW LEVEL SECURITY` alone would leave every policy **silently inert** —
  the most dangerous failure available here, because nothing misbehaves until
  the day a second tenant exists.

  The fix is `ALTER TABLE … FORCE ROW LEVEL SECURITY`, which applies policies to
  the owner as well. Every protected table gets both `ENABLE` and `FORCE`; see
  `V2__identity.sql`. The originally intended mitigation — a separate,
  non-owning application role — is not available on a single-role database.

  A **superuser** still bypasses RLS regardless of `FORCE`. The application must
  never connect as one. Because both of these failure modes are invisible in
  normal operation, `RowLevelSecurityIntegrationTest` asserts that isolation
  actually holds rather than trusting that it was configured.

- **The empty-tenant case must map to NULL, not to a cast error.** The
  transaction manager writes an empty string when no tenant is bound, and
  `''::uuid` raises rather than yielding NULL. Policies therefore read the
  setting through `app_current_tenant()`, which applies `NULLIF` first, so an
  unset scope filters every row out instead of erroring — failing closed, and
  without turning every unscoped background query into a stack trace.

- **Login needs one deliberate escape hatch.** A caller supplies a tenant *code*
  before any scope exists, so an RLS-protected `tenant` table would hide the row
  needed to establish the scope. Rather than leaving that table unprotected, a
  single `SECURITY DEFINER` function, `resolve_tenant_by_code`, returns an id and
  nothing else. It is the only route into a protected table without a scope, and
  it is narrow enough to audit at a glance.
- **`SET LOCAL` requires a transaction.** Any read executed outside one gets no
  tenant setting, and with RLS active will return nothing. Read paths must be
  transactional. This is a behaviour change that will surprise anyone expecting
  a bare `JdbcClient` call to work.
- **A per-transaction round trip** for the `SET LOCAL`. Negligible next to
  everything else, but it is not free.
- **Platform-level queries need an explicit escape hatch** — a distinct role or
  a `BYPASSRLS` grant used deliberately and audited. Cross-tenant reporting
  cannot use the application role.
- **Materialised paths must be rewritten when a subtree moves.** Org
  reorganisations are rare and the subtree is small, so a straightforward
  cascading update is acceptable; it must not be forgotten.
- **The path separator is reserved.** Org unit slugs cannot contain `/`, and are
  validated on write. `LIKE` also makes `%` and `_` meaningful — slugs are
  restricted to a safe character set rather than escaped at every call site.

**Not implemented.** §2.1's Cross-Branch Access Control Lists, which grant a
user visibility into a sibling subtree, are deferred. The `org_path` prefix
model handles strictly top-down visibility only; ACLs would need a separate
grant table joined into the visibility predicate. This is a known gap, not an
oversight.
