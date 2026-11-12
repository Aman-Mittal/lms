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
import { apiSession, newSite, openScreen, signIn, tag, withoutPermissions } from './support';
import { newTrip } from './support-control';

/*
 * The yard gate and a trip's stops, in both consoles.
 *
 * The gate: a visitor's lorry is recorded in and shows as inside, a second entry for it is
 * refused with its problem code, its exit is recorded, and a gate pass is issued, listed and
 * cancelled with a reason. The stops: a trip's planned stops are shown and a person arrives at,
 * departs from and skips them.
 *
 * Each test builds its own site and registration number from the run's tag, so nothing collides
 * with an earlier run or the other console. The pass number is typed because the demo tenant has
 * no series numbering gate passes; where one exists the server ignores it, and the specs find the
 * pass by the vehicle rather than by its number.
 */

/** A `datetime-local` input's value for a moment: the browser's own zone, to the minute. */
function local(at: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

async function openGate(page: Page): Promise<void> {
  await openScreen(page, 'nav-gate', 'Gate desk');
}

test.describe('the gate desk', () => {
  test('a visitor lorry is recorded in, a second entry is refused, and its exit is recorded', async ({ page, request }, info) => {
    const salt = tag(info);
    const site = await newSite(request, await apiSession(request), `ZZ-GT-${salt}`, 'PLANT');
    const registration = `ZZ${salt}`.toUpperCase();
    await signIn(page);
    await openGate(page);

    const entry = page.getByTestId('gate-entry');
    await test.step('record the entry', async () => {
      await entry.getByTestId('entry-site').fill(site.id);
      await entry.getByTestId('entry-registration').fill(registration);
      await entry.getByTestId('entry-driver-name').fill('Visiting driver');
      await entry.getByTestId('entry-purpose').selectOption('VISITOR');
      await entry.getByTestId('entry-seals').fill('SEAL-1, SEAL-2');
      await entry.getByTestId('entry-weight').fill(`WB-${salt}`);
      await entry.getByTestId('entry-submit').click();
      await expect(page.getByTestId('gate-message')).toHaveText('Entry recorded.');
      const row = page.getByTestId('inside-row').filter({ hasText: registration });
      await expect(row).toContainText(site.code);
      await expect(row).toContainText('VISITOR');
      await expect(row).toContainText('Visiting driver');
    });

    await test.step('a second entry for the same vehicle is refused with its code', async () => {
      await entry.getByTestId('entry-site').fill(site.id);
      await entry.getByTestId('entry-registration').fill(registration);
      await entry.getByTestId('entry-purpose').selectOption('VISITOR');
      await entry.getByTestId('entry-submit').click();
      await expect(page.getByTestId('gate-refusal')).toHaveText('gate-entry-already-open');
      await expect(page.getByTestId('inside-row').filter({ hasText: registration })).toHaveCount(1);
    });

    await test.step('record the exit', async () => {
      const row = page.getByTestId('inside-row').filter({ hasText: registration });
      await row.getByTestId('visit-exit').click();
      const form = page.getByTestId('exit-form');
      await form.getByTestId('exit-seals').fill('SEAL-3');
      await form.getByTestId('exit-remarks').fill('Left empty');
      await form.getByTestId('exit-submit').click();
      await expect(page.getByTestId('gate-message')).toHaveText('Exit recorded.');
      await expect(page.getByTestId('inside-row').filter({ hasText: registration })).toHaveCount(0);
    });

    await test.step('the register shows the visit as left', async () => {
      await page.getByTestId('gate-tab-register').click();
      await page.getByTestId('register-search').fill(registration);
      await page.getByTestId('register-search-submit').click();
      const row = page.getByTestId('register-row').filter({ hasText: registration });
      await expect(row).toHaveCount(1);
      await expect(row).toContainText('CLOSED');
      await expect(row).toContainText('SEAL-3');
    });
  });

  test('a pass is issued, listed and cancelled with a reason', async ({ page, request }, info) => {
    const salt = tag(info);
    const site = await newSite(request, await apiSession(request), `ZZ-GP-${salt}`, 'PLANT');
    const registration = `ZP${salt}`.toUpperCase();
    await signIn(page);
    await openGate(page);
    await page.getByTestId('gate-tab-passes').click();

    const form = page.getByTestId('pass-issue');
    await form.getByTestId('pass-site').fill(site.id);
    await form.getByTestId('pass-registration').fill(registration);
    await form.getByTestId('pass-purpose').selectOption('VISITOR');
    await form.getByTestId('pass-from').fill(local(new Date(Date.now() - 5 * 60_000)));
    await form.getByTestId('pass-until').fill(local(new Date(Date.now() + 24 * 3600_000)));
    await form.getByTestId('pass-number').fill(`GP-${salt}`.toUpperCase());
    await form.getByTestId('pass-submit').click();
    await expect(page.getByTestId('gate-message')).toHaveText('Pass issued.');

    await page.getByTestId('pass-search').fill(registration);
    await page.getByTestId('pass-search-submit').click();
    const row = page.getByTestId('pass-row').filter({ hasText: registration });
    await expect(row).toHaveCount(1);
    await expect(row.getByTestId('pass-status')).toHaveText('ISSUED');
    await expect(row.getByTestId('pass-expired')).toHaveCount(0);

    await row.getByTestId('pass-cancel').click();
    await page.getByTestId('pass-cancel-reason').fill('Visit called off');
    await page.getByTestId('pass-cancel-confirm').click();
    await expect(page.getByTestId('gate-message')).toHaveText('Pass cancelled.');
    await expect(row.getByTestId('pass-status')).toHaveText('CANCELLED');
    await expect(row).toContainText('Visit called off');
    await expect(row.getByTestId('pass-cancel')).toHaveCount(0);
  });

  test('a pass can be checked for a vehicle and site', async ({ page, request }, info) => {
    const salt = tag(info);
    const site = await newSite(request, await apiSession(request), `ZZ-GC-${salt}`, 'PLANT');
    await signIn(page);
    await openGate(page);
    const form = page.getByTestId('pass-check');
    await form.getByTestId('check-site').fill(site.id);
    await form.getByTestId('check-registration').fill(`ZC${salt}`.toUpperCase());
    await form.getByTestId('check-submit').click();
    await expect(page.getByTestId('check-verdict')).toBeVisible();
  });

  test('without the gate rights there is no Gate entry, and without GATE_PASS_MANAGE no way to issue a pass', async ({ page }) => {
    await withoutPermissions(page, ['GATE_REGISTER', 'GATE_PASS_MANAGE']);
    await signIn(page);
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-gate')).toHaveCount(0);
  });

  test('with only GATE_REGISTER, entries are offered and passes cannot be issued', async ({ page }) => {
    await withoutPermissions(page, ['GATE_PASS_MANAGE']);
    await signIn(page);
    await openGate(page);
    await expect(page.getByTestId('gate-entry')).toBeVisible();
    await page.getByTestId('gate-tab-passes').click();
    await expect(page.getByTestId('gate-passes')).toBeVisible();
    await expect(page.getByTestId('pass-issue')).toHaveCount(0);
  });

  test('with only GATE_PASS_MANAGE, passes are offered and entries and exits are not', async ({ page }) => {
    await withoutPermissions(page, ['GATE_REGISTER']);
    await signIn(page);
    await openGate(page);
    await expect(page.getByTestId('gate-entry')).toHaveCount(0);
    await expect(page.getByTestId('visit-exit')).toHaveCount(0);
    await page.getByTestId('gate-tab-passes').click();
    await expect(page.getByTestId('pass-issue')).toBeVisible();
  });
});

test.describe('a trip\'s stops', () => {
  test('stops are shown in order and are arrived at, departed from and skipped', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const trip = await newTrip(request, session, tag(info));
    await signIn(page);
    await openScreen(page, 'nav-trip-route', 'Trip route');
    await page.getByTestId('trip-id').fill(trip.tripId);
    await page.getByRole('button', { name: 'Show route' }).click();
    await expect(page.getByTestId('route-trip-no')).toHaveText(trip.tripNo);

    const stops = page.getByTestId('stop-row');
    await expect(stops.first()).toBeVisible();
    expect(await stops.count()).toBeGreaterThanOrEqual(2);
    await expect(stops.first().getByTestId('stop-status')).toHaveText('PLANNED');
    await expect(stops.first()).toContainText('PICKUP');

    await test.step('arrive at the first stop', async () => {
      await stops.first().getByTestId('stop-arrive').click();
      await expect(stops.first().getByTestId('stop-status')).toHaveText('ARRIVED');
      await expect(stops.first().getByTestId('stop-arrive')).toHaveCount(0);
    });

    await test.step('depart from it', async () => {
      await stops.first().getByTestId('stop-depart').click();
      await expect(stops.first().getByTestId('stop-status')).toHaveText('DEPARTED');
    });

    await test.step('skip the last stop with a reason', async () => {
      const last = stops.last();
      await last.getByTestId('stop-skip').click();
      await page.getByTestId('stop-skip-reason').fill('Consignee closed');
      await page.getByTestId('stop-skip-confirm').click();
      await expect(last.getByTestId('stop-status')).toHaveText('SKIPPED');
      await expect(last).toContainText('Consignee closed');
    });
  });

  test('without TRIP_EXECUTE the stops are shown but cannot be acted on', async ({ page, request }, info) => {
    const trip = await newTrip(request, await apiSession(request), tag(info));
    await withoutPermissions(page, ['TRIP_EXECUTE']);
    await signIn(page);
    await openScreen(page, 'nav-trip-route', 'Trip route');
    await page.getByTestId('trip-id').fill(trip.tripId);
    await page.getByRole('button', { name: 'Show route' }).click();
    await expect(page.getByTestId('stop-row').first()).toBeVisible();
    await expect(page.getByTestId('stop-arrive')).toHaveCount(0);
    await expect(page.getByTestId('stop-skip')).toHaveCount(0);
  });
});
