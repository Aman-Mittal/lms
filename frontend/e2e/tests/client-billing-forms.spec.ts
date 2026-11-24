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
import { openScreen, signIn } from './support';
import {
  clientId, portalUserId, strangerId, stubClientBilling, stubClientBillingSession, type ClientBillingStub,
} from './support-client-billing-forms';

/*
 * The client billing desk's four forms, in both consoles: portal access granted to a user, a service usage recorded by
 * hand, a month's storage computed and shown, and the margin report read a page at a time. The API is stubbed so the
 * same assertions run whatever the demo tenant holds; the stub keeps the server's rules and the spec checks what each
 * console sends. Set E2E_STUB_SESSION=1 to stub the sign-in too, for a run with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubClientBillingSession(page);
  }
});

/** A source key built at run time, so no two runs record the same usage. */
const sourceKey = () => `usage-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;

/** The last change the stub saw whose path ends as given. */
function sent(stub: ClientBillingStub, ending: string) {
  return stub.changes.filter((c) => c.path.endsWith(ending)).at(-1);
}

/** Opens the billing desk on the stubbed client. */
async function openDesk(page: Page): Promise<void> {
  await openScreen(page, 'nav-client-billing', 'Client billing');
  await page.getByTestId('client-id').fill(clientId);
  await page.getByRole('button', { name: 'Open', exact: true }).click();
  await expect(page.getByTestId('client-desk')).toBeVisible();
}

test.describe('portal access', () => {
  test('a user picked by name is let into the client portal; a user the server does not know is refused with its code', async ({ page }) => {
    const stub = await stubClientBilling(page);
    await signIn(page);
    await openDesk(page);
    const form = page.getByTestId('cb-portal-form');

    await test.step('granting sends the user and says so', async () => {
      const picker = form.getByTestId('cb-portal-user');
      await expect(picker.locator('option')).toHaveText(['Choose a user', 'Asha Rao (asha@example.test)', 'Ghost Writer (ghost@example.test)']);
      await picker.selectOption({ label: 'Asha Rao (asha@example.test)' });
      await form.getByTestId('cb-portal-submit').click();
      await expect(page.getByTestId('cb-portal-done')).toHaveText('Portal access granted to Asha Rao.');
      expect(sent(stub, `/clients/${clientId}/portal-users`)?.body).toEqual({ userId: portalUserId });
      expect(stub.portalUsers).toEqual([portalUserId]);
      await expect(form.getByTestId('cb-portal-user')).toHaveValue('');
    });

    await test.step('an unknown user is refused, the code shown, and no success note stays', async () => {
      await form.getByTestId('cb-portal-user').selectOption({ label: 'Ghost Writer (ghost@example.test)' });
      await form.getByTestId('cb-portal-submit').click();
      await expect(page.getByTestId('cb-portal-refusal')).toHaveText('not-found');
      await expect(page.getByTestId('cb-portal-done')).toHaveCount(0);
      expect(stub.portalUsers).toEqual([portalUserId]);
    });
  });
});

test.describe('portal access without a user list', () => {
  test('when the users cannot be listed the form takes a pasted id', async ({ page }) => {
    const stub = await stubClientBilling(page);
    stub.refuseUsers = true;
    await signIn(page);
    await openDesk(page);
    const form = page.getByTestId('cb-portal-form');
    await form.getByTestId('cb-portal-user').fill(portalUserId);
    await form.getByTestId('cb-portal-submit').click();
    await expect(page.getByTestId('cb-portal-done')).toHaveText(`Portal access granted to ${portalUserId}.`);
    expect(sent(stub, `/clients/${clientId}/portal-users`)?.body).toEqual({ userId: portalUserId });
  });
});

test.describe('service usage', () => {
  test('a usage is recorded once; the same source key is refused with its code', async ({ page }) => {
    const stub = await stubClientBilling(page);
    await signIn(page);
    await openDesk(page);
    const form = page.getByTestId('cb-usage-form');
    const key = sourceKey();

    async function fill() {
      await form.getByTestId('cb-usage-type').selectOption('VALUE_ADDED');
      await form.getByTestId('cb-usage-code').fill('PALLET_WRAP');
      await form.getByTestId('cb-usage-quantity').fill('4');
      await form.getByTestId('cb-usage-day').fill('2026-10-05');
      await form.getByTestId('cb-usage-reference').fill('WO-77');
      await form.getByTestId('cb-usage-key').fill(key);
      await form.getByTestId('cb-usage-submit').click();
    }

    await test.step('recording sends every value and says so', async () => {
      await expect(form.getByTestId('cb-usage-code')).toHaveCount(0);
      await fill();
      await expect(page.getByTestId('cb-usage-done')).toHaveText(`Usage ${key} recorded.`);
      expect(sent(stub, `/clients/${clientId}/service-usage`)?.body).toEqual({
        serviceType: 'VALUE_ADDED', serviceCode: 'PALLET_WRAP', quantity: 4, occurredOn: '2026-10-05',
        reference: 'WO-77', sourceKey: key,
      });
      await expect(form.getByTestId('cb-usage-key')).toHaveValue('');
    });

    await test.step('the same key again is refused', async () => {
      await fill();
      await expect(page.getByTestId('cb-usage-refusal')).toHaveText('client-charge-key-reused');
      await expect(page.getByTestId('cb-usage-done')).toHaveCount(0);
      expect(stub.usageKeys).toEqual([key]);
    });
  });
});

test.describe('storage by month', () => {
  test('a month is shown uncomputed, computed and read back; one that has not ended is refused', async ({ page }) => {
    const stub = await stubClientBilling(page);
    await signIn(page);
    await openDesk(page);
    const form = page.getByTestId('cb-storage-form');
    const result = page.getByTestId('cb-storage-result');

    await test.step('a month not yet computed says so', async () => {
      await form.getByTestId('cb-storage-month').fill('2026-10');
      await form.getByTestId('cb-storage-view').click();
      await expect(result.getByTestId('cb-storage-pending')).toBeVisible();
      await expect(result.getByTestId('cb-storage-day')).toHaveCount(0);
    });

    await test.step('computing sends the month and shows the unit-days and each day', async () => {
      await form.getByTestId('cb-storage-compute').click();
      await expect(page.getByTestId('cb-storage-done')).toHaveText('Storage for 2026-10 computed.');
      expect(sent(stub, `/clients/${clientId}/storage-computations`)?.body).toEqual({ period: '2026-10-01' });
      await expect(result.getByTestId('cb-storage-unit-days')).toHaveText('60');
      await expect(result.getByTestId('cb-storage-pallet-days')).toHaveText('6');
      await expect(result.getByTestId('cb-storage-day')).toHaveCount(2);
    });

    await test.step('viewing the month again reads the same computation', async () => {
      await form.getByTestId('cb-storage-view').click();
      await expect(result.getByTestId('cb-storage-day')).toHaveCount(2);
      await expect(page.getByTestId('cb-storage-done')).toHaveCount(0);
    });

    await test.step('a month that has not ended is refused', async () => {
      await form.getByTestId('cb-storage-month').fill(new Date().toISOString().slice(0, 7));
      await form.getByTestId('cb-storage-compute').click();
      await expect(page.getByTestId('cb-storage-refusal')).toHaveText('client-period-not-ended');
      await expect(page.getByTestId('cb-storage-done')).toHaveCount(0);
    });
  });
});

test.describe('margins', () => {
  test('the margin report is read for a month, a page at a time; a refusal shows its code', async ({ page }) => {
    const stub = await stubClientBilling(page);
    await signIn(page);
    await openScreen(page, 'nav-client-billing', 'Client billing');
    const margins = page.getByTestId('cb-margins');

    await test.step('the first page lists its invoices and offers the next', async () => {
      await margins.getByTestId('cb-margin-month').fill('2026-10');
      await margins.getByTestId('cb-margin-show').click();
      await expect(margins.getByTestId('cb-margin-row')).toHaveCount(1);
      await expect(margins.getByTestId('cb-margin-row')).toContainText('INV-0001');
      await expect(margins.getByTestId('cb-margin-amount')).toHaveAttribute('data-value', '200');
    });

    await test.step('more margins appends the next page and the offer goes', async () => {
      await margins.getByTestId('cb-margin-more').click();
      await expect(margins.getByTestId('cb-margin-row')).toHaveCount(2);
      await expect(margins.getByTestId('cb-margin-row').nth(1)).toContainText('INV-0002');
      await expect(margins.getByTestId('cb-margin-more')).toHaveCount(0);
    });

    await test.step('a month with no invoice says so', async () => {
      await margins.getByTestId('cb-margin-month').fill('2026-09');
      await margins.getByTestId('cb-margin-show').click();
      await expect(margins.getByTestId('cb-margin-empty')).toBeVisible();
      await expect(margins.getByTestId('cb-margin-row')).toHaveCount(0);
    });

    await test.step('a refusal shows its code', async () => {
      stub.refuseMargins = true;
      await margins.getByTestId('cb-margin-show').click();
      await expect(margins.getByTestId('cb-margin-refusal')).toHaveText('access-denied');
    });
  });
});

test.describe('permissions', () => {
  test('what the holder may not do is not offered', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubClientBillingSession(page, ['CLIENT_BILLING_RUN', 'CLIENT_CONTRACT_MANAGE', 'CLIENT_MARGIN_READ']);
    await stubClientBilling(page);
    await signIn(page);
    await openDesk(page);
    await expect(page.getByTestId('cb-portal-form')).toHaveCount(0);
    await expect(page.getByTestId('cb-usage-form')).toHaveCount(0);
    await expect(page.getByTestId('cb-storage-compute')).toHaveCount(0);
    await expect(page.getByTestId('cb-storage-view')).toBeVisible();
    await expect(page.getByTestId('cb-margins')).toHaveCount(0);
  });
});
