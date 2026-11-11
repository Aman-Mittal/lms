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
import { API, apiSession, newDeskOrder, openScreen, signIn, tag, type Session } from './support';
import { deliveredTrip } from './support-commercial';

/*
 * The approvals inbox, in both consoles, through an order's cancellation.
 *
 * The demo tenant asks for no approval to cancel an order, so the spec turns
 * it on for itself -- letting the requester decide, because the demo has one
 * administrator -- and puts the default back afterwards, whatever happened,
 * so no other spec meets an approval it did not expect.
 */

async function setPolicy(request: APIRequestContext, session: Session, type: string, data: object): Promise<void> {
  const response = await request.put(`${API}/api/v1/approvals/policies/${type}`, {
    headers: { Authorization: `Bearer ${session.token}` }, data,
  });
  expect(response.ok(), `set policy: ${response.status()} ${await response.text()}`).toBeTruthy();
}

async function requireCancellationApproval(request: APIRequestContext, session: Session): Promise<void> {
  await setPolicy(request, session, 'ORDER_CANCELLATION', { required: true, allowSelfApproval: true });
  await setPolicy(request, session, 'POD_REVERSAL', { allowSelfApproval: true });
}

async function defaultPolicy(request: APIRequestContext, session: Session): Promise<void> {
  for (const type of ['ORDER_CANCELLATION', 'POD_REVERSAL']) {
    await request.delete(`${API}/api/v1/approvals/policies/${type}`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
  }
}

async function openOrder(page: Page, orderId: string): Promise<void> {
  await openScreen(page, 'nav-order-desk', 'Order desk');
  await page.getByTestId('order-id').fill(orderId);
  await page.getByTestId('open-order').getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('order-panel')).toBeVisible();
}

/** Asks to cancel on the order desk and sees it wait. */
async function askToCancel(page: Page, orderId: string, reason: string): Promise<void> {
  await openOrder(page, orderId);
  const cancel = page.getByTestId('cancel-order');
  await cancel.getByLabel('Cancellation reason').fill(reason);
  await cancel.getByRole('button', { name: 'Cancel order' }).click();
  await expect(page.getByTestId('order-done')).toHaveText('Cancellation requested: awaiting approval.');
  await expect(page.getByTestId('order-status')).toHaveText('DRAFT');
}

/** Opens the inbox and the request for this order. */
async function openRequest(page: Page, orderNo: string): Promise<void> {
  await openScreen(page, 'nav-approvals', 'Approvals');
  const row = page.getByTestId('approvals').getByRole('row').filter({ hasText: orderNo });
  await expect(row).toHaveCount(1);
  await row.getByRole('button').first().click();
  await expect(page.getByTestId('approval-detail')).toBeVisible();
  await expect(page.getByTestId('approval-status')).toHaveText('REQUESTED');
}

test.describe('approvals inbox', () => {
  let session: Session;

  test.beforeEach(async ({ request }) => {
    session = await apiSession(request);
    await requireCancellationApproval(request, session);
  });

  test.afterEach(async ({ request }) => {
    await defaultPolicy(request, session);
  });

  test('a cancellation waits in the inbox, and approving it cancels the order', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, session, tag(info), 'DRAFT');
    await signIn(page);
    await askToCancel(page, order.orderId, 'customer withdrew');

    await openRequest(page, order.orderNo);
    await expect(page.getByTestId('approval-payload')).toContainText('reason: customer withdrew');
    await expect(page.getByTestId('approval-policy')).toContainText('ORDER_APPROVE');
    await expect(page.getByTestId('approval-no-trail')).toBeVisible();

    await page.getByTestId('approve-note').fill('checked with the customer');
    await page.getByTestId('approve').click();
    await expect(page.getByTestId('approval-status')).toHaveText('APPROVED');
    await expect(page.getByTestId('approval-trail')).toContainText('checked with the customer');

    await openOrder(page, order.orderId);
    await expect(page.getByTestId('order-status')).toHaveText('CANCELLED');
  });

  test('a rejection needs a reason and leaves the order as it was', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, session, tag(info), 'DRAFT');
    await signIn(page);
    await askToCancel(page, order.orderId, 'duplicate entry');

    await openRequest(page, order.orderNo);
    await page.getByTestId('reject-reason').fill('not a duplicate');
    await page.getByTestId('reject').click();
    await expect(page.getByTestId('approval-status')).toHaveText('REJECTED');
    await expect(page.getByTestId('approval-reason')).toHaveText('not a duplicate');
    await expect(page.getByTestId('approve')).toHaveCount(0);

    await openOrder(page, order.orderId);
    await expect(page.getByTestId('order-status')).toHaveText('DRAFT');
  });

  test('the requester sees their own request and can withdraw it', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, session, tag(info), 'DRAFT');
    await signIn(page);
    await askToCancel(page, order.orderId, 'raised by mistake');

    await openScreen(page, 'nav-approvals', 'Approvals');
    await page.getByTestId('approvals-scope').selectOption('REQUESTED_BY_ME');
    await page.getByTestId('approvals-filter').getByRole('button', { name: 'Filter' }).click();
    const row = page.getByTestId('approvals').getByRole('row').filter({ hasText: order.orderNo });
    await row.getByRole('button').first().click();
    await page.getByTestId('cancel-approval').click();
    await expect(page.getByTestId('approval-status')).toHaveText('CANCELLED');
  });

  test('a proof of delivery is reversed only through the inbox', async ({ page, request }, info) => {
    test.setTimeout(120_000);
    const trip = await deliveredTrip(request, session, tag(info), 'CLEAN');
    await signIn(page);
    await openScreen(page, 'nav-delivery', 'Delivery desk');
    await page.getByTestId('consignment-id').fill(trip.consignmentId);
    await page.getByTestId('lookup').getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('proof-status')).toHaveText('CONFIRMED');

    await page.getByTestId('reversal-reason').fill('signed by the wrong person');
    await page.getByTestId('reverse-proof').getByRole('button', { name: 'Reverse proof' }).click();
    await expect(page.getByTestId('reversal-requested')).toHaveText('Reversal requested: awaiting approval.');
    await expect(page.getByTestId('proof-status')).toHaveText('CONFIRMED');

    await openScreen(page, 'nav-approvals', 'Approvals');
    await page.getByTestId('approvals-subject-type').fill('POD_REVERSAL');
    await page.getByTestId('approvals-filter').getByRole('button', { name: 'Filter' }).click();
    const row = page.getByTestId('approvals').getByRole('row').filter({ hasText: 'signed by the wrong person' });
    await row.first().getByRole('button').first().click();
    await page.getByTestId('approve').click();
    await expect(page.getByTestId('approval-status')).toHaveText('APPROVED');

    await openScreen(page, 'nav-delivery', 'Delivery desk');
    await page.getByTestId('consignment-id').fill(trip.consignmentId);
    await page.getByTestId('lookup').getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('proof-status')).toHaveText('REVERSED');
  });
});
