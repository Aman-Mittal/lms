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
  awardedLoadId, consignmentId, draftLoadId, driverId, inactiveVehicleId, stubTripSession, stubTrips, terminalId,
  vehicleId, type TripStub,
} from './support-trips';

/*
 * The trips and loads lifecycle, in both consoles: raise a trip, assign it, move it through loading, dispatch,
 * transit, arrival and completion, tick off its stops, cancel it; open, plan and fill a load. The API is stubbed so
 * the same assertions run whatever the demo tenant holds; the stub keeps the server's rules and the spec checks what
 * each console sends. Set E2E_STUB_SESSION=1 to stub the sign-in too, for a run with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubTripSession(page);
  }
});

async function openDesk(page: Page) {
  await page.getByTestId('nav-trip-desk').click();
  await expect(page.getByRole('heading', { name: 'Trip desk', level: 1 })).toBeVisible();
}

async function openTrip(page: Page, id: string) {
  await page.getByTestId('trip-open-id').fill(id);
  await page.getByTestId('trip-open').click();
  await expect(page.getByTestId('trip-panel')).toHaveAttribute('data-id', id);
}

const status = (page: Page) => page.getByTestId('trip-status');

/** The last request the stub saw whose path ends as given. */
function sent(stub: TripStub, ending: string) {
  return stub.changes.filter((c) => c.path.endsWith(ending)).at(-1);
}

