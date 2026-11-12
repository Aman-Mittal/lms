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

# 7. A client-side cache for maps and visualizations

Status: **Accepted** · 2026-11-12

## Context

The backend has 512 MB of RAM and 0.1 CPU (AGENTS.md section 1). The map screens
(network map, trip route, itineraries, milk runs) and the control tower's desk
re-ask the server, on every visit, for things that rarely change or only grow:
every terminal's geofence, the whole track of a trip that gained three points,
and a ticket list the browser then counts. That is display-only load, and the
cheapest request is the one never made. The browser has the room: IndexedDB,
the Cache API, Web Workers and a service worker are all native, and the
consoles are static pages.

## Decision

**The consoles keep a copy of what the server already returned to this person,
and use it for display only.** Issue #221.

### What is cached, and what is never

| Cached in the browser | Server-authoritative, never cached |
|---|---|
| Reference geometry: terminals and their geofences (`/terminals`, `/terminals/{id}`), revalidated by `ETag` | State changes, and the reads an edit depends on (versions for `If-Match` are always read fresh) |
| Track points of a trip, extended by `GET /telematics/trips/{id}/track?afterSeq=` | Money: bills, invoices, credit, rates, anything a person acts on financially |
| Map tiles, in the Cache API, by a service worker | Permissions and access decisions, which the server takes on every call |
| Chart and count aggregates derived in the browser from rows it already holds (not stored; computed in a Web Worker) | Geofence crossings and trip events: they drive settlement and alerts and are read live |
| | Large analytics: they stay on the server, and no aggregate endpoint was added for this |

A vehicle's current position and a trip's progress are live and are not cached.

### How it stays correct

- **Revalidation, not trust.** Geometry carries the terminal's `version` as its
  `ETag` (the list carries a digest of its terminals' ids and versions).
  Within 5 minutes the browser's copy is used with no request; after that it
  sends `If-None-Match` and a `304` costs no body. Entries also expire after 24
  hours however often they are revalidated.
- **Tracks are cursor-based.** `afterSeq` is the recorded time of the last point
  held, in epoch microseconds, served by the existing `(tenant, trip,
  recorded_at)` index (a `QueryPlanTest` entry guards it). Without the parameter
  the endpoint answers exactly as before. A point that arrives late, dated before
  the cursor, is not returned until the entry expires (24 hours): acceptable for
  a picture of where a vehicle has been, and the reason the cache never feeds a
  geofence or an ETA. When retention has collapsed the raw points, the server
  answers the simplified polyline as plain pairs and the browser replaces what it
  held.
- **Long tracks are thinned before drawing** (Douglas-Peucker, about 3 m, over
  1500 points) in a Web Worker, so the main thread does not stall. The cache
  keeps the full track.
- **Tiles**: cache first, expiry 7 days, at most 600 entries and 200 KB a tile,
  least recently used out first. The service worker is registered only in
  production builds, scoped to the console's own path, and handles only GET image
  requests whose path looks like a tile and that carry no `Authorization`
  header. It cannot see an API response.
- **Switch**: setting `ui.clientCache` (boolean, default on; organisation default
  and organisational unit) turns it off for a tenant or a unit. Off, nothing is
  opened or stored, existing data is deleted at the next sign-in, the worker is
  unregistered and every read goes to the network.

### Security rules

- **One database per tenant and user**, named `lms-cache:<tenantId>:<userId>`.
  Binding one deletes every other `lms-cache:*` database in the origin, so a
  second person on a shared device never finds the first person's data.
- **Wiped** on sign-out (including a 401 that ends the session), on a change of
  tenant or user, when `cacheEpoch` changes, and the tile cache with it (which
  areas someone looked at is theirs to keep private).
- **`cacheEpoch`** is on `GET /me`: a digest (SHA-256, truncated) of tenant,
  user, organisational unit and path, the role codes held and the sorted
  permissions of the token. It is computed from values the call already
  fetched, so it costs no query and cannot go stale the way a counter bumped by
  every role, grant and unit write would when one write path forgets to bump it.
  Anything that changes what the caller may see changes an input. Checked on
  every `/me` load.
- **Never stored**: tokens, passwords, refresh tokens, one-time codes. The token
  stays in memory only, as before.
- **The cache can not widen access.** It holds only what the server returned to
  this user under row-level security and ABAC (ADR 0005); there is no path by
  which a cached row is shown to someone the server did not already show it to.
  A permission taken away takes effect at the next sign-in, where the epoch
  differs and the copy is deleted; within a signed-in session a cached map can
  show a geofence the person has since lost the right to read, until the
  entry's 24 hours end or the session does.
- **Cross-site scripting reads it.** IndexedDB is same-origin storage, so a
  script injected into a console can read the cache exactly as it can read the
  page. That is why the cache holds only what the screens already show, why a
  strict Content-Security-Policy on the consoles' hosting matters more now
  (for instance `script-src 'self'`), and why
  nothing sensitive is in it.

## Costs

- **Staleness, bounded.** A geofence edited elsewhere shows up within 5 minutes,
  a late ping within 24 hours. Both are display-only; neither feeds a decision.
- **A permission removal is not instant for cached geometry** within an open
  session (above). Reference geometry is low sensitivity; anything sensitive is
  not cached.
- **Browser storage and its quirks**: quota, eviction under pressure, private
  windows without IndexedDB, and Safari clearing script-writable storage after
  seven days of non-use. Every read tolerates a missing or failing cache and
  falls through to the network; nothing depends on it.
- **A conditional request still reaches the server** after the fresh window
  (one query, no body); it is cheaper, not free. The `ETag` of the list is
  decided by the same query that would have produced the body.
- **Code and bundle**: a few KB in each console's main bundle for the cache
  module (the Angular initial total is 890 kB against the 900 kB budget); the workers and the simplifier load only with the screens that use
  them. Two copies of the module, one per console, as with every other shared
  piece of the consoles.
- **Tests**: a service worker is not exercised by the Playwright suite
  (Playwright cannot route what a worker fetches, which would blind the
  suites that stub tiles), so it is blocked there; the cache and the cursor are.
- **A new CORS request header**, `If-None-Match`, is allowed. Cross-origin
  conditional requests cost a preflight, cached for an hour.
- **Tile servers that refuse CORS** are still shown (the page's own request is
  made) but not cached.

## Alternatives considered

- **Server-side caching** (a Redis, or in-process caches): rejected by the
  memory budget (AGENTS.md section 1, and no new infrastructure).
- **Browser HTTP cache only**: the API answers `Cache-Control: no-store` for
  authenticated responses, correctly; making them cacheable would also put
  per-tenant data in shared caches. Revalidation is done by the console instead.
- **A counter bumped by every permission write as the epoch**: needs a table
  or a column and a bump in every writer; the digest is correct by construction.
- **`idb` or another IndexedDB wrapper**: the native API is about 150 lines
  here; no new dependency, no new licence to audit.
