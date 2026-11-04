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
  apiSession, cutConsignments, editOrderElsewhere, newDeskOrder, printed, signIn, stubPrint, tag, withoutPermissions,
} from './support';

async function openDesk(page: Page): Promise<void> {
  await page.getByTestId('nav-order-desk').click();
  await expect(page.getByRole('heading', { name: 'Order desk', level: 1 })).toBeVisible();
}

/** Opens an order on the desk by its id. */
async function openOrder(page: Page, orderId: string): Promise<void> {
  await openDesk(page);
  await page.getByTestId('order-id').fill(orderId);
  await page.getByTestId('open-order').getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('order-panel')).toBeVisible();
}

test.describe('order desk', () => {
  test('an order is raised, its draft lines are edited, and it is validated', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'NONE');
    await signIn(page);
    await openDesk(page);

    await test.step('raise it', async () => {
      const raise = page.getByTestId('raise-order');
      await raise.getByLabel('Order number').fill(order.orderNo);
      await raise.getByLabel('New order customer id').fill(order.customers[0].id);
      await raise.getByLabel('New order origin terminal id').fill(order.originId);
      await raise.getByRole('button', { name: 'Raise order' }).click();
      await expect(page.getByTestId('order-panel').getByRole('heading', { name: order.orderNo })).toBeVisible();
      await expect(page.getByTestId('order-status')).toHaveText('DRAFT');
      await expect(page.getByTestId('order-customer')).toHaveText(order.customers[0].name);
    });

    await test.step('give it two lines, then take one away', async () => {
      const editor = page.getByTestId('edit-draft');
      await editor.getByLabel('Line 1 material code').fill('PALLET-1');
      await editor.getByLabel('Line 1 description').fill('Boxed parts');
      await editor.getByLabel('Line 1 weight kg').fill('300');
      await editor.getByLabel('Line 1 consignee id').fill(order.consigneeId);
      await editor.getByLabel('Line 1 destination id').fill(order.destinationId);
      await editor.getByTestId('add-line').click();
      // The new line goes where the last one went.
      await expect(editor.getByLabel('Line 2 destination id')).toHaveValue(order.destinationId);
      await editor.getByLabel('Line 2 material code').fill('PALLET-2');
      await editor.getByLabel('Line 2 description').fill('Loose parts');
      await editor.getByLabel('Line 2 weight kg').fill('200');
      await editor.getByTestId('save-lines').click();
      await expect(page.getByTestId('order-done')).toHaveText('Lines saved.');
      await expect(page.getByTestId('order-line')).toHaveCount(2);
      await expect(page.getByTestId('order-weight')).toHaveText('500');

      await page.getByTestId('order-line').filter({ hasText: 'PALLET-2' }).getByTestId('remove-line').click();
      await expect(page.getByTestId('order-done')).toHaveText('Line 2 removed.');
      await expect(page.getByTestId('order-line')).toHaveCount(1);
      await expect(page.getByTestId('order-weight')).toHaveText('300');
    });

    await test.step('move it to the other customer, then validate it', async () => {
      await page.getByTestId('header-editor').getByLabel('Customer id').fill(order.customers[1].id);
      await page.getByTestId('save-header').click();
      await expect(page.getByTestId('order-customer')).toHaveText(order.customers[1].name);
      await page.getByTestId('validate-order').click();
      await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
      await expect(page.getByTestId('edit-draft')).toHaveCount(0);
      await expect(page.getByTestId('amend-order')).toBeVisible();
    });
  });

  test('a validated order is amended, keeping its measurements, until consignments are cut', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'VALIDATED');
    await signIn(page);
    await openOrder(page, order.orderId);
    await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
    const before = Number(await page.getByTestId('order-version').textContent());

    await page.getByTestId('amend-order').getByLabel('Line 1 weight kg').fill('650');
    await page.getByTestId('amend').click();
    await expect(page.getByTestId('order-done')).toHaveText('Order amended.');
    await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
    await expect(page.getByTestId('order-weight')).toHaveText('650');
    expect(Number(await page.getByTestId('order-version').textContent())).toBeGreaterThan(before);
    // The line was restated whole, and its measurements went with it.
    await expect(page.getByTestId('order-line')).toContainText('1.2 × 0.8 × 1.5');

    await page.getByTestId('generate-consignments').click();
    await expect(page.getByTestId('order-done')).toHaveText('Consignments cut.');
    await expect(page.getByTestId('order-status')).toHaveText('FULLY_PLANNED');
    // The lines now belong to consignments: nothing is offered to amend, and the rule is named.
    await expect(page.getByTestId('amend-order')).toHaveCount(0);
    await expect(page.getByTestId('not-amendable')).toContainText('order-has-consignments');
    await expect(page.getByTestId('order-line')).toContainText('Yes');
  });

  test('an amendment from a read taken before consignments were cut is refused, and nothing changes', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const order = await newDeskOrder(request, session, tag(info), 'VALIDATED');
    await signIn(page);
    await openOrder(page, order.orderId);

    // Another planner cuts the freight after this desk read the order.
    await cutConsignments(request, session, order.orderId);

    await page.getByTestId('amend-order').getByLabel('Line 1 weight kg').fill('700');
    await page.getByTestId('amend').click();
    await expect(page.getByTestId('order-refusal')).toHaveText('concurrent-modification');
    await page.getByTestId('reload-order').click();
    await expect(page.getByTestId('order-status')).toHaveText('FULLY_PLANNED');
    await expect(page.getByTestId('order-weight')).toHaveText('500');
    await expect(page.getByTestId('not-amendable')).toBeVisible();
  });

  test('an edit made from a stale read is refused, and goes through once the order is read again', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const order = await newDeskOrder(request, session, tag(info), 'DRAFT');
    await signIn(page);
    await openOrder(page, order.orderId);
    await expect(page.getByTestId('order-customer')).toHaveText(order.customers[0].name);

    // Someone else edits the order after this desk read it.
    await editOrderElsewhere(request, session, order, order.customers[1].id);

    await page.getByTestId('edit-draft').getByLabel('Line 1 weight kg').fill('450');
    await page.getByTestId('save-lines').click();
    await expect(page.getByTestId('order-refusal')).toHaveText('concurrent-modification');
    await expect(page.getByTestId('order-weight')).toHaveText('500');

    await page.getByTestId('reload-order').click();
    await expect(page.getByTestId('order-customer')).toHaveText(order.customers[1].name);
    await expect(page.getByTestId('order-error')).toHaveCount(0);
    await page.getByTestId('edit-draft').getByLabel('Line 1 weight kg').fill('450');
    await page.getByTestId('save-lines').click();
    await expect(page.getByTestId('order-done')).toHaveText('Lines saved.');
    await expect(page.getByTestId('order-weight')).toHaveText('450');
  });

  test('a cancelled order offers nothing more to edit', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'DRAFT');
    await signIn(page);
    await openOrder(page, order.orderId);

    await page.getByLabel('Cancellation reason').fill('customer withdrew');
    await page.getByTestId('cancel-order').getByRole('button', { name: 'Cancel order' }).click();
    await expect(page.getByTestId('order-status')).toHaveText('CANCELLED');
    await expect(page.getByTestId('edit-draft')).toHaveCount(0);
    await expect(page.getByTestId('amend-order')).toHaveCount(0);
    await expect(page.getByTestId('validate-order')).toHaveCount(0);
  });
});

test.describe('what each person is offered', () => {
  test('without the right to change orders, an order is read and printed but not edited', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'DRAFT');
    await withoutPermissions(page, ['ORDER_CREATE', 'ORDER_UPDATE', 'LOAD_CREATE']);
    await stubPrint(page);
    await signIn(page);
    await openOrder(page, order.orderId);

    await expect(page.getByTestId('raise-order')).toHaveCount(0);
    await expect(page.getByTestId('order-line')).toHaveCount(1);
    await expect(page.getByTestId('edit-draft')).toHaveCount(0);
    await expect(page.getByTestId('validate-order')).toHaveCount(0);
    await expect(page.getByTestId('remove-line')).toHaveCount(0);

    await page.getByTestId('print-order').click();
    await expect(page.getByTestId('order-slip').getByTestId('slip-line')).toHaveCount(1);
    await page.getByTestId('print-now').click();
    expect(await printed(page)).toBe(1);
  });

  test('an order that does not exist is reported, not shown', async ({ page }) => {
    await signIn(page);
    await openDesk(page);
    await page.getByTestId('order-id').fill(crypto.randomUUID());
    await page.getByTestId('open-order').getByRole('button', { name: 'Open' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('order-panel')).toHaveCount(0);
  });
});