test.describe('trip desk', () => {
  test('a trip is raised, assigned, loaded, dispatched, driven, arrived and completed', async ({ page }) => {
    const stub = await stubTrips(page);
    await signIn(page);
    await openDesk(page);

    await test.step('raising a trip against an awarded load opens it', async () => {
      await page.getByTestId('trip-no-input').fill('T-100');
      await page.getByTestId('trip-load-id').fill(awardedLoadId);
      await page.getByTestId('trip-raise').click();
      await expect(page.getByTestId('trip-no')).toHaveText('T-100');
      await expect(status(page)).toHaveAttribute('data-value', 'PLANNED');
      await expect(page.getByTestId('trip-done')).toHaveText('The trip is raised.');
      await expect(page.getByTestId('trip-load')).toHaveText('LOAD-AWARDED');
      expect(sent(stub, '/trips')?.body).toMatchObject({ tripNo: 'T-100', loadId: awardedLoadId });
      await expect(page.getByTestId('trip-stop')).toHaveCount(2);
    });
    const id = stub.trips[0].id;

    await test.step('the vehicle and driver are assigned', async () => {
      await page.getByTestId('trip-vehicle-id').fill(vehicleId);
      await page.getByTestId('trip-driver-id').fill(driverId);
      await page.getByTestId('trip-assign').click();
      await expect(status(page)).toHaveAttribute('data-value', 'ASSIGNED');
      await expect(page.getByTestId('trip-vehicle')).toHaveText('MH12AB1234');
      await expect(page.getByTestId('trip-driver')).toHaveText('Asha Driver');
      expect(sent(stub, '/assignment')?.body).toMatchObject({ vehicleId, driverId });
      await expect(page.getByTestId('trip-gate-hint')).toBeVisible();
      await expect(page.getByTestId('trip-assign-form')).toHaveCount(0);
    });

    await test.step('after the gate has let it in, it is loaded and dispatched', async () => {
      stub.gateIn(id);
      await openTrip(page, id);
      await expect(status(page)).toHaveAttribute('data-value', 'AT_ORIGIN');
      await page.getByTestId('trip-loaded').click();
      await expect(status(page)).toHaveAttribute('data-value', 'LOADED');
      await page.getByTestId('trip-dispatch').click();
      await expect(status(page)).toHaveAttribute('data-value', 'DISPATCHED');
      await expect(page.getByTestId('trip-dispatched')).not.toHaveText('—');
      await expect(page.getByTestId('trip-cancel-form')).toHaveCount(0);
    });

    await test.step('on the road, its stops are reached and left in turn', async () => {
      await page.getByTestId('trip-in-transit').click();
      await expect(status(page)).toHaveAttribute('data-value', 'IN_TRANSIT');
      const first = page.locator('[data-testid="trip-stop"][data-seq="1"]');
      await first.getByTestId('trip-stop-arrive').click();
      await expect(first.getByTestId('trip-stop-status')).toHaveAttribute('data-value', 'ARRIVED');
      await first.getByTestId('trip-stop-depart').click();
      await expect(first.getByTestId('trip-stop-status')).toHaveAttribute('data-value', 'DEPARTED');
      const second = page.locator('[data-testid="trip-stop"][data-seq="2"]');
      await second.getByTestId('trip-stop-arrive').click();
      await expect(second.getByTestId('trip-stop-status')).toHaveAttribute('data-value', 'ARRIVED');
    });

    await test.step('arrival is recorded and the trip completed', async () => {
      await page.getByTestId('trip-arrival').click();
      await expect(status(page)).toHaveAttribute('data-value', 'AT_DESTINATION');
      await expect(page.getByTestId('trip-arrived')).not.toHaveText('—');
      await page.getByTestId('trip-complete').click();
      await expect(status(page)).toHaveAttribute('data-value', 'COMPLETED');
      await expect(page.getByTestId('trip-done')).toHaveText('The trip is complete.');
      await expect(page.getByTestId('trip-completed')).not.toHaveText('—');
      await expect(page.getByTestId('trip-complete')).toHaveCount(0);
    });
  });

  test('a refused raise or assignment names its code and changes nothing', async ({ page }) => {
    const stub = await stubTrips(page);
    await signIn(page);
    await openDesk(page);

    await page.getByTestId('trip-no-input').fill('T-200');
    await page.getByTestId('trip-load-id').fill(draftLoadId);
    await page.getByTestId('trip-raise').click();
    await expect(page.getByTestId('trip-raise-refusal')).toHaveText('load-not-awarded');
    await expect(page.getByTestId('trip-panel')).toHaveCount(0);
    expect(stub.trips).toHaveLength(0);

    await test.step('a second trip on the same load is refused', async () => {
      await page.getByTestId('trip-load-id').fill(awardedLoadId);
      await page.getByTestId('trip-raise').click();
      await expect(page.getByTestId('trip-panel')).toBeVisible();
      await page.getByTestId('trip-no-input').fill('T-201');
      await page.getByTestId('trip-load-id').fill(awardedLoadId);
      await page.getByTestId('trip-raise').click();
      await expect(page.getByTestId('trip-raise-refusal')).toHaveText('load-already-has-trip');
      expect(stub.trips).toHaveLength(1);
    });

    await test.step('a switched-off vehicle is refused by code and the trip stays planned', async () => {
      await page.getByTestId('trip-vehicle-id').fill(inactiveVehicleId);
      await page.getByTestId('trip-assign').click();
      await expect(page.getByTestId('trip-refusal')).toHaveText('vehicle-inactive');
      await expect(status(page)).toHaveAttribute('data-value', 'PLANNED');
    });
  });

  test('a dispatch the compliance gate refuses shows why, and goes through once cleared', async ({ page }) => {
    const stub = await stubTrips(page);
    const trip = stub.seedTrip({ tripNo: 'T-300', status: 'LOADED', blockDispatch: true });
    await signIn(page);
    await openDesk(page);
    await openTrip(page, trip.id);

    await page.getByTestId('trip-dispatch').click();
    await expect(page.getByTestId('trip-refusal')).toHaveText('dispatch-blocked');
    await expect(page.getByTestId('trip-error')).toContainText('e-way bill missing');
    await expect(status(page)).toHaveAttribute('data-value', 'LOADED');
    await expect(page.getByTestId('trip-done')).toHaveCount(0);

    trip.blockDispatch = false;
    await page.getByTestId('trip-dispatch').click();
    await expect(status(page)).toHaveAttribute('data-value', 'DISPATCHED');
    await expect(page.getByTestId('trip-error')).toHaveCount(0);
  });

  test('a trip is cancelled with a reason, and only while it is still in the yard', async ({ page }) => {
    const stub = await stubTrips(page);
    const planned = stub.seedTrip({ tripNo: 'T-400' });
    const away = stub.seedTrip({ tripNo: 'T-401', status: 'DISPATCHED' });
    await signIn(page);
    await openDesk(page);
    await openTrip(page, planned.id);

    await test.step('without a reason nothing is sent', async () => {
      await page.getByTestId('trip-cancel').click();
      expect(sent(stub, '/cancellation')).toBeUndefined();
      await expect(status(page)).toHaveAttribute('data-value', 'PLANNED');
    });

    await test.step('with a reason it is cancelled and says why', async () => {
      await page.getByTestId('trip-cancel-reason').fill('the customer withdrew');
      await page.getByTestId('trip-cancel').click();
      await expect(status(page)).toHaveAttribute('data-value', 'CANCELLED');
      await expect(page.getByTestId('trip-cancelled-reason')).toHaveText('the customer withdrew');
      expect(sent(stub, '/cancellation')?.body).toMatchObject({ reason: 'the customer withdrew' });
      await expect(page.getByTestId('trip-cancel-form')).toHaveCount(0);
    });

    await test.step('a trip on the road is not offered the cancel at all', async () => {
      await openTrip(page, away.id);
      await expect(page.getByTestId('trip-cancel-form')).toHaveCount(0);
      await expect(page.getByTestId('trip-in-transit')).toBeVisible();
    });
  });

  test('without the right to execute trips the desk is read-only', async ({ page }) => {
    const stub = await stubTrips(page);
    const trip = stub.seedTrip({ tripNo: 'T-500', status: 'LOADED' });
    await stubTripSession(page, ['TRIP_EXECUTE']);
    await signIn(page);
    await openDesk(page);
    await openTrip(page, trip.id);

    await expect(page.getByTestId('trip-raise-form')).toHaveCount(0);
    await expect(page.getByTestId('trip-dispatch')).toHaveCount(0);
    await expect(page.getByTestId('trip-cancel-form')).toHaveCount(0);
    await expect(page.getByTestId('trip-stop')).toHaveCount(2);
    await expect(page.getByTestId('trip-stop-arrive')).toHaveCount(0);
  });
});

