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
import { signIn } from './support';
import {
  busyTripId, cancelledTripId, carrierId, plantId, railheadA, railheadB, stubMilk, stubMilkSession, supplierA, supplierB,
  tripId, type MilkStub,
} from './support-milk';

/*
 * Milk runs and itineraries, in both consoles: publish a route, plan its runs, hand a run to a trip, record each stop
 * and complete the run; plan an itinerary and report a rail leg's milestones. The API is stubbed so the same assertions
 * run whatever the demo tenant holds; the stub keeps the server's rules and the spec checks what each console sends.
 * Set E2E_STUB_SESSION=1 to stub the sign-in too, for a run with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubMilkSession(page);
  }
});

/** The last request the stub saw whose path ends as given. */
function sent(stub: MilkStub, ending: string) {
  return stub.changes.filter((c) => c.path.endsWith(ending)).at(-1);
}

async function openMilkDesk(page: Page) {
  await page.getByTestId('nav-milk-run-desk').click();
  await expect(page.getByRole('heading', { name: 'Milk run desk', level: 1 })).toBeVisible();
}

async function openItineraryDesk(page: Page) {
  await page.getByTestId('nav-itinerary-desk').click();
  await expect(page.getByRole('heading', { name: 'Itinerary desk', level: 1 })).toBeVisible();
}

/** Fills the publish form for a route with the given supplier terminals. */
async function fillRoute(page: Page, code: string, plant: string, suppliers: string[]) {
  await page.getByTestId('mr-code').fill(code);
  await page.getByTestId('mr-plant').fill(plant);
  await page.getByTestId('mr-from').fill('2026-12-01');
  await page.getByTestId('mr-departs').fill('06:30');
  await page.getByTestId('mr-rate').fill('1200');
  await page.getByTestId('mr-fee').fill('150');
  for (let i = 0; i < suppliers.length; i++) {
    if (i > 0) {
      await page.getByTestId('mr-stop-add').click();
    }
    await page.getByTestId('mr-stop-terminal').nth(i).fill(suppliers[i]);
    await page.getByTestId('mr-stop-open').nth(i).fill(String(30 * i));
    await page.getByTestId('mr-stop-close').nth(i).fill(String(30 * i + 20));
    await page.getByTestId('mr-stop-dwell').nth(i).fill('10');
  }
}

