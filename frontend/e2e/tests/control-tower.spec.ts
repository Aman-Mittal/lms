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
  apiSession, newReportingVehicle, openScreen, signIn, stubTiles, tag, withoutPermissions,
} from './support';
import {
  adminUserId, closeElsewhere, detectorSettings, newTicket, newTrip, resolveElsewhere, restoreDetectorSettings,
} from './support-control';

async function openDesk(page: Page): Promise<void> {
  await openScreen(page, 'nav-exception-desk', 'Exception desk');
}

async function openTicket(page: Page, ticketId: string) {
  await page.getByTestId('ticket-id').fill(ticketId);
  await page.getByTestId('open-ticket').getByRole('button', { name: 'Open' }).click();
  const panel = page.getByTestId('ticket-panel');
  await expect(panel).toBeVisible();
  return panel;
}

test.describe('exception desk', () => {
  test('a ticket is assigned, investigated, resolved and closed, and its history shows every step', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const trip = await newTrip(request, session, tag(info));
    const ticket = await newTicket(request, session, trip.tripId);
    const assignee = await adminUserId(request, session);
    await stubTiles(page);
    await signIn(page);
    await openDesk(page);

    const panel = await openTicket(page, ticket.id);
    await expect(panel.getByRole('heading', { name: ticket.ticketNo })).toBeVisible();
    await expect(panel.getByTestId('ticket-status')).toHaveText('RAISED');
    await expect(panel.getByTestId('ticket-severity')).toHaveText('P3');

    await test.step('assign it', async () => {
      await panel.getByTestId('assignee').selectOption(assignee);
      await panel.getByLabel('Assignment note').fill('Yours to chase');
      await panel.getByTestId('assign-ticket').getByRole('button', { name: 'Assign' }).click();
      await expect(panel.getByTestId('ticket-status')).toHaveText('ASSIGNED');
      await expect(panel.getByTestId('ticket-assignee')).not.toHaveText('—');
    });

    await test.step('investigate and resolve it', async () => {
      await panel.getByTestId('act-investigate').click();
      await expect(panel.getByTestId('ticket-status')).toHaveText('UNDER_INVESTIGATION');
      await panel.getByLabel('Resolution').fill('Consignee resent the invoice');
      await panel.getByTestId('resolve-ticket').getByRole('button', { name: 'Resolve' }).click();
      await expect(panel.getByTestId('ticket-status')).toHaveText('RESOLVED');
      await expect(panel.getByTestId('ticket-resolution')).toHaveText('Consignee resent the invoice');
    });

    await test.step('close it; nothing more is offered', async () => {
      await panel.getByLabel('Closing note').fill('Checked');
      await panel.getByTestId('close-ticket').getByRole('button', { name: 'Close ticket' }).click();
      await expect(panel.getByTestId('ticket-status')).toHaveText('CLOSED');
      await expect(panel.getByTestId('ticket-done')).toHaveText('Closed.');
      await expect(panel.getByTestId('reopen-ticket')).toHaveCount(0);
      await expect(panel.getByTestId('assign-ticket')).toHaveCount(0);
    });

    const steps = panel.getByTestId('history-row');
    await expect(steps).toHaveCount(5);
    for (const [i, action] of ['RAISED', 'ASSIGNED', 'INVESTIGATION_STARTED', 'RESOLVED', 'CLOSED'].entries()) {
      await expect(steps.nth(i)).toContainText(action);
    }
    await expect(panel.getByTestId('ticket-trip-route')).toHaveAttribute('href', new RegExp(`trip=${trip.tripId}`));
  });

  test('a resolved ticket is sent back for investigation, but not once another desk has closed it', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const trip = await newTrip(request, session, tag(info));
    const reopened = await newTicket(request, session, trip.tripId);
    const closedElsewhere = await newTicket(request, session, trip.tripId);
    await resolveElsewhere(request, session, reopened.id);
    await resolveElsewhere(request, session, closedElsewhere.id);
    await stubTiles(page);
    await signIn(page);
    await openDesk(page);

    await test.step('reopening a resolved ticket puts it back under investigation', async () => {
      const panel = await openTicket(page, reopened.id);
      await expect(panel.getByTestId('ticket-status')).toHaveText('RESOLVED');
      await panel.getByLabel('Reason to reopen').fill('The invoice is still wrong');
      await panel.getByTestId('reopen-ticket').getByRole('button', { name: 'Reopen' }).click();
      await expect(panel.getByTestId('ticket-status')).toHaveText('UNDER_INVESTIGATION');
      await expect(panel.getByTestId('history-row').last()).toContainText('REOPENED');
    });

    await test.step('a ticket closed meanwhile cannot be reopened, and the refusal says why', async () => {
      const panel = await openTicket(page, closedElsewhere.id);
      await expect(panel.getByTestId('ticket-status')).toHaveText('RESOLVED');
      await closeElsewhere(request, session, closedElsewhere.id);
      await panel.getByLabel('Reason to reopen').fill('Not convinced');
      await panel.getByTestId('reopen-ticket').getByRole('button', { name: 'Reopen' }).click();
      await expect(panel.getByTestId('exception-refusal')).toHaveText('exception-illegal-transition');
      await expect(panel.getByTestId('ticket-status')).toHaveText('CLOSED');
      await expect(panel.getByTestId('reopen-ticket')).toHaveCount(0);
    });
  });

  test('a detector setting is changed and kept', async ({ page, request }) => {
    const session = await apiSession(request);
    const before = await detectorSettings(request, session);
    const changed = before.etaSlipMinutes >= 10000 ? before.etaSlipMinutes - 7 : before.etaSlipMinutes + 7;
    try {
      await stubTiles(page);
      await signIn(page);
      await openDesk(page);
      const detectors = page.getByTestId('detector-settings');
      await expect(detectors.getByTestId('eta-slip')).toHaveText(`${before.etaSlipMinutes} min`);

      await detectors.getByTestId('eta-slip-input').fill(String(changed));
      await detectors.getByRole('button', { name: 'Save' }).click();
      await expect(detectors.getByTestId('detectors-saved')).toBeVisible();
      await expect(detectors.getByTestId('eta-slip')).toHaveText(`${changed} min`);
      await expect(detectors.getByTestId('temperature-excursion')).toHaveText(`${before.temperatureExcursionMinutes} min`);
      await expect(detectors.getByTestId('detector-customised')).toHaveText('Set for this tenant');

      // Away and back: the desk reads the settings again from the server.
      await page.getByTestId('nav-delivery').click();
      await openDesk(page);
      await expect(page.getByTestId('detector-settings').getByTestId('eta-slip')).toHaveText(`${changed} min`);
    } finally {
      await restoreDetectorSettings(request, session, before);
    }
  });

  test('a trip is held to an arrival time and a temperature band, then let go', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const trip = await newTrip(request, session, tag(info));
    await stubTiles(page);
    await signIn(page);
    await openDesk(page);

    const desk = page.getByTestId('trip-desk');
    await desk.getByTestId('watch-trip-id').fill(trip.tripId);
    await desk.getByRole('button', { name: 'Open trip' }).click();
    const panel = desk.getByTestId('trip-panel');
    await expect(panel.getByTestId('no-progress')).toBeVisible();
    await expect(panel.getByTestId('no-watch')).toBeVisible();
    await expect(panel.getByTestId('trip-route-link')).toHaveAttribute('href', new RegExp(`trip=${trip.tripId}`));

    const arrival = new Date(Date.now() + 2 * 86_400_000);
    arrival.setSeconds(0, 0);
    const local = new Date(arrival.getTime() - arrival.getTimezoneOffset() * 60_000).toISOString().slice(0, 16);
    await panel.getByTestId('watch-arrival-input').fill(local);
    await panel.getByLabel('Minimum temperature').fill('2');
    await panel.getByLabel('Maximum temperature').fill('8');
    await panel.getByRole('button', { name: 'Save watch' }).click();
    await expect(panel.getByTestId('watch-done')).toHaveText('Watch saved.');
    await expect(panel.getByTestId('watch-band')).toContainText(/^2(\.0+)? – 8(\.0+)? °C$/);
    await expect(panel.getByTestId('watch-arrival')).toHaveText(arrival.toISOString().slice(0, 16).replace('T', ' '));

    await panel.getByTestId('clear-watch').click();
    await expect(panel.getByTestId('watch-done')).toHaveText('Watch cleared.');
    await expect(panel.getByTestId('no-watch')).toBeVisible();
  });

  test('the fleet shows where vehicles reported from and which trips are off their route', async ({ page, request }, info) => {
    const salt = tag(info);
    const session = await apiSession(request);
    const vehicle = await newReportingVehicle(request, session, salt);
    const trip = await newTrip(request, session, salt);
    // A real deviation needs a planned corridor and a vehicle driving out of it; the
    // detector has its own tests, so here the list is answered for the screen.
    await page.route('**/api/v1/telematics/deviations', (route) => route.fulfill({ json: [{
      id: crypto.randomUUID(), tripId: trip.tripId, tripNo: trip.tripNo, detectedAt: new Date().toISOString(),
      distanceM: 1240, corridorM: 500, lat: 19.1, lon: 73.2,
    }] }));
    const tiles = await stubTiles(page);
    await signIn(page);
    await openDesk(page);

    const fleet = page.getByTestId('fleet');
    await expect(fleet.locator(`[data-testid="map-feature"][data-id="${vehicle.id}"]`)).toContainText(vehicle.registrationNo);
    await expect.poll(() => tiles.count()).toBeGreaterThan(0);
    const deviation = fleet.getByTestId('deviation-row');
    await expect(deviation).toHaveCount(1);
    await expect(deviation).toContainText(trip.tripNo);
    await expect(deviation).toContainText('1240');

    await deviation.getByRole('button', { name: 'Open trip' }).click();
    await expect(page.getByTestId('trip-panel').getByTestId('no-watch')).toBeVisible();
  });
});

