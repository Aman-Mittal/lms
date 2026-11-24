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
import { stubTripSession, stubTrips, type TripStub } from './support-trips';

/*
 * The confirm dialog both consoles put in front of a destructive action, shown here on the trip desk's Cancel trip. The
 * API is stubbed, so the spec needs no backend: it checks what the dialog says and how it behaves, and that dismissing
 * it, by its button or by Escape, sends nothing and gives focus back to the button that opened it. Set E2E_STUB_SESSION=1
 * to stub the sign-in too.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubTripSession(page);
  }
});

async function openCancelDialog(page: Page): Promise<TripStub> {
  const stub = await stubTrips(page);
  const trip = stub.seedTrip({ tripNo: 'T-100' });
  await signIn(page);
  await page.getByTestId('nav-trip-desk').click();
  await expect(page.getByRole('heading', { name: 'Trip desk', level: 1 })).toBeVisible();
  await page.getByTestId('trip-open-id').fill(trip.id);
  await page.getByTestId('trip-open').click();
  await expect(page.getByTestId('trip-panel')).toHaveAttribute('data-id', trip.id);
  await page.getByTestId('trip-cancel').click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  return stub;
}

const nothingChanged = async (page: Page, stub: TripStub) => {
  expect(stub.changes.filter((c) => c.path.endsWith('/cancellation'))).toHaveLength(0);
  await expect(page.getByTestId('trip-status')).toHaveAttribute('data-value', 'PLANNED');
  await expect(page.getByTestId('confirm-dialog')).toHaveCount(0);
};

test.describe('confirm dialog', () => {
  test('it names the action and the record, is labelled, and puts focus on the safe button', async ({ page }) => {
    await openCancelDialog(page);
    const dialog = page.getByTestId('confirm-dialog');

    await expect(dialog).toHaveAttribute('role', 'dialog');
    await expect(dialog).toHaveAttribute('aria-modal', 'true');
    await expect(dialog.getByRole('heading', { name: 'Cancel trip T-100?' })).toBeVisible();
    await expect(dialog).toHaveAccessibleName('Cancel trip T-100?');
    await expect(page.getByTestId('confirm-accept')).toHaveText('Cancel trip');
    await expect(page.getByTestId('confirm-dismiss')).toHaveText('Keep it');
    await expect(page.getByTestId('trip-cancel-reason')).toBeVisible();
    await expect(page.getByTestId('confirm-dismiss')).toBeFocused();
  });

  test('focus stays inside the dialog while it is open', async ({ page }) => {
    await openCancelDialog(page);
    const inside = () => page.evaluate(() => !!document.activeElement?.closest('[data-testid="confirm-dialog"]'));
    for (let i = 0; i < 6; i += 1) {
      await page.keyboard.press('Tab');
      expect(await inside()).toBe(true);
    }
    await page.keyboard.press('Shift+Tab');
    expect(await inside()).toBe(true);
  });

  test('Keep it changes nothing and returns focus to the button that opened it', async ({ page }) => {
    const stub = await openCancelDialog(page);
    await page.getByTestId('trip-cancel-reason').fill('typed but not sent');
    await page.getByTestId('confirm-dismiss').click();

    await nothingChanged(page, stub);
    await expect(page.getByTestId('trip-cancel')).toBeFocused();
  });

  test('Escape changes nothing and returns focus to the button that opened it', async ({ page }) => {
    const stub = await openCancelDialog(page);
    await page.keyboard.press('Escape');

    await nothingChanged(page, stub);
    await expect(page.getByTestId('trip-cancel')).toBeFocused();
  });

  test('a required reason is asked for inside the dialog, and the action goes through once it is given', async ({ page }) => {
    const stub = await openCancelDialog(page);
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('reason-error')).toBeVisible();
    expect(stub.changes.filter((c) => c.path.endsWith('/cancellation'))).toHaveLength(0);

    await page.getByTestId('trip-cancel-reason').fill('the customer withdrew');
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('trip-status')).toHaveAttribute('data-value', 'CANCELLED');
    await expect(page.getByTestId('confirm-dialog')).toHaveCount(0);
  });
});
