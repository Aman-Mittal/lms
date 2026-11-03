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

import { expect, test, type Page } from '@playwright/test';
import {
  apiSession, newMilkRunRoute, newRailItinerary, newReportingVehicle, openScreen, signIn, stubTiles, tag,
  withoutPermissions, type Site,
} from './support';

/** The feature the map lists for an id, which carries what was drawn. */
function feature(page: Page, kind: string, id: string) {
  return page.locator(`[data-testid="map-feature"][data-kind="${kind}"][data-id="${id}"]`);
}

/** A point is drawn where the site really is, to the precision the list gives. */
async function expectAt(page: Page, kind: string, site: Site) {
  const f = feature(page, kind, site.id);
  await expect(f).toHaveCount(1);
  await expect(f).toHaveAttribute('data-lat', site.lat.toFixed(5));
  await expect(f).toHaveAttribute('data-lon', site.lon.toFixed(5));
}

test.describe('maps', () => {
  test('the network map draws sites with their geofence and vehicles where they reported', async ({ page, request }, info) => {
    const salt = tag(info);
    const session = await apiSession(request);
    const { from: site } = await newRailItinerary(request, session, salt);
    const vehicle = await newReportingVehicle(request, session, salt);
    const tiles = await stubTiles(page);

    await signIn(page);
    await openScreen(page, 'nav-map', 'Network map');
    await expect(page.getByRole('alert')).toHaveCount(0);

    await expectAt(page, 'site', site);
    await expect(feature(page, 'site', site.id)).toHaveAttribute('data-radius', '750');
    const truck = feature(page, 'vehicle', vehicle.id);
    await expect(truck).toHaveAttribute('data-lat', vehicle.lat.toFixed(5));
    await expect(truck).toContainText(vehicle.registrationNo);
    await expect(page.getByTestId('sites')).toContainText(site.code);

    // Leaflet asked for tiles from the configured server (here, answered by the test).
    await expect.poll(() => tiles.count()).toBeGreaterThan(0);
    await expect(page.locator('.leaflet-control-attribution')).toContainText('OpenStreetMap');
  });

  test('an itinerary shows its rail leg between the two railheads', async ({ page, request }, info) => {
    const salt = tag(info);
    const itinerary = await newRailItinerary(request, await apiSession(request), salt);
    await stubTiles(page);

    await signIn(page);
    await openScreen(page, 'nav-itineraries', 'Itineraries');
    const row = page.getByTestId('itineraries-row').filter({ hasText: itinerary.shipmentRef });
    await expect(row).toHaveCount(1);
    await row.getByRole('button', { name: itinerary.itineraryNo }).click();

    const detail = page.getByTestId('itinerary-detail');
    await expect(detail).toBeVisible();
    await expect(detail.getByTestId('legs-row')).toHaveCount(1);
    await expect(detail.getByTestId('legs')).toContainText('RAIL');

    const leg = detail.locator('[data-testid="map-feature"][data-kind="leg-RAIL"]');
    await expect(leg).toHaveCount(1);
    await expect(leg).toHaveAttribute('data-points', '2');
    await expect(leg).toHaveAttribute('data-lat', itinerary.from.lat.toFixed(5));
    await expect(leg).toContainText(`${itinerary.from.code} → ${itinerary.to.code}`);
    await expectAt(page, 'stop', itinerary.to);
  });

  test('a milk-run route draws the plant, its stops in order, and the closed circuit', async ({ page, request }, info) => {
    const salt = tag(info);
    const route = await newMilkRunRoute(request, await apiSession(request), salt);
    await stubTiles(page);

    await signIn(page);
    await openScreen(page, 'nav-milk-runs', 'Milk runs');
    await page.getByTestId('routes-row').filter({ hasText: route.code }).getByRole('button', { name: route.code }).click();

    const detail = page.getByTestId('route-detail');
    await expect(detail).toBeVisible();
    await expect(detail.getByTestId('stops-row')).toHaveCount(2);
    await expect(detail.getByTestId('stops-row').first()).toContainText(route.stops[0].code);

    await expectAt(page, 'plant', route.plant);
    const stops = detail.locator('[data-testid="map-feature"][data-kind="stop"]');
    await expect(stops).toHaveCount(2);
    await expect(stops.first()).toContainText(`Stop 1: ${route.stops[0].code}`);
    // Plant, two stops, back to the plant.
    await expect(detail.locator('[data-testid="map-feature"][data-kind="leg-ROAD"]')).toHaveAttribute('data-points', '4');
    await expect(detail.getByTestId('no-runs')).toBeVisible();
  });

  test('a trip that does not exist is refused on the route screen', async ({ page }) => {
    await stubTiles(page);
    await signIn(page);
    await openScreen(page, 'nav-trip-route', 'Trip route');
    await page.getByTestId('trip-id').fill(crypto.randomUUID());
    await page.getByRole('button', { name: 'Show route' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('trip-route')).toHaveCount(0);
  });

  test('a trip route draws the reported track and the latest position', async ({ page }) => {
    // The track itself is answered by the test: a trip with a real reported
    // track needs a dispatched trip and a stream of pings, which the backend's
    // own end-to-end suite already proves. This checks what the screen draws.
    const tripId = crypto.randomUUID();
    const track = [[72.85, 19.05], [73.10, 19.20], [73.40, 19.35]];
    await page.route(`**/api/v1/trips/${tripId}`, (route) => route.fulfill({ json: {
      id: tripId, tripNo: 'TRP-MAP-1', status: 'IN_TRANSIT', originTerminalCode: 'ORIG', destinationTerminalCode: 'DEST',
    } }));
    await page.route(`**/api/v1/telematics/trips/${tripId}/track`, (route) => route.fulfill({ json: track }));
    await page.route(`**/api/v1/telematics/trips/${tripId}/progress`, (route) => route.fulfill({ json: {
      tripId, tripNo: 'TRP-MAP-1', status: 'IN_TRANSIT', lat: 19.35, lon: 73.4, completionPct: 40,
      lastFixAt: new Date().toISOString(),
    } }));
    await stubTiles(page);

    await signIn(page);
    await openScreen(page, 'nav-trip-route', 'Trip route');
    await page.getByTestId('trip-id').fill(tripId);
    await page.getByRole('button', { name: 'Show route' }).click();

    await expect(page.getByTestId('route-trip-no')).toHaveText('TRP-MAP-1');
    const line = page.locator('[data-testid="map-feature"][data-kind="track"]');
    await expect(line).toHaveAttribute('data-points', '3');
    await expect(line).toHaveAttribute('data-lat', '19.05000');
    await expect(page.locator('[data-testid="map-feature"][data-kind="position"]')).toHaveAttribute('data-lon', '73.40000');
  });

  test('the multimodal screens are offered only to those who may read them', async ({ page }) => {
    await withoutPermissions(page, ['MULTIMODAL_READ', 'MILKRUN_READ']);
    await signIn(page);
    await expect(page.getByTestId('nav-map')).toBeVisible();
    await expect(page.getByTestId('nav-itineraries')).toHaveCount(0);
    await expect(page.getByTestId('nav-milk-runs')).toHaveCount(0);
  });
});
