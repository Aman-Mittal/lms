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
import { apiSession, newBillingClient, newConsignment, openScreen, signIn, tag, withoutPermissions } from './support';
import { deliveredTrip } from './support-commercial';

/*
 * A proof of delivery decides when money moves: the carrier's bill waits for
 * clean proofs (or a person releases it with a reason), and a client's transport
 * waits for proofs unless someone marks a consignment billable by hand. Both
 * consoles run every test here.
 */

async function openBill(page: Page, billId: string) {
  await openScreen(page, 'nav-commercial', 'Commercial');
  const form = page.getByTestId('open-bill');
  await form.getByLabel('Bill id').fill(billId);
  await form.getByRole('button', { name: 'Open' }).click();
  const panel = page.getByTestId('bill-panel');
  await expect(panel).toBeVisible();
  return panel;
}

test.describe('proof of delivery and money', () => {
  test('a bill waiting for proofs says so, refuses settlement, and is released with a reason', async ({ page, request }, info) => {
    test.setTimeout(120_000);
    const salt = tag(info);
    const trip = await deliveredTrip(request, await apiSession(request), salt, 'none');
    await signIn(page);
    const panel = await openBill(page, trip.billId);
    await expect(panel.getByTestId('bill-status')).toHaveText('DRAFT');
    await expect(panel.getByTestId('bill-awaiting-proof')).toContainText('Waiting for clean proofs of delivery');

    await test.step('matching is refused while the proof is outstanding', async () => {
      const match = panel.getByTestId('match-bill');
      await match.getByLabel('Vendor\'s claimed amount').fill('20000');
      await match.getByRole('button', { name: 'Match claim' }).click();
      await expect(page.getByTestId('bill-refusal')).toContainText('settlement-awaiting-proof');
      await expect(panel.getByTestId('bill-status')).toHaveText('DRAFT');
    });

    await test.step('released by hand, with the reason on the bill', async () => {
      const release = panel.getByTestId('release-proof-hold');
      await release.getByLabel('Reason for releasing the proof hold').fill(`Consignee confirmed by phone ${salt}`);
      await release.getByRole('button', { name: 'Release proof hold' }).click();
      await expect(panel.getByTestId('bill-proof-release')).toContainText(`Consignee confirmed by phone ${salt}`);
      await expect(panel.getByTestId('bill-awaiting-proof')).toHaveCount(0);
      await expect(panel.getByTestId('release-proof-hold')).toHaveCount(0);
    });

    await test.step('and then it settles', async () => {
      const match = panel.getByTestId('match-bill');
      await match.getByLabel('Vendor\'s claimed amount').fill('20000');
      await match.getByRole('button', { name: 'Match claim' }).click();
      await expect(panel.getByTestId('bill-status')).toHaveText('APPROVED_FOR_PAYMENT');
    });
  });

  test('a proof recording damage keeps the bill waiting, and without the right nothing releases it', async ({ page, request }, info) => {
    test.setTimeout(120_000);
    const trip = await deliveredTrip(request, await apiSession(request), tag(info), 'DAMAGE');
    await withoutPermissions(page, ['SETTLEMENT_HOLD_RELEASE']);
    await signIn(page);
    const panel = await openBill(page, trip.billId);
    await expect(panel.getByTestId('bill-awaiting-proof')).toBeVisible();
    await expect(panel.getByTestId('release-proof-hold')).toHaveCount(0);
  });

  test('a consignment is marked billable without its proof, and a mark that names nothing is refused', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const client = await newBillingClient(request, session);
    const consignment = await newConsignment(request, session);
    await signIn(page);
    await openScreen(page, 'nav-client-billing', 'Client billing');
    await page.getByTestId('client-id').fill(client.id);
    await page.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('client-desk')).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Billable' })).toBeVisible();
    await expect(page.getByRole('columnheader', { name: 'Transport billed' })).toBeVisible();
    await expect(page.getByTestId('contract-row')).toContainText('On proof of delivery');

    const form = page.getByTestId('mark-billable');
    await form.getByLabel('Consignment id').fill('11111111-2222-4333-8444-555555555555');
    await form.getByLabel('Reason it is billable').fill(`Paper copy signed ${tag(info)}`);
    await form.getByRole('button', { name: 'Mark billable' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('marked-billable')).toHaveCount(0);

    await form.getByLabel('Consignment id').fill(consignment);
    await form.getByLabel('Reason it is billable').fill(`Paper copy signed ${tag(info)}`);
    await form.getByRole('button', { name: 'Mark billable' }).click();
    await expect(page.getByTestId('marked-billable')).toBeVisible();
  });

  test('the delivery desk records what was short or damaged and shows the consignment delivered', async ({ page }) => {
    const consignment = '22222222-3333-4444-8555-666666666666';
    let confirmed = false;
    const cors = { 'access-control-allow-origin': '*' };
    const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
    let sent: Record<string, unknown> = {};

    await page.route(new RegExp(`/api/v1/consignments/${consignment}$`), async (route) => {
      if (route.request().method() === 'OPTIONS') {
        return route.fulfill(preflight);
      }
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json',
        body: JSON.stringify({ id: consignment, trackingRef: 'LR-POD-1', status: confirmed ? 'DELIVERED' : 'IN_TRANSIT',
          consigneeName: 'Consignee', destinationTerminalCode: 'DEST', freightMode: 'FTL',
          proofStatus: confirmed ? 'CONFIRMED' : 'OTP_ISSUED', proofCondition: confirmed ? 'DAMAGE' : 'CLEAN' }) });
    });
    await page.route(new RegExp(`/api/v1/deliveries/${consignment}`), async (route) => {
      const request = route.request();
      if (request.method() === 'OPTIONS') {
        return route.fulfill(preflight);
      }
      if (request.method() === 'POST' && request.url().endsWith('/proof')) {
        sent = request.postDataJSON() as Record<string, unknown>;
        confirmed = true;
        return route.fulfill({ status: 204, headers: cors });
      }
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json',
        body: JSON.stringify(confirmed
          ? { consignmentId: consignment, status: 'CONFIRMED', recipientName: 'Asha Rao', deliveredAt: '2026-10-30T10:00:00Z',
              goodsCondition: 'DAMAGE', remark: 'Two cartons crushed' }
          : { consignmentId: consignment, status: 'OTP_ISSUED', otpExpiresAt: '2099-01-01T00:00:00Z', goodsCondition: 'CLEAN' }) });
    });

    await signIn(page);
    await page.getByTestId('nav-delivery').click();
    await page.getByTestId('consignment-id').fill(consignment);
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('proof-status')).toHaveText('OTP_ISSUED');
    await expect(page.getByTestId('delivery-consignment-status')).toContainText('IN_TRANSIT');

    const form = page.getByTestId('record-proof');
    await form.getByLabel('Code').fill('123456');
    await form.getByLabel('Recipient name').fill('Asha Rao');
    await form.getByLabel('Goods received').selectOption('DAMAGE');
    await form.getByLabel('Remark').fill('Two cartons crushed');
    await form.getByRole('button', { name: 'Record proof' }).click();

    await expect(page.getByTestId('proof-status')).toHaveText('CONFIRMED');
    await expect(page.getByTestId('proof-condition')).toHaveText('DAMAGE');
    await expect(page.getByTestId('proof-remark')).toHaveText('Two cartons crushed');
    await expect(page.getByTestId('delivery-consignment-status')).toContainText('DELIVERED');
    expect(sent).toMatchObject({ otp: '123456', recipientName: 'Asha Rao', goodsCondition: 'DAMAGE', remark: 'Two cartons crushed' });
  });
});