test.describe('milk run desk', () => {
  test('a route is published, its runs planned, and a run taken stop by stop to completion', async ({ page }) => {
    const stub = await stubMilk(page);
    await signIn(page);
    await openMilkDesk(page);

    await test.step('a route is published with two suppliers', async () => {
      await fillRoute(page, 'MR-A', plantId, [supplierA, supplierB]);
      await page.getByTestId('mr-publish').click();
      await expect(page.getByTestId('mr-publish-done')).toHaveText('The route MR-A is published.');
      expect(sent(stub, '/milk-run-routes')?.body).toMatchObject({
        code: 'MR-A', plantTerminalId: plantId, days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], departureTime: '06:30',
        runRate: 1200, perStopFee: 150,
        stops: [{ terminalId: supplierA, windowOpenMin: 0, windowCloseMin: 20, plannedDwellMin: 10 },
          { terminalId: supplierB, windowOpenMin: 30, windowCloseMin: 50, plannedDwellMin: 10 }],
      });
    });

    await test.step('runs are planned from a day', async () => {
      await page.getByTestId('mr-gen-from').fill('2026-12-01');
      await page.getByTestId('mr-gen-days').fill('2');
      await page.getByTestId('mr-generate').click();
      await expect(page.getByTestId('mr-generate-done')).toHaveText('2 runs planned.');
      expect(sent(stub, '/milk-runs/generate')?.body).toMatchObject({ fromDate: '2026-12-01', days: 2 });
      await expect(page.getByTestId('mr-run')).toHaveCount(2);
    });

    await test.step('planning the same days again adds nothing', async () => {
      await page.getByTestId('mr-generate').click();
      await expect(page.getByTestId('mr-generate-done')).toHaveText('0 runs planned.');
      await expect(page.getByTestId('mr-run')).toHaveCount(2);
    });

    const runId = stub.runs[0].id;
    await test.step('a run is selected and given a trip', async () => {
      await page.locator(`[data-testid="mr-run"][data-id="${runId}"]`).getByTestId('mr-run-select').click();
      await expect(page.getByTestId('mr-run-panel')).toHaveAttribute('data-id', runId);
      await expect(page.getByTestId('mr-run-status')).toHaveAttribute('data-value', 'PLANNED');
      await expect(page.getByTestId('mr-stops-hint')).toBeVisible();
      await expect(page.getByTestId('mr-stop-arrive')).toHaveCount(0);
      await page.getByTestId('mr-trip-id').fill(tripId);
      await page.getByTestId('mr-assign-trip').click();
      await expect(page.getByTestId('mr-run-done')).toHaveText('The trip is assigned to the run.');
      await expect(page.getByTestId('mr-run-trip')).toHaveText('T-900');
      expect(sent(stub, '/trip')?.body).toEqual({ tripId });
    });

    await test.step('once under way, the first stop is reached, loaded and left', async () => {
      stub.startRun(runId);
      await page.locator(`[data-testid="mr-run"][data-id="${runId}"]`).getByTestId('mr-run-select').click();
      await expect(page.getByTestId('mr-run-status')).toHaveAttribute('data-value', 'IN_PROGRESS');
      const first = page.locator('[data-testid="mr-stop"][data-seq="1"]');
      await first.getByTestId('mr-stop-arrive').click();
      await expect(first.getByTestId('mr-stop-status')).toHaveAttribute('data-value', 'ARRIVED');
      await first.getByTestId('mr-stop-qty').fill('450');
      await first.getByTestId('mr-stop-pickup').click();
      await expect(first.getByTestId('mr-stop-picked')).toHaveText('450');
      expect(sent(stub, '/stops/1/pickup')?.body).toEqual({ quantityKg: 450 });
      await first.getByTestId('mr-stop-depart').click();
      await expect(first.getByTestId('mr-stop-status')).toHaveAttribute('data-value', 'DEPARTED');
      await expect(page.getByTestId('mr-run-visited')).toHaveText('1');
    });

    await test.step('the second stop is skipped with a reason, and the run completed', async () => {
      const second = page.locator('[data-testid="mr-stop"][data-seq="2"]');
      await second.getByTestId('mr-stop-skip-reason').fill('the supplier was closed');
      await second.getByTestId('mr-stop-skip').click();
      await expect(second.getByTestId('mr-stop-status')).toHaveAttribute('data-value', 'SKIPPED');
      expect(sent(stub, '/stops/2/skip')?.body).toEqual({ reason: 'the supplier was closed' });
      await expect(page.getByTestId('mr-run-skipped')).toHaveText('1');
      await page.getByTestId('mr-complete').click();
      await expect(page.getByTestId('mr-run-status')).toHaveAttribute('data-value', 'COMPLETED');
      await expect(page.getByTestId('mr-run-done')).toHaveText('The run is complete.');
      await expect(page.getByTestId('mr-run-charge')).not.toHaveText('—');
      await expect(page.getByTestId('mr-complete')).toHaveCount(0);
      await expect(page.getByTestId('mr-trip-form')).toHaveCount(0);
    });
  });

  test('a refused route names its code and publishes nothing', async ({ page }) => {
    const stub = await stubMilk(page);
    await signIn(page);
    await openMilkDesk(page);

    await test.step('the same supplier twice is refused', async () => {
      await fillRoute(page, 'MR-B', plantId, [supplierA, supplierA]);
      await page.getByTestId('mr-publish').click();
      await expect(page.getByTestId('mr-publish-refusal')).toHaveText('milk-run-supplier-repeated');
      await expect(page.getByTestId('mr-publish-done')).toHaveCount(0);
      expect(stub.routes).toHaveLength(0);
    });

    await test.step('the plant among the suppliers is refused', async () => {
      await page.getByTestId('mr-stop-terminal').nth(1).fill(plantId);
      await page.getByTestId('mr-publish').click();
      await expect(page.getByTestId('mr-publish-refusal')).toHaveText('milk-run-plant-as-stop');
      expect(stub.routes).toHaveLength(0);
    });

    await test.step('a supplier can be taken off the list again', async () => {
      await page.getByTestId('mr-stop-remove').first().click();
      await expect(page.getByTestId('mr-stop-row')).toHaveCount(1);
    });
  });

  test('a trip that is cancelled or busy is refused by code, and a stop needs its quantity or reason', async ({ page }) => {
    const stub = await stubMilk(page);
    await signIn(page);
    await openMilkDesk(page);
    await fillRoute(page, 'MR-C', plantId, [supplierA, supplierB]);
    await page.getByTestId('mr-publish').click();
    await expect(page.getByTestId('mr-publish-done')).toBeVisible();
    await page.getByTestId('mr-generate').click();
    await expect(page.getByTestId('mr-run')).toHaveCount(7);
    const runId = stub.runs[0].id;
    await page.locator(`[data-testid="mr-run"][data-id="${runId}"]`).getByTestId('mr-run-select').click();

    await page.getByTestId('mr-trip-id').fill(cancelledTripId);
    await page.getByTestId('mr-assign-trip').click();
    await expect(page.getByTestId('mr-run-refusal')).toHaveText('trip-cancelled');
    await page.getByTestId('mr-trip-id').fill(busyTripId);
    await page.getByTestId('mr-assign-trip').click();
    await expect(page.getByTestId('mr-run-refusal')).toHaveText('trip-already-carries-a-circuit');
    await expect(page.getByTestId('mr-run-trip')).toHaveText('—');

    stub.startRun(runId);
    await page.locator(`[data-testid="mr-run"][data-id="${runId}"]`).getByTestId('mr-run-select').click();
    const first = page.locator('[data-testid="mr-stop"][data-seq="1"]');
    await first.getByTestId('mr-stop-skip').click();
    await expect(page.getByTestId('mr-stop-error')).toContainText('Say why the stop is skipped.');
    expect(sent(stub, '/stops/1/skip')).toBeUndefined();
    await first.getByTestId('mr-stop-arrive').click();
    await first.getByTestId('mr-stop-pickup').click();
    await expect(page.getByTestId('mr-stop-error')).toContainText('Enter the quantity picked up.');
    expect(sent(stub, '/stops/1/pickup')).toBeUndefined();
  });

  test('without the right to execute runs the desk offers no stop or trip actions', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    const stub = await stubMilk(page);
    stub.routes.push({ id: 'route-1', code: 'MR-E' });
    stub.runs.push({
      id: 'run-1', routeId: 'route-1', routeCode: 'MR-E', runDate: '2026-12-01', status: 'IN_PROGRESS', stopsVisited: 0,
      stopsSkipped: 0, chargeAmount: 0, totalPickedUpKg: 0,
      stops: [{ sequence: 1, terminalId: supplierA, terminalCode: 'SUP-A', status: 'PENDING' }] });
    await stubMilkSession(page, ['MILKRUN_EXECUTE']);
    await signIn(page);
    await openMilkDesk(page);
    await expect(page.getByTestId('mr-publish-form')).toBeVisible();
    await page.getByTestId('mr-run-select').click();
    await expect(page.getByTestId('mr-run-panel')).toBeVisible();
    await expect(page.getByTestId('mr-stop')).toHaveCount(1);
    await expect(page.getByTestId('mr-trip-form')).toHaveCount(0);
    await expect(page.getByTestId('mr-complete')).toHaveCount(0);
    await expect(page.getByTestId('mr-stop-arrive')).toHaveCount(0);
  });

  test('without the right to plan, no route or generation form is offered', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubMilk(page);
    await stubMilkSession(page, ['MILKRUN_PLAN']);
    await signIn(page);
    await openMilkDesk(page);
    await expect(page.getByTestId('mr-publish-form')).toHaveCount(0);
    await expect(page.getByTestId('mr-generate-form')).toHaveCount(0);
    await expect(page.getByTestId('mr-runs-form')).toBeVisible();
  });
});

