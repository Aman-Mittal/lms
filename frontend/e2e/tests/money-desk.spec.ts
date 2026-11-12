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
import { apiSession, openScreen, signIn, tag } from './support';
import { deliveredTrip } from './support-commercial';
import {
  allowSelfApproval, approveBill, approveSubject, newChargeEntry, resetApprovalPolicies, setOrgSetting, tripOfBill,
} from './support-money';

/*
 * The money desks, in both consoles: the charge catalogue, manual charges (in force at once under
 * the approval threshold, waiting for approval above it), the vendor payables desk (a wrong invoice
 * is refused, a partial payment leaves a balance) and advances on a trip (over the cap is refused,
 * an approved advance is paid with a reference).
 *
 * The threshold is lowered for the run and the two approval policies let the one demo administrator
 * decide their own requests; both are put back afterwards, whatever happened.
 */

const THRESHOLD = 'finance.manualCharge.approvalThreshold';

test.describe.configure({ mode: 'serial' });

test.beforeAll(async ({ request }) => {
  const session = await apiSession(request);
  await setOrgSetting(request, session, THRESHOLD, 1000);
  await allowSelfApproval(request, session);
});

test.afterAll(async ({ request }) => {
  const session = await apiSession(request);
  await setOrgSetting(request, session, THRESHOLD, null);
  await resetApprovalPolicies(request, session);
});

async function raise(page: Page, label: string, tripId: string, quantity: string): Promise<void> {
  const form = page.getByTestId('mc-form');
  await form.getByTestId('mc-catalogue').selectOption({ label });
  await form.getByTestId('mc-subject-type').selectOption('TRIP');
  await form.getByTestId('mc-subject').fill(tripId);
  await form.getByTestId('mc-direction').selectOption('RECEIVABLE');
  await form.getByTestId('mc-quantity').fill(quantity);
  await form.getByTestId('mc-reason').fill('Waited at the gate');
  await form.getByTestId('mc-evidence').fill('GATE-LOG-1');
  await form.getByTestId('mc-submit').click();
}

test.describe('the charge catalogue', () => {
  test('an entry is added, then edited with its code locked', async ({ page }, info) => {
    const code = `ZZ-CC-${tag(info)}`.toUpperCase();
    await signIn(page);
    await openScreen(page, 'nav-charge-catalogue', 'Charge catalogue');

    const form = page.getByTestId('cc-form');
    await form.getByTestId('cc-code').fill(code);
    await form.getByTestId('cc-name').fill('Halting at the gate');
    await form.getByTestId('cc-basis').selectOption('PER_HOUR');
    await form.getByTestId('cc-rate').fill('50');
    await form.getByTestId('cc-direction').selectOption('BOTH');
    await form.getByTestId('cc-save').click();
    await expect(page.getByTestId('cc-saved')).toBeVisible();

    await page.getByTestId('cc-search').fill(code);
    await page.getByRole('button', { name: 'Search' }).click();
    const row = page.getByTestId('cc-row').filter({ hasText: code });
    await expect(row).toHaveCount(1);
    await row.getByTestId('cc-edit').click();
    await expect(form.getByTestId('cc-code')).toBeDisabled();
    await form.getByTestId('cc-rate').fill('75');
    await form.getByTestId('cc-save').click();
    await expect(page.getByTestId('cc-saved')).toBeVisible();
    await expect(page.getByTestId('cc-row').filter({ hasText: code })).toContainText('75');
  });
});

test.describe('manual charges', () => {
  test('under the threshold a charge is raised at once; above it waits for approval and is then approved', async ({ page, request }, info) => {
    test.setTimeout(180_000);
    const salt = tag(info);
    const session = await apiSession(request);
    const trip = await deliveredTrip(request, session, salt);
    const tripId = await tripOfBill(request, session, trip.billId);
    const entry = await newChargeEntry(request, session, `ZZ-MC-${salt}`.toUpperCase(), 100);
    const label = `${entry.code} - ${entry.name}`;

    await signIn(page);
    await openScreen(page, 'nav-manual-charges', 'Manual charges');

    await test.step('two units at 100 is 200, under the threshold of 1 000', async () => {
      await raise(page, label, tripId, '2');
      await expect(page.getByTestId('mc-result')).toContainText('Raised');
      const row = page.getByTestId('mc-row').first();
      await expect(row.getByTestId('mc-status')).toHaveText('APPROVED');
      await expect(row.getByTestId('mc-amount')).toContainText('200');
    });

    let pendingId = '';
    await test.step('twenty units is 2 000, above it: awaiting approval', async () => {
      await raise(page, label, tripId, '20');
      await expect(page.getByTestId('mc-result')).toContainText('Awaiting approval');
      const row = page.getByTestId('mc-row').first();
      await expect(row.getByTestId('mc-status')).toHaveText('PENDING_APPROVAL');
      pendingId = (await row.getAttribute('data-id')) ?? '';
      expect(pendingId).not.toBe('');
    });

    await test.step('approved through the approvals module, it is in force', async () => {
      await approveSubject(request, session, 'MANUAL_CHARGE', pendingId);
      await page.getByTestId('mc-filter-status').selectOption('APPROVED');
      await expect(page.locator(`[data-testid="mc-row"][data-id="${pendingId}"]`).getByTestId('mc-status'))
        .toHaveText('APPROVED');
    });

    await test.step('an approved charge that has not been billed is cancelled with a reason', async () => {
      const row = page.locator(`[data-testid="mc-row"][data-id="${pendingId}"]`);
      await row.getByTestId('mc-cancel').click();
      await page.getByTestId('mc-cancel-reason').fill('raised twice');
      await page.getByTestId('mc-cancel-confirm').click();
      await page.getByTestId('mc-filter-status').selectOption('CANCELLED');
      await expect(page.locator(`[data-testid="mc-row"][data-id="${pendingId}"]`).getByTestId('mc-status'))
        .toHaveText('CANCELLED');
    });
  });
});

