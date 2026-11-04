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
import {
  apiSession, newBillingClient, newDeskOrder, openScreen, signIn, tag, withoutPermissions, type Session,
} from './support';

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

/*
 * The demo tenant has one administrator, and a credit limit only changes once a second
 * person approves it, so no spec here can give a customer a real limit to break. A hard
 * limit refusal is therefore answered by the browser itself, exactly as the API words
 * it, the first time only: what the desk does with it is what is under test, and the
 * retry after an override reaches the real API.
 */

/** The problem document the API answers a hard credit limit refusal with. */
function creditRefusal(partnerId: string, subjectType: string) {
  return {
    status: 409,
    contentType: 'application/problem+json',
    json: {
      type: 'about:blank', title: 'Conflict', status: 409, code: 'credit-limit-exceeded',
      detail: 'Exposure 1500.00 would exceed the credit limit 1000.00',
      errors: { exposure: '1500.00', creditLimit: '1000.00', partnerId, subjectType },
    },
  };
}

/** Records every override the page asks for, and answers each as granted. */
async function captureOverrides(page: Page): Promise<Array<Record<string, unknown>>> {
  const seen: Array<Record<string, unknown>> = [];
  await page.route('**/api/v1/partners/*/credit-overrides', async (route) => {
    seen.push(route.request().postDataJSON() as Record<string, unknown>);
    await route.fulfill({ status: 201, json: { id: crypto.randomUUID() } });
  });
  return seen;
}

/** Walks a partner along its lifecycle to `target` through the API. */
async function moveTo(request: APIRequestContext, session: Session, partnerId: string, targets: string[]): Promise<void> {
  for (const target of targets) {
    const response = await request.post(`${API}/api/v1/partners/${partnerId}/transitions`, {
      headers: { Authorization: `Bearer ${session.token}` }, data: { target },
    });
    expect(response.ok(), `transition to ${target}: ${response.status()}`).toBeTruthy();
  }
}

/** A fresh customer with no limit and nothing owed. */
async function newCustomer(request: APIRequestContext, session: Session, code: string): Promise<string> {
  const response = await request.post(`${API}/api/v1/partners`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data: { orgUnitId: session.orgUnitId, code, legalName: `${code} Ltd`, partnerType: 'CUSTOMER', taxId: `TAX-${code}` },
  });
  expect(response.ok(), `partner ${code}: ${response.status()}`).toBeTruthy();
  return (await response.json()).id as string;
}

async function openOrder(page: Page, orderId: string): Promise<void> {
  await page.getByTestId('nav-order-desk').click();
  await expect(page.getByRole('heading', { name: 'Order desk', level: 1 })).toBeVisible();
  await page.getByTestId('order-id').fill(orderId);
  await page.getByTestId('open-order').getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('order-panel')).toBeVisible();
}

async function openClient(page: Page, clientId: string): Promise<void> {
  await openScreen(page, 'nav-client-billing', 'Client billing');
  await page.getByTestId('client-id').fill(clientId);
  await page.getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('client-desk')).toBeVisible();
}

/** The month before this one, as the billing desk offers it by default. */
function lastMonth(): string {
  const d = new Date();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - 1);
  return d.toISOString().slice(0, 7);
}

test.describe('credit on the order desk', () => {
  test('the customer\'s credit position is shown beside the order', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info));
    await signIn(page);
    await openOrder(page, order.orderId);

    const position = page.getByTestId('credit-position');
    await expect(position).toBeVisible();
    await expect(position.getByTestId('credit-state')).toHaveText('NO_LIMIT');
    await expect(position.getByTestId('credit-limit')).toHaveText('No limit');
    await expect(position.getByTestId('credit-not-trading')).toHaveCount(0);
  });

  test('a credit refusal is shown by its code, and an override lets validation through', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info));
    const overrides = await captureOverrides(page);
    await page.route(`**/api/v1/orders/${order.orderId}/validate`,
      (route) => route.fulfill(creditRefusal(order.customers[0].id, 'ORDER')), { times: 1 });
    await signIn(page);
    await openOrder(page, order.orderId);

    await test.step('the refusal names the exposure and the limit', async () => {
      await page.getByTestId('validate-order').click();
      await expect(page.getByTestId('order-refusal')).toHaveText('credit-limit-exceeded');
      await expect(page.getByTestId('credit-refusal-figures')).toContainText('1500.00');
      await expect(page.getByTestId('credit-refusal-figures')).toContainText('1000.00');
      await expect(page.getByTestId('order-status')).toHaveText('DRAFT');
    });

    await test.step('an override with a reason is asked for, and validation tried again', async () => {
      const form = page.getByTestId('credit-override');
      await expect(form.getByLabel('Override up to')).toHaveValue('1500.00');
      await form.getByLabel('Override reason').fill('Payment promised this week');
      await page.getByTestId('credit-override-submit').click();
      await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
      expect(overrides).toHaveLength(1);
      expect(overrides[0]).toMatchObject({
        subjectType: 'ORDER', orderId: order.orderId, upTo: 1500, reason: 'Payment promised this week',
      });
    });
  });

  test('without CREDIT_OVERRIDE the refusal is shown and no override is offered', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info));
    await page.route(`**/api/v1/orders/${order.orderId}/validate`,
      (route) => route.fulfill(creditRefusal(order.customers[0].id, 'ORDER')), { times: 1 });
    await withoutPermissions(page, ['CREDIT_OVERRIDE']);
    await signIn(page);
    await openOrder(page, order.orderId);

    await page.getByTestId('validate-order').click();
    await expect(page.getByTestId('order-refusal')).toHaveText('credit-limit-exceeded');
    await expect(page.getByTestId('credit-override-unavailable')).toBeVisible();
    await expect(page.getByTestId('credit-override')).toHaveCount(0);
  });

  test('a suspended customer\'s order is refused validation, and the desk says why', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const order = await newDeskOrder(request, session, tag(info));
    await moveTo(request, session, order.customers[0].id, ['PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED']);
    await signIn(page);
    await openOrder(page, order.orderId);

    await expect(page.getByTestId('credit-not-trading')).toBeVisible();
    await page.getByTestId('validate-order').click();
    await expect(page.getByTestId('order-refusal')).toHaveText('customer-not-trading');
    await expect(page.getByTestId('order-status')).toHaveText('DRAFT');
  });
});

