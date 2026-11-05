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
import { apiSession, cutConsignments, newDeskOrder, openScreen, signIn, tag, withoutPermissions } from './support';

/** Opens an order on the desk by its id. */
async function openOrder(page: Page, orderId: string): Promise<void> {
  await openScreen(page, 'nav-order-desk', 'Order desk');
  await page.getByTestId('order-id').fill(orderId);
  await page.getByTestId('open-order').getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('order-panel')).toBeVisible();
}

test.describe('closing an order', () => {
  test('a normal close is refused by code, then a short close with a reason ends the order', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'VALIDATED');
    await signIn(page);
    await openOrder(page, order.orderId);
    const close = page.getByTestId('close-order');

    await test.step('a normal close says what is outstanding', async () => {
      await close.getByTestId('close-normal').click();
      await expect(page.getByTestId('order-refusal')).toHaveText('order-not-complete');
      await expect(page.getByTestId('order-error')).toContainText('line 1 is not planned');
    });

    await test.step('a short close needs a reason', async () => {
      await close.getByTestId('close-short').click();
      await expect(page.getByTestId('order-refusal')).toHaveText('close-reason-required');
      await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
    });

    await test.step('with a reason it closes the order and its line', async () => {
      await close.getByLabel('Close reason').fill('customer needs no more');
      await close.getByTestId('close-short').click();
      await expect(page.getByTestId('order-done')).toHaveText('Order short-closed.');
      await expect(page.getByTestId('order-status')).toHaveText('CLOSED');
      await expect(page.getByTestId('order-close-kind')).toHaveText('SHORT');
      await expect(page.getByTestId('order-close-reason')).toHaveText('customer needs no more');
      await expect(page.getByTestId('order-line').getByTestId('line-closed')).toHaveText('SHORT');
      // A closed order offers nothing more to close or edit.
      await expect(page.getByTestId('close-order')).toHaveCount(0);
      await expect(page.getByTestId('close-line')).toHaveCount(0);
      await expect(page.getByTestId('amend-order')).toHaveCount(0);
    });

    await test.step('the history shows the close and why', async () => {
      await page.getByTestId('tab-history').click();
      const newest = page.getByTestId('order-history').getByTestId('history-entry').first();
      await expect(newest.getByTestId('history-action')).toHaveText('ORDER_CLOSED');
      await expect(newest).toContainText('VALIDATED → CLOSED');
      await expect(newest).toContainText('customer needs no more');
    });
  });

  test('cut freight that has not left refuses a short close, and a forced close ends the order', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const order = await newDeskOrder(request, session, tag(info), 'VALIDATED');
    await cutConsignments(request, session, order.orderId);
    await signIn(page);
    await openOrder(page, order.orderId);
    await expect(page.getByTestId('order-status')).toHaveText('FULLY_PLANNED');
    const close = page.getByTestId('close-order');

    await close.getByLabel('Close reason').fill('customer insolvent');
    await close.getByTestId('close-short').click();
    await expect(page.getByTestId('order-refusal')).toHaveText('order-freight-pending');
    await expect(page.getByTestId('order-status')).toHaveText('FULLY_PLANNED');

    // A refused close keeps the reason, ready for the forced one.
    await expect(close.getByLabel('Close reason')).toHaveValue('customer insolvent');
    await close.getByTestId('close-force').click();
    await expect(page.getByTestId('order-done')).toHaveText('Order force-closed.');
    await expect(page.getByTestId('order-status')).toHaveText('CLOSED');
    await expect(page.getByTestId('order-close-kind')).toHaveText('FORCE');
  });

  test('closing one line leaves the order open, and closing the last one closes it', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'VALIDATED', 2);
    await signIn(page);
    await openOrder(page, order.orderId);
    const second = page.getByTestId('order-line').filter({ hasText: 'COIL-2' });
    const first = page.getByTestId('order-line').filter({ hasText: 'COIL-1' });

    await page.getByTestId('close-order').getByLabel('Close reason').fill('second drop cancelled');
    await second.getByTestId('close-line').click();
    await expect(page.getByTestId('order-done')).toHaveText('Line 2 closed.');
    await expect(second.getByTestId('line-closed')).toHaveText('SHORT');
    await expect(second.getByTestId('close-line')).toHaveCount(0);
    await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
    // The reason went with the close.
    await expect(page.getByTestId('close-order').getByLabel('Close reason')).toHaveValue('');

    await first.getByTestId('close-line').click();
    await expect(page.getByTestId('order-refusal')).toHaveText('close-reason-required');
    await page.getByTestId('close-order').getByLabel('Close reason').fill('first drop cancelled');
    await first.getByTestId('force-close-line').click();
    await expect(page.getByTestId('order-done')).toHaveText('Line 1 closed.');
    await expect(page.getByTestId('order-status')).toHaveText('CLOSED');
    await expect(page.getByTestId('order-close-kind')).toHaveText('FORCE');
  });
});

test.describe('what each person is offered to close', () => {
  test('without ORDER_FORCE_CLOSE nothing can be forced, but a short close is still offered', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'VALIDATED');
    await withoutPermissions(page, ['ORDER_FORCE_CLOSE']);
    await signIn(page);
    await openOrder(page, order.orderId);

    await expect(page.getByTestId('close-short')).toBeVisible();
    await expect(page.getByTestId('close-line')).toHaveCount(1);
    await expect(page.getByTestId('close-force')).toHaveCount(0);
    await expect(page.getByTestId('force-close-line')).toHaveCount(0);
  });

  test('without the right to change orders, nothing is offered to close', async ({ page, request }, info) => {
    const order = await newDeskOrder(request, await apiSession(request), tag(info), 'VALIDATED');
    await withoutPermissions(page, ['ORDER_UPDATE']);
    await signIn(page);
    await openOrder(page, order.orderId);

    await expect(page.getByTestId('order-status')).toHaveText('VALIDATED');
    await expect(page.getByTestId('close-order')).toHaveCount(0);
    await expect(page.getByTestId('close-line')).toHaveCount(0);
  });
});
