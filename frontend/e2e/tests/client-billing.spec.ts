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
  apiSession, billLastMonth, chargedServices, dayOfMonth, handOffFor, handledInLastMonth, newBillingClient,
  openScreen, printed, signIn, stubPrint, withoutPermissions,
} from './support';

/** An amount as the screen shows it, whether or not trailing zeros are kept. */
function amount(value: number): RegExp {
  return new RegExp(`^${value}(\\.0+)?$`);
}

/** Opens the billing desk on one client. */
async function openClient(page: Page, clientId: string): Promise<void> {
  await openScreen(page, 'nav-client-billing', 'Client billing');
  await page.getByTestId('client-id').fill(clientId);
  await page.getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('client-desk')).toBeVisible();
  await expect(page.getByTestId('contract-row')).toHaveCount(1);
}

test.describe('client billing', () => {
  test('a warehouse hand-off shows among the month\'s charges as handling out and transport', async ({ page, request }) => {
    const session = await apiSession(request);
    const client = await newBillingClient(request, session);
    await handOffFor(request, session, client.id);
    // The charge is raised by a listener once the dispatch commits.
    await expect.poll(() => chargedServices(request, session, client.id, dayOfMonth(0, 1)))
      .toEqual(['HANDLING_OUT', 'TRANSPORT']);

    await signIn(page);
    await openClient(page, client.id);

    const charges = page.getByTestId('charge-row');
    await expect(charges).toHaveCount(2);
    await expect(charges.filter({ hasText: 'HANDLING_OUT' })).toHaveCount(1);
    await expect(charges.filter({ hasText: 'TRANSPORT' })).toContainText('420');
  });

  test('an ended month is billed once, printed, credited and paid, and the statement follows', async ({ page, request }) => {
    const session = await apiSession(request);
    const client = await newBillingClient(request, session);
    await handledInLastMonth(request, session, client.id, 4);
    await stubPrint(page);
    await signIn(page);
    await openClient(page, client.id);

    await test.step('last month holds the handling charge', async () => {
      await page.getByTestId('charges-month').fill(dayOfMonth(1, 1).slice(0, 7));
      await expect(page.getByTestId('charge-row')).toHaveCount(1);
      await expect(page.getByTestId('charge-row')).toContainText('HANDLING_IN');
    });

    await test.step('a billing run issues one invoice, and running it again issues no other', async () => {
      await page.getByTestId('run-billing').click();
      await expect(page.getByTestId('billing-result')).toBeVisible();
      await expect(page.getByTestId('invoice-row')).toHaveCount(1);
      const panel = page.getByTestId('invoice-panel');
      await expect(panel.getByTestId('invoice-line')).toHaveCount(1);
      await expect(panel.getByTestId('invoice-total')).toContainText('100');

      await page.getByTestId('run-billing').click();
      await expect(page.getByTestId('billing-result')).toBeVisible();
      await expect(page.getByTestId('invoice-row')).toHaveCount(1);
      await expect(page.getByTestId('statement-balance')).toHaveText(amount(100));
    });

    await test.step('the invoice prints', async () => {
      await page.getByTestId('print-invoice').click();
      await expect(page.getByTestId('invoice-slip').getByTestId('slip-line')).toHaveCount(1);
      await page.getByTestId('print-now').click();
      expect(await printed(page)).toBe(1);
    });

    await test.step('a credit note reduces the invoice and the balance', async () => {
      const form = page.getByTestId('credit-note');
      await form.getByLabel('Reason for the credit').fill('One pallet counted twice');
      await form.getByLabel('Amount to credit').fill('10');
      await form.getByRole('button', { name: 'Issue credit note' }).click();
      await expect(page.getByTestId('credit-issued')).toBeVisible();
      await expect(page.getByTestId('invoice-credited')).toHaveText(amount(10));
      await expect(page.getByTestId('statement-balance')).toHaveText(amount(90));
    });

    await test.step('a payment reduces the balance and what is outstanding', async () => {
      const form = page.getByTestId('record-payment');
      await form.getByLabel('Amount received').fill('40');
      await form.getByLabel('Remittance reference').fill(`E2E-PAY-${Date.now()}`);
      await form.getByRole('button', { name: 'Record payment' }).click();
      await expect(page.getByTestId('payment-recorded')).toBeVisible();
      await expect(page.getByTestId('statement-balance')).toHaveText(amount(50));
      await expect(page.getByTestId('statement-row')).toHaveCount(3);
      await expect(page.getByTestId('ageing-outstanding')).toContainText('50');
    });
  });

  test('a run for a month with nothing charged is refused, and the reason shown', async ({ page, request }) => {
    const client = await newBillingClient(request, await apiSession(request));
    await signIn(page);
    await openClient(page, client.id);

    await page.getByTestId('run-billing').click();
    await expect(page.getByRole('alert')).toContainText('Nothing was charged');
    await expect(page.getByTestId('no-invoices')).toBeVisible();
  });
});

test.describe('what each person is offered', () => {
  test('without the rights to bill, credit or record payments, billing is read and printed but not changed', async ({ page, request }) => {
    const session = await apiSession(request);
    const client = await newBillingClient(request, session);
    await handledInLastMonth(request, session, client.id, 2);
    await billLastMonth(request, session, client.id);
    await withoutPermissions(page, ['CLIENT_BILLING_RUN', 'CLIENT_CREDIT_NOTE_ISSUE']);
    await stubPrint(page);
    await signIn(page);
    await openClient(page, client.id);

    await expect(page.getByTestId('billing-run')).toHaveCount(0);
    await expect(page.getByTestId('record-payment')).toHaveCount(0);
    await page.getByTestId('invoice-row').click();
    await expect(page.getByTestId('invoice-panel').getByTestId('invoice-line')).toHaveCount(1);
    await expect(page.getByTestId('credit-note')).toHaveCount(0);
    await page.getByTestId('print-invoice').click();
    await page.getByTestId('print-now').click();
    expect(await printed(page)).toBe(1);
  });

  test('without the right to read client billing, it is not in the sidebar', async ({ page }) => {
    await withoutPermissions(page, ['CLIENT_BILLING_READ']);
    await signIn(page);
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-client-billing')).toHaveCount(0);
  });
});
