/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import { API, apiSession, openScreen, signIn, signOut, stubTiles, type Session } from './support';

/*
 * The browser-side cache (DOCS/adr/0007): a trip's track is kept in IndexedDB and the next visit asks
 * the server only for what is newer; sign-out deletes it; and the `ui.clientCache` setting turns it off.
 *
 * The track is answered by the test, as in maps.spec.ts: a trip with a real stream of pings needs a
 * dispatched trip, which the backend's own tests cover. The server's `afterSeq` behaviour is proved
 * there too; this checks what the console asks for and keeps. The tile service worker is blocked for
 * the whole suite (see playwright.config.ts), so it is not exercised here.
 */

/** [lon, lat, seq], oldest first: what `track?afterSeq=` answers. */
type Point = [number, number, number];

interface StubbedTrip {
  id: string;
  /** The `afterSeq` of each track request, in order; null where there was none. */
  asked: Array<number | null>;
  points: Point[];
}

async function stubTrip(page: Page, points: Point[]): Promise<StubbedTrip> {
  const trip: StubbedTrip = { id: crypto.randomUUID(), asked: [], points };
  await page.route(`**/api/v1/trips/${trip.id}`, (route) => route.fulfill({ json: {
    id: trip.id, tripNo: 'TRP-CACHE-1', status: 'IN_TRANSIT', originTerminalCode: 'ORIG', destinationTerminalCode: 'DEST',
  } }));
  await page.route(`**/api/v1/telematics/trips/${trip.id}/progress`, (route) => route.fulfill({ json: {
    tripId: trip.id, tripNo: 'TRP-CACHE-1', status: 'IN_TRANSIT', lat: 19.35, lon: 73.4, completionPct: 40,
    lastFixAt: new Date().toISOString(),
  } }));
  await page.route(`**/api/v1/telematics/trips/${trip.id}/track**`, (route) => {
    const raw = new URL(route.request().url()).searchParams.get('afterSeq');
    const after = raw === null ? null : Number(raw);
    trip.asked.push(after);
    // Without afterSeq: [lon, lat] pairs, as before. With it: the newer points, each with its seq.
    return route.fulfill({
      json: after === null ? trip.points.map(([lon, lat]) => [lon, lat]) : trip.points.filter((p) => p[2] > after),
    });
  });
  return trip;
}

async function showRoute(page: Page, trip: StubbedTrip, points: number): Promise<void> {
  await openScreen(page, 'nav-trip-route', 'Trip route');
  await page.getByTestId('trip-id').fill(trip.id);
  await page.getByRole('button', { name: 'Show route' }).click();
  await expect(page.getByTestId('route-trip-no')).toHaveText('TRP-CACHE-1');
  await expect(page.locator('[data-testid="map-feature"][data-kind="track"]')).toHaveAttribute('data-points', String(points));
}

/** The names of the cache databases this origin holds. */
function cacheDatabases(page: Page): Promise<string[]> {
  return page.evaluate(async () =>
    (await indexedDB.databases()).map((d) => d.name ?? '').filter((n) => n.startsWith('lms-cache:')));
}

/** Sets `ui.clientCache` on the tenant's default profile, making the profile if there is none. */
async function setClientCache(request: APIRequestContext, session: Session, value: boolean | null): Promise<void> {
  const headers = { Authorization: `Bearer ${session.token}` };
  const found = await request.get(`${API}/api/v1/settings/profiles?scopeType=ORG_DEFAULT`, { headers });
  expect(found.ok(), `list profiles: ${found.status()}`).toBeTruthy();
  const existing = (await found.json()).items[0] as { id: string; version: number } | undefined;
  if (!existing) {
    if (value === null) {
      return;
    }
    const made = await request.post(`${API}/api/v1/settings/profiles`, {
      headers, data: { scopeType: 'ORG_DEFAULT', values: { 'ui.clientCache': value } },
    });
    expect(made.ok(), `create default profile: ${made.status()} ${await made.text()}`).toBeTruthy();
    return;
  }
  const read = await request.get(`${API}/api/v1/settings/profiles/${existing.id}`, { headers });
  const patched = await request.patch(`${API}/api/v1/settings/profiles/${existing.id}`, {
    headers: { ...headers, 'If-Match': read.headers()['etag'] }, data: { values: { 'ui.clientCache': value } },
  });
  expect(patched.ok(), `set ui.clientCache: ${patched.status()} ${await patched.text()}`).toBeTruthy();
}

test.describe('client-side cache', () => {
  test('a reload draws the cached track and asks the server only for the points after it', async ({ page }) => {
    const trip = await stubTrip(page, [[72.85, 19.05, 1000], [73.10, 19.20, 2000], [73.40, 19.35, 3000]]);
    await stubTiles(page);

    await signIn(page);
    await showRoute(page, trip, 3);
    // Nothing held yet: everything after the start.
    expect(trip.asked).toEqual([0]);
    await expect.poll(() => cacheDatabases(page)).toHaveLength(1);

    // The vehicle reports one more point while nobody is looking; the person comes back to a fresh page.
    trip.points.push([73.5, 19.4, 4000]);
    trip.asked.length = 0;
    await page.reload();
    await signIn(page);
    await showRoute(page, trip, 4);

    // The three held points were not asked for again: the request carried the cursor of the last one.
    expect(trip.asked).toEqual([3000]);
    await expect(page.locator('[data-testid="map-feature"][data-kind="track"]')).toHaveAttribute('data-lat', '19.05000');
  });

  test('signing out deletes the cache databases', async ({ page }) => {
    const trip = await stubTrip(page, [[72.85, 19.05, 1000], [73.10, 19.20, 2000]]);
    await stubTiles(page);

    await signIn(page);
    await showRoute(page, trip, 2);
    await expect.poll(() => cacheDatabases(page)).toHaveLength(1);

    await signOut(page);
    await expect.poll(() => cacheDatabases(page)).toEqual([]);
  });

  test('with ui.clientCache off nothing is stored and the track is read whole', async ({ page, request }) => {
    const session = await apiSession(request);
    await setClientCache(request, session, false);
    try {
      const trip = await stubTrip(page, [[72.85, 19.05, 1000], [73.10, 19.20, 2000]]);
      await stubTiles(page);

      await signIn(page);
      await showRoute(page, trip, 2);

      // The plain read: no cursor was sent, and no database was made.
      expect(trip.asked).toEqual([null]);
      expect(await cacheDatabases(page)).toEqual([]);
    } finally {
      await setClientCache(request, session, null);
    }
  });
});
