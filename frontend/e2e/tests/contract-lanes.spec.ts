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
  apiSession, newBillingPartner, newDeskOrder, newSite, openScreen, publishContract, resetApprovalPolicy,
  setApprovalPolicy, signIn, tag, type Session,
} from './support';

/*
 * Contract lanes in both consoles: edited on a draft contract and frozen once it is in force, and
 * picked on the order desk, which then shows the contract, the lane and the promised days.
 *
 * The demo tenant has one administrator, so the spec lets the requester decide, as the contract
 * lifecycle spec does, and puts the contract policy back to the code default afterwards.
 */

/** A `YYYY-MM-DD` day this many days from today, in UTC like the API. */
function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

/** Opens the client's desk, whatever it holds. */
async function openClient(page: Page, clientId: string): Promise<void> {
  await openScreen(page, 'nav-client-billing', 'Client billing');
  await page.getByTestId('client-id').fill(clientId);
  await page.getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('client-desk')).toBeVisible();
  await expect(page.getByTestId('contract-lifecycle')).toBeVisible();
}

/** Opens the version row for the contract code. */
async function openVersion(page: Page, code: string): Promise<void> {
  await page.getByTestId('version-row').filter({ hasText: code }).getByTestId('open-version').click();
  await expect(page.getByTestId('contract-panel')).toBeVisible();
}

/** Approves the one open request whose summary mentions the text, in the approvals inbox. */
async function approve(page: Page, text: string): Promise<void> {
  await openScreen(page, 'nav-approvals', 'Approvals');
  const row = page.getByTestId('approvals').getByRole('row').filter({ hasText: text });
  await expect(row).toHaveCount(1);
  await row.getByRole('button').first().click();
  await expect(page.getByTestId('approval-detail')).toBeVisible();
  await page.getByTestId('approve').click();
  await expect(page.getByTestId('approval-status')).toHaveText('APPROVED');
}