test.describe('credit on the client billing desk', () => {
  test('the client\'s credit position is shown, and a refused run is overridden for its month', async ({ page, request }) => {
    const client = await newBillingClient(request, await apiSession(request));
    const overrides = await captureOverrides(page);
    await page.route(`**/api/v1/client-billing/clients/${client.id}/billing-runs`,
      (route) => route.fulfill(creditRefusal(client.id, 'CLIENT_INVOICE')), { times: 1 });
    await signIn(page);
    await openClient(page, client.id);

    await expect(page.getByTestId('credit-position').getByTestId('credit-state')).toHaveText('NO_LIMIT');

    await page.getByTestId('run-billing').click();
    await expect(page.getByTestId('billing-refusal')).toHaveText('credit-limit-exceeded');
    await expect(page.getByTestId('credit-refusal-figures')).toContainText('1000.00');

    await page.getByTestId('credit-override').getByLabel('Override reason').fill('Agreed with finance');
    await page.getByTestId('credit-override-submit').click();
    // The retry reaches the real API, which has nothing to bill for this new client.
    await expect(page.getByTestId('billing-refusal')).toHaveText('client-period-empty');
    expect(overrides).toHaveLength(1);
    expect(overrides[0]).toMatchObject({
      subjectType: 'CLIENT_INVOICE', period: `${lastMonth()}-01`, upTo: 1500, reason: 'Agreed with finance',
    });
  });
});

test.describe('credit limit changes', () => {
  test('a limit change is asked for and waits for someone else to approve it', async ({ page, request }, info) => {
    const code = `ZZ-CR-${tag(info)}`;
    const customer = await newCustomer(request, await apiSession(request), code);
    await signIn(page);
    await openScreen(page, 'nav-admin-credit', 'Credit limits');
    await page.getByTestId('credit-customer').fill(customer);
    await page.getByTestId('credit-partner-form').getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('credit-position').getByTestId('credit-limit')).toHaveText('No limit');
    await expect(page.getByTestId('credit-history-empty')).toBeVisible();

    const ask = page.getByTestId('credit-change-request');
    await test.step('a soft limit above the limit is refused', async () => {
      await ask.getByLabel('New credit limit').fill('100');
      await ask.getByLabel('New soft limit').fill('200');
      await ask.getByLabel('Change reason').fill('Seasonal volume');
      await page.getByTestId('credit-change-submit').click();
      await expect(page.getByTestId('credit-admin-refusal')).toHaveText('credit-soft-limit-above-limit');
    });

    await test.step('a sound request waits for approval', async () => {
      await ask.getByLabel('New credit limit').fill('5000');
      await ask.getByLabel('New soft limit').fill('4000');
      await page.getByTestId('credit-change-submit').click();
      await expect(page.getByTestId('credit-admin-message')).toContainText('waits for approval');
      await expect(page.getByTestId('credit-history-row')).toHaveCount(1);
      await expect(page.getByTestId('credit-history-row')).toContainText('PENDING');
      await expect(page.getByTestId('credit-change-pending')).toBeVisible();
      await expect(page.getByTestId('credit-position').getByTestId('credit-limit')).toHaveText('No limit');
    });

    const row = page.getByTestId('credit-queue-row').filter({ hasText: code });
    await test.step('the person who asked cannot approve it', async () => {
      await row.getByTestId('credit-approve').click();
      await expect(page.getByTestId('credit-admin-refusal')).toHaveText('credit-limit-change-self-approval');
      await expect(page.getByTestId('credit-position').getByTestId('credit-limit')).toHaveText('No limit');
    });

    await test.step('a rejection leaves the limit as it was', async () => {
      await row.getByLabel(`Decision note for ${code}`).fill('Not this quarter');
      await row.getByTestId('credit-reject').click();
      await expect(page.getByTestId('credit-admin-message')).toContainText('Rejected');
      await expect(page.getByTestId('credit-history-row')).toContainText('REJECTED');
      await expect(page.getByTestId('credit-queue-row').filter({ hasText: code })).toHaveCount(0);
      await expect(page.getByTestId('credit-position').getByTestId('credit-limit')).toHaveText('No limit');
    });
  });

  test('without the right to approve, requests are listed but cannot be decided', async ({ page }) => {
    await withoutPermissions(page, ['CREDIT_LIMIT_APPROVE']);
    await signIn(page);
    await openScreen(page, 'nav-admin-credit', 'Credit limits');
    await expect(page.getByTestId('credit-approve')).toHaveCount(0);
    await expect(page.getByTestId('credit-reject')).toHaveCount(0);
  });
});