test.describe('vendor payables', () => {
  test('a wrong invoice is refused, the right one is captured, and a part payment leaves a balance', async ({ page, request }, info) => {
    test.setTimeout(180_000);
    const salt = tag(info);
    const session = await apiSession(request);
    const trip = await deliveredTrip(request, session, salt);
    await approveBill(request, session, trip.billId, 20000);
    const vendorNo = `VINV-${salt}`;

    await signIn(page);
    await openScreen(page, 'nav-payables', 'Vendor payables');

    const form = page.getByTestId('inv-form');
    await test.step('30 000 against 20 000 approved is a mismatch', async () => {
      await form.getByTestId('inv-bills').fill(trip.billNo);
      await form.getByTestId('inv-vendor-no').fill(vendorNo);
      await form.getByTestId('inv-amount').fill('30000');
      await form.getByTestId('inv-submit').click();
      await expect(page.getByTestId('inv-error')).toContainText('vendor-invoice-mismatch');
    });

    await test.step('the right amount is captured', async () => {
      await form.getByTestId('inv-amount').fill('20000');
      await form.getByTestId('inv-submit').click();
      await expect(page.getByTestId('inv-result')).toBeVisible();
      const row = page.getByTestId('inv-row').filter({ hasText: vendorNo });
      await expect(row).toHaveCount(1);
      await expect(row.getByTestId('inv-status')).toHaveText('OPEN');
      await expect(row.getByTestId('inv-outstanding')).toHaveText('20000');
    });

    await test.step('a part payment leaves the rest outstanding', async () => {
      await page.getByTestId('inv-row').filter({ hasText: vendorNo }).click();
      const detail = page.getByTestId('inv-detail');
      await detail.getByTestId('pay-amount').fill('5000');
      await detail.getByTestId('pay-reference').fill(`PAY-${salt}-1`);
      await detail.getByTestId('pay-submit').click();
      await expect(detail.getByTestId('detail-status')).toHaveText('PARTIALLY_PAID');
      await expect(detail.getByTestId('detail-outstanding')).toHaveText('15000');
    });

    await test.step('the payment is listed', async () => {
      await page.getByTestId('pay-tab-payments').click();
      await expect(page.getByTestId('payment-row').filter({ hasText: `PAY-${salt}-1` })).toHaveCount(1);
    });
  });
});

test.describe('advances on a trip', () => {
  test('over the cap is refused; an approved advance is paid with a reference', async ({ page, request }, info) => {
    test.setTimeout(180_000);
    const salt = tag(info);
    const session = await apiSession(request);
    const trip = await deliveredTrip(request, session, salt);
    const tripId = await tripOfBill(request, session, trip.billId);

    await signIn(page);
    await openScreen(page, 'nav-trip-route', 'Trip route');
    await page.getByTestId('trip-id').fill(tripId);
    await page.getByRole('button', { name: 'Show route' }).click();
    const money = page.getByTestId('trip-money');
    await expect(money).toBeVisible();

    await test.step('15 000 of a 20 000 freight is over the 70 % cap', async () => {
      await money.getByTestId('adv-mode').selectOption('BANK');
      await money.getByTestId('adv-amount').fill('15000');
      await money.getByTestId('adv-reason').fill('Fuel and tolls');
      await money.getByTestId('adv-submit').click();
      await expect(money.getByTestId('adv-error')).toContainText('advance-exceeds-cap');
    });

    let advanceId = '';
    await test.step('10 000 is requested and waits for approval', async () => {
      await money.getByTestId('adv-amount').fill('10000');
      await money.getByTestId('adv-submit').click();
      await expect(money.getByTestId('adv-result')).toContainText('Awaiting approval');
      const row = money.getByTestId('adv-row').first();
      await expect(row.getByTestId('adv-status')).toHaveText('PENDING_APPROVAL');
      advanceId = (await row.getAttribute('data-id')) ?? '';
      expect(advanceId).not.toBe('');
    });

    await test.step('approved, then paid with a reference', async () => {
      await approveSubject(request, session, 'VENDOR_ADVANCE', advanceId);
      await page.getByRole('button', { name: 'Show route' }).click();
      const row = page.getByTestId('trip-money').locator(`[data-testid="adv-row"][data-id="${advanceId}"]`);
      await expect(row.getByTestId('adv-status')).toHaveText('APPROVED');
      await row.getByTestId('adv-pay').click();
      await page.getByTestId('adv-pay-reference').fill(`UTR-${salt}`);
      await page.getByTestId('adv-pay-confirm').click();
      await expect(page.getByTestId('trip-money').locator(`[data-testid="adv-row"][data-id="${advanceId}"]`)
        .getByTestId('adv-status')).toHaveText('PAID');
    });
  });
});