test.describe('itinerary desk', () => {
  /** Fills one leg of the plan form. */
  async function fillLeg(page: Page, i: number, mode: string, from: string, to: string) {
    await page.getByTestId('it-leg-mode').nth(i).selectOption(mode);
    await page.getByTestId('it-leg-from').nth(i).fill(from);
    await page.getByTestId('it-leg-to').nth(i).fill(to);
    await page.getByTestId('it-leg-carrier').nth(i).fill(carrierId);
    await page.getByTestId('it-leg-departs').nth(i).fill(`2026-12-0${i + 1}T06:00`);
    await page.getByTestId('it-leg-arrives').nth(i).fill(`2026-12-0${i + 1}T18:00`);
    if (mode === 'RAIL') {
      await page.getByTestId('it-leg-unit-type').nth(0).selectOption('WAGON');
      await page.getByTestId('it-leg-unit-count').nth(0).fill('10');
      await page.getByTestId('it-leg-booking').nth(0).fill('BK-1');
    }
  }

  test('a road, rail, road itinerary is planned and its rail leg taken through every milestone', async ({ page }) => {
    const stub = await stubMilk(page);
    await signIn(page);
    await openItineraryDesk(page);

    await test.step('three legs are planned', async () => {
      await page.getByTestId('it-shipment').fill('SHIP-1');
      await fillLeg(page, 0, 'ROAD', supplierA, railheadA);
      await page.getByTestId('it-leg-add').click();
      await fillLeg(page, 1, 'RAIL', railheadA, railheadB);
      await page.getByTestId('it-leg-add').click();
      await fillLeg(page, 2, 'ROAD', railheadB, supplierB);
      await page.getByTestId('it-plan').click();
      await expect(page.getByTestId('it-panel')).toBeVisible();
      await expect(page.getByTestId('it-done')).toHaveText('The itinerary is planned.');
      await expect(page.getByTestId('it-shipment-ref')).toHaveText('SHIP-1');
      await expect(page.getByTestId('it-status')).toHaveAttribute('data-value', 'PLANNED');
      await expect(page.getByTestId('it-leg')).toHaveCount(3);
      await expect(page.getByTestId('it-item')).toHaveCount(1);
      const body = sent(stub, '/itineraries')?.body as { shipmentRef: string; legs: Array<Record<string, unknown>> };
      expect(body.shipmentRef).toBe('SHIP-1');
      expect(body.legs.map((l) => l.mode)).toEqual(['ROAD', 'RAIL', 'ROAD']);
      expect(body.legs[1]).toMatchObject({
        fromTerminalId: railheadA, toTerminalId: railheadB, carrierPartnerId: carrierId, unitType: 'WAGON', unitCount: 10,
        bookingRef: 'BK-1',
      });
    });

    await test.step('a road leg has no milestone button; the rail leg takes them one at a time', async () => {
      const road = page.locator('[data-testid="it-leg"][data-seq="1"]');
      const rail = page.locator('[data-testid="it-leg"][data-seq="2"]');
      await expect(road.getByTestId('it-leg-next')).toHaveCount(0);
      for (const milestone of ['PLACED', 'LOADED', 'DEPARTED', 'ARRIVED', 'RELEASED']) {
        await rail.getByTestId('it-leg-next').click();
        await expect(rail.getByTestId('it-leg-status')).toHaveAttribute('data-value', milestone);
        expect(sent(stub, '/legs/2/milestones')?.body).toEqual({ milestone });
      }
      await expect(page.getByTestId('it-done')).toHaveText('Leg 2 is released.');
      await expect(rail.getByTestId('it-leg-next')).toHaveCount(0);
    });
  });

  test('a plan whose legs do not join is refused by code and nothing is planned', async ({ page }) => {
    const stub = await stubMilk(page);
    await signIn(page);
    await openItineraryDesk(page);
    await page.getByTestId('it-shipment').fill('SHIP-2');
    await fillLeg(page, 0, 'ROAD', supplierA, railheadA);
    await page.getByTestId('it-leg-add').click();
    await fillLeg(page, 1, 'ROAD', railheadB, supplierB);
    await page.getByTestId('it-plan').click();
    await expect(page.getByTestId('it-plan-refusal')).toHaveText('itinerary-not-contiguous');
    await expect(page.getByTestId('it-panel')).toHaveCount(0);
    expect(stub.itineraries).toHaveLength(0);
  });

  test('without the right to track, rail legs offer no milestone button', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    const stub = await stubMilk(page);
    stub.itineraries.push({
      id: 'it-1', itineraryNo: 'IT-1', shipmentRef: 'SHIP-3', status: 'PLANNED', currency: 'INR', totalCost: 0,
      legs: [{
        id: 'leg-1', seq: 1, mode: 'RAIL', status: 'PLANNED', fromTerminalId: railheadA, fromTerminalCode: 'RH-A',
        toTerminalId: railheadB, toTerminalCode: 'RH-B', plannedDepartureAt: '2026-12-01T06:00:00Z',
        plannedArrivalAt: '2026-12-01T18:00:00Z', carrierCode: 'CARRIER' }] });
    await stubMilkSession(page, ['MULTIMODAL_TRACK']);
    await signIn(page);
    await openItineraryDesk(page);
    await expect(page.getByTestId('it-plan-form')).toBeVisible();
    await page.getByTestId('it-select').click();
    await expect(page.getByTestId('it-leg')).toHaveCount(1);
    await expect(page.getByTestId('it-leg-next')).toHaveCount(0);
  });

  test('without the right to plan, no plan form is offered', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubMilk(page);
    await stubMilkSession(page, ['MULTIMODAL_PLAN']);
    await signIn(page);
    await openItineraryDesk(page);
    await expect(page.getByTestId('it-plan-form')).toHaveCount(0);
    await expect(page.getByTestId('it-list-empty')).toBeVisible();
  });
});