test.describe('what each person is offered', () => {
  test('without the rights to raise, manage or close, tickets and watches are read but no step is offered', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const trip = await newTrip(request, session, tag(info));
    const ticket = await newTicket(request, session, trip.tripId);
    await withoutPermissions(page, ['EXCEPTION_RAISE', 'EXCEPTION_MANAGE', 'EXCEPTION_CLOSE']);
    await stubTiles(page);
    await signIn(page);
    await openDesk(page);

    await expect(page.getByTestId('raise-ticket')).toHaveCount(0);
    const panel = await openTicket(page, ticket.id);
    await expect(panel.getByTestId('ticket-status')).toHaveText('RAISED');
    await expect(panel.getByTestId('assign-ticket')).toHaveCount(0);
    await expect(panel.getByTestId('history-row')).toHaveCount(1);
    await expect(page.getByTestId('detector-settings').getByTestId('eta-slip')).toBeVisible();
    await expect(page.getByTestId('save-detectors')).toHaveCount(0);

    const desk = page.getByTestId('trip-desk');
    await desk.getByTestId('watch-trip-id').fill(trip.tripId);
    await desk.getByRole('button', { name: 'Open trip' }).click();
    await expect(desk.getByTestId('no-watch')).toBeVisible();
    await expect(page.getByTestId('set-watch')).toHaveCount(0);
  });

  test('without the right to read exceptions, the desk is not in the sidebar', async ({ page }) => {
    await withoutPermissions(page, ['EXCEPTION_READ']);
    await signIn(page);
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-exception-desk')).toHaveCount(0);
  });

  test('a ticket that does not exist is reported, not shown', async ({ page }) => {
    await stubTiles(page);
    await signIn(page);
    await openDesk(page);
    await page.getByTestId('ticket-id').fill(crypto.randomUUID());
    await page.getByTestId('open-ticket').getByRole('button', { name: 'Open' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('ticket-panel')).toHaveCount(0);
  });
});