test.describe('contract lanes', () => {
  let session: Session;

  test.beforeEach(async ({ request }) => {
    session = await apiSession(request);
    await setApprovalPolicy(request, session, 'CONTRACT_ACTIVATION', { required: true, allowSelfApproval: true });
  });

  test.afterEach(async ({ request }) => {
    await resetApprovalPolicy(request, session, 'CONTRACT_ACTIVATION');
  });

  test('lanes are edited on a draft contract and frozen once it is in force', async ({ page, request }, info) => {
    const client = await newBillingPartner(request, session);
    const from = await newSite(request, session, `ZZ-LN-A-${tag(info)}`, 'CUSTOMER_SITE');
    const to = await newSite(request, session, `ZZ-LN-B-${tag(info)}`, 'CUSTOMER_SITE');
    const code = `LN-${tag(info)}`;
    await signIn(page);
    await openClient(page, client.id);

    await test.step('a draft starts with no lanes', async () => {
      await page.getByTestId('new-contract-code').fill(code);
      await page.getByTestId('new-contract-effectiveFrom').fill(inDays(-10));
      await page.getByTestId('new-contract-transportRatePerKg').fill('5');
      await page.getByTestId('create-draft').click();
      await expect(page.getByTestId('contract-status')).toHaveText('DRAFT');
      await expect(page.getByTestId('no-lanes')).toBeVisible();
      await expect(page.getByTestId('check-LANES')).toContainText('Define at least one lane');
    });

    await test.step('a lane is added, priced per tonne, with the days it takes', async () => {
      await page.getByTestId('lane-form-code').fill('E2E-1');
      await page.getByTestId('lane-form-origin').fill(from.id);
      await page.getByTestId('lane-form-destination').fill(to.id);
      await page.getByTestId('lane-form-basis').selectOption('PER_TONNE');
      await page.getByTestId('lane-form-rate').fill('9000');
      await page.getByTestId('lane-form-days').fill('4');
      await page.getByTestId('lane-form-load-type').fill('32ft');
      await page.getByTestId('add-lane-button').click();
      await expect(page.getByTestId('contract-message')).toHaveText('Lane added.');
      await expect(page.getByTestId('lane-row')).toHaveCount(1);
      const row = page.getByTestId('lane-row').first();
      await expect(row).toContainText('E2E-1');
      await expect(row).toContainText('PER_TONNE');
      await expect(row).toContainText('32FT');
      await expect(page.getByTestId('check-LANES')).toContainText('passes');
    });

    await test.step('a second lane with the same code is refused by name', async () => {
      await page.getByTestId('lane-form-code').fill('e2e-1');
      await page.getByTestId('lane-form-origin').fill(to.id);
      await page.getByTestId('lane-form-destination').fill(from.id);
      await page.getByTestId('lane-form-rate').fill('5');
      await page.getByTestId('add-lane-button').click();
      await expect(page.getByTestId('contract-refusal')).toHaveText('client-contract-lane-duplicated');
      await expect(page.getByTestId('lane-row')).toHaveCount(1);
    });

    await test.step('another lane is added and taken away again', async () => {
      await page.getByTestId('lane-form-code').fill('E2E-2');
      await page.getByTestId('lane-form-origin').fill(to.id);
      await page.getByTestId('lane-form-destination').fill(from.id);
      await page.getByTestId('lane-form-rate').fill('5');
      await page.getByTestId('add-lane-button').click();
      await expect(page.getByTestId('lane-row')).toHaveCount(2);
      await page.getByTestId('lane-row').filter({ hasText: 'E2E-2' }).getByTestId('remove-lane').click();
      await expect(page.getByTestId('contract-message')).toHaveText('Lane removed.');
      await expect(page.getByTestId('lane-row')).toHaveCount(1);
    });

    await test.step('direct orders are refused and the load types are listed', async () => {
      await page.getByTestId('allow-direct').uncheck();
      await page.getByTestId('allowed-load-types').fill('32ft, open');
      await page.getByTestId('save-allowances').click();
      await expect(page.getByTestId('contract-message')).toHaveText('Allowances saved.');
      await expect(page.getByTestId('contract-direct')).toHaveText('Not allowed');
      await expect(page.getByTestId('contract-load-types')).toHaveText('32FT, OPEN');
    });

    await test.step('once verified the lanes can no longer be changed', async () => {
      await page.getByTestId('request-verification').click();
      await expect(page.getByTestId('contract-status')).toHaveText('PENDING_VERIFICATION');
      await approve(page, code);
      await openClient(page, client.id);
      await openVersion(page, code);
      await expect(page.getByTestId('contract-status')).toHaveText('ACTIVE');
      await expect(page.getByTestId('lane-row')).toHaveCount(1);
      await expect(page.getByTestId('remove-lane')).toHaveCount(0);
      await expect(page.getByTestId('add-lane')).toHaveCount(0);
    });
  });

  test('the order desk offers the contract lanes, fills the order from one and shows the promise', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, session, tag(info), 'NONE');
    const code = `LD-${tag(info)}`;
    await publishContract(request, session, {
      orgUnitId: session.orgUnitId, code, clientPartnerId: order.customers[0].id, effectiveFrom: inDays(-10),
      transportRatePerKg: 5,
      lanes: [{
        laneCode: 'E2E-DESK', originTerminalId: order.originId, destinationTerminalId: order.destinationId,
        rateBasis: 'PER_TONNE', rate: 9000, transitDays: 4, loadType: '32ft',
      }],
    });
    await signIn(page);
    await page.getByTestId('nav-order-desk').click();
    await expect(page.getByRole('heading', { name: 'Order desk', level: 1 })).toBeVisible();

    await test.step('picking the lane for the customer fills the origin and the load type', async () => {
      const raise = page.getByTestId('raise-order');
      await raise.getByLabel('Order number').fill(order.orderNo);
      await raise.getByLabel('New order customer id').fill(order.customers[0].id);
      await expect(page.getByTestId('raise-lane')).toBeVisible();
      await expect(page.getByTestId('raise-lane')).toContainText('E2E-DESK');
      await page.getByTestId('raise-lane').selectOption({ index: 1 });
      await expect(raise.getByLabel('New order origin terminal id')).toHaveValue(order.originId);
      await expect(raise.getByLabel('New order load type')).toHaveValue('32FT');
      await raise.getByRole('button', { name: 'Raise order' }).click();
      await expect(page.getByTestId('order-panel').getByRole('heading', { name: order.orderNo })).toBeVisible();
    });

    await test.step('the order shows its contract, lane and promised days', async () => {
      await expect(page.getByTestId('order-contract')).toHaveText(code);
      await expect(page.getByTestId('order-lane')).toHaveText('E2E-DESK');
      await expect(page.getByTestId('order-load-type')).toHaveText('32FT');
      await expect(page.getByTestId('order-transit')).toHaveText('4');
    });

    await test.step('a line to the lane\'s destination validates, and the expected delivery date is set', async () => {
      const editor = page.getByTestId('edit-draft');
      await editor.getByLabel('Line 1 material code').fill('PALLET-1');
      await editor.getByLabel('Line 1 description').fill('Boxed parts');
      await editor.getByLabel('Line 1 weight kg').fill('300');
      await editor.getByLabel('Line 1 consignee id').fill(order.consigneeId);
      await editor.getByLabel('Line 1 destination id').fill(order.destinationId);
      await editor.getByTestId('save-lines').click();
      await expect(page.getByTestId('order-done')).toHaveText('Lines saved.');
      await page.getByTestId('validate-order').click();
      await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
      await expect(page.getByTestId('order-lane')).toHaveText('E2E-DESK');
      await expect(page.getByTestId('order-expected')).toHaveText(/^\d{4}-\d{2}-\d{2}$/);
    });
  });
});