test.describe('opening and planning a load', () => {
  test('a load is opened, planned only once it has a consignment, and takes one by reference', async ({ page }) => {
    const stub = await stubTrips(page);
    await signIn(page);
    await page.getByTestId('nav-load-building').click();
    await expect(page.getByRole('heading', { name: 'Load building', level: 1 })).toBeVisible();

    await test.step('a new load is opened and shown', async () => {
      await page.getByTestId('new-load-no').fill('LOAD-NEW');
      await page.getByTestId('new-load-origin').fill(terminalId);
      await page.getByTestId('new-load-vehicle').fill(vehicleId);
      await page.getByTestId('new-load-open').click();
      await expect(page.getByTestId('load-panel')).toBeVisible();
      await expect(page.getByRole('heading', { name: 'LOAD-NEW', level: 2 })).toBeVisible();
      await expect(page.getByTestId('load-status')).toHaveAttribute('data-value', 'DRAFT');
      expect(sent(stub, '/loads')?.body).toMatchObject({ loadNo: 'LOAD-NEW', originTerminalId: terminalId, vehicleId });
    });

    await test.step('the same number again is refused by code', async () => {
      await page.getByTestId('new-load-no').fill('LOAD-NEW');
      await page.getByTestId('new-load-origin').fill(terminalId);
      await page.getByTestId('new-load-vehicle').fill(vehicleId);
      await page.getByTestId('new-load-open').click();
      await expect(page.getByTestId('new-load-error')).toContainText('load-no-taken');
      expect(stub.loads.filter((l) => l.loadNo === 'LOAD-NEW')).toHaveLength(1);
    });

    await test.step('an empty load cannot be planned', async () => {
      await page.getByTestId('plan-load').click();
      await expect(page.getByTestId('load-refusal')).toHaveText('load-is-empty');
      await expect(page.getByTestId('load-status')).toHaveAttribute('data-value', 'DRAFT');
    });

    await test.step('a consignment is added by reference', async () => {
      await page.getByTestId('assign-one-id').fill(consignmentId);
      await page.getByTestId('assign-one').click();
      await expect(page.getByTestId('assign-one-done')).toBeVisible();
      await expect(page.getByTestId('load-count')).toHaveText('1');
      await expect(page.getByTestId('aboard-row')).toHaveCount(1);
      expect(sent(stub, '/consignments/bulk')?.body).toEqual({ consignmentIds: [consignmentId] });
    });

    await test.step('the load is planned', async () => {
      await page.getByTestId('plan-load').click();
      await expect(page.getByTestId('load-done')).toHaveText('The load is planned and ready for sourcing.');
      await expect(page.getByTestId('load-status')).toHaveAttribute('data-value', 'PLANNED');
      await expect(page.getByTestId('plan-load')).toHaveCount(0);
      await expect(page.getByTestId('unplan-load')).toBeVisible();
    });
  });

  test('without the right to build loads, nothing to open or plan is offered', async ({ page }) => {
    const stub = await stubTrips(page);
    const load = stub.loads[1];
    await stubTripSession(page, ['LOAD_CREATE']);
    await signIn(page);
    await page.getByTestId('nav-load-building').click();
    await expect(page.getByTestId('new-load')).toHaveCount(0);
    await page.getByTestId('load-id').fill(load.id);
    await page.getByRole('button', { name: 'Open', exact: true }).click();
    await expect(page.getByTestId('load-panel')).toBeVisible();
    await expect(page.getByTestId('plan-load')).toHaveCount(0);
    await expect(page.getByTestId('assign-one-form')).toHaveCount(0);
  });
});
