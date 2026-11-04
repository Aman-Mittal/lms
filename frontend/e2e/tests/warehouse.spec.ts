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

import { expect, test } from '@playwright/test';
import { apiSession, printed, signIn, stubPrint, tag, withoutPermissions } from './support';
import { newFloor, openFloor, outboundOrder, received, stocked } from './support-warehouse';

test.describe('warehouse operations', () => {
  test('a delivery is expected, counted at the dock and put away, and shows on hand', async ({ page, request }, info) => {
    const floor = await newFloor(request, await apiSession(request));
    await signIn(page);
    await openFloor(page, floor.id);

    await test.step('expect a delivery of 20', async () => {
      const form = page.getByTestId('create-asn');
      await form.getByLabel('ASN number').fill(`E2E-ASN-${tag(info)}`);
      await form.getByLabel('Line 1 SKU').fill(floor.skuId);
      await form.getByLabel('Line 1 quantity').fill('20');
      await form.getByRole('button', { name: 'Create ASN' }).click();
      await expect(page.getByTestId('asn-status')).toHaveText('EXPECTED');
      await expect(page.getByTestId('asn-row')).toHaveCount(1);
    });

    await test.step('count all 20 in', async () => {
      const form = page.getByTestId('receive-asn');
      await expect(form.getByLabel('Line 1 received')).toHaveValue('20');
      await form.getByRole('button', { name: 'Receive' }).click();
      await expect(page.getByTestId('asn-status')).toHaveText('RECEIVED');
      await expect(page.getByTestId('receive-asn')).toHaveCount(0);
    });

    await test.step('put them away in the bin', async () => {
      const form = page.getByTestId('putaway');
      await form.getByLabel('Bin', { exact: true }).fill(floor.binId);
      await form.getByLabel('SKU', { exact: true }).fill(floor.skuId);
      await form.getByLabel('Quantity', { exact: true }).fill('20');
      await form.getByRole('button', { name: 'Put away' }).click();
      await expect(page.getByTestId('wh-done').filter({ hasText: 'Put away 20' })).toBeVisible();
    });

    await test.step('more than the dock holds is refused by name', async () => {
      const form = page.getByTestId('putaway');
      await form.getByLabel('Bin', { exact: true }).fill(floor.binId);
      await form.getByLabel('SKU', { exact: true }).fill(floor.skuId);
      await form.getByLabel('Quantity', { exact: true }).fill('5');
      await form.getByRole('button', { name: 'Put away' }).click();
      await expect(page.getByTestId('wh-refusal')).toHaveText('dock-stock-insufficient');
    });

    await test.step('the bin holds all 20', async () => {
      await page.getByTestId('tab-stock').click();
      const rows = page.getByTestId('stock-row');
      await expect(rows).toHaveCount(1);
      await expect(rows).toContainText('BIN');
      await expect(rows).toContainText(floor.binCode);
      await expect(rows.locator('td').last()).toHaveText('20');
    });
  });

  test('an order is picked, packed and dispatched, with a pick list and a labelled unit', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const floor = await newFloor(request, session);
    await stocked(request, session, floor, 30);
    await stubPrint(page);
    await signIn(page);
    await openFloor(page, floor.id);
    await page.getByTestId('tab-outbound').click();
    const status = page.getByTestId('outbound-status');

    await test.step('send 12 out', async () => {
      const form = page.getByTestId('create-outbound');
      await form.getByLabel('Order number').fill(`E2E-OUT-${tag(info)}`);
      await form.getByLabel('Line 1 SKU').fill(floor.skuId);
      await form.getByLabel('Line 1 quantity').fill('12');
      await form.getByRole('button', { name: 'Create order' }).click();
      await expect(status).toHaveText('OPEN');
    });

    await test.step('the pick list says where to pick from, and prints', async () => {
      await page.getByTestId('print-pick-list').click();
      const rows = page.getByTestId('pick-list').getByTestId('pick-list-row');
      await expect(rows).toHaveCount(1);
      await expect(rows).toContainText(`${floor.binCode} (30)`);
      await page.getByTestId('pick-list').getByTestId('print-now').click();
      expect(await printed(page)).toBe(1);
    });

    await test.step('pick, then pack into a handling unit', async () => {
      await page.getByTestId('pick-order').click();
      await expect(status).toHaveText('PICKED');
      await page.getByTestId('pack-order').click();
      await expect(status).toHaveText('PACKED');
      await expect(page.getByTestId('unit-status')).toHaveText('PACKED');
      await expect(page.getByTestId('unit-content')).toContainText('12');
    });

    await test.step('the unit\'s label carries its QR code', async () => {
      await page.getByTestId('print-unit-label').click();
      const label = page.getByTestId('unit-label');
      await expect(label.getByTestId('qr')).toHaveAttribute('data-payload', /^LMS:HU:.+/);
      await label.getByTestId('print-now').click();
      expect(await printed(page)).toBe(2);
    });

    await test.step('dispatch it', async () => {
      const reference = `E2E-DSP-${tag(info)}`;
      const form = page.getByTestId('dispatch-unit');
      await form.getByLabel('Dispatch reference').fill(reference);
      await form.getByRole('button', { name: 'Dispatch' }).click();
      await expect(page.getByTestId('unit-status')).toHaveText('DISPATCHED');
      await expect(page.getByTestId('unit-row')).toContainText(reference);
      await expect(page.getByTestId('dispatch-unit')).toHaveCount(0);
    });
  });

  test('two orders are released as one wave and picked together', async ({ page, request }) => {
    const session = await apiSession(request);
    const floor = await newFloor(request, session);
    await stocked(request, session, floor, 20);
    const first = await outboundOrder(request, session, floor, 5);
    const second = await outboundOrder(request, session, floor, 6);
    await signIn(page);
    await openFloor(page, floor.id);
    await page.getByTestId('tab-waves').click();

    const form = page.getByTestId('release-wave');
    await form.getByLabel(`Wave ${first}`).check();
    await form.getByLabel(`Wave ${second}`).check();
    await form.getByRole('button', { name: 'Release wave' }).click();
    await expect(page.getByTestId('wave-status')).toHaveText('RELEASED');
    await expect(page.getByTestId('wave-order')).toHaveCount(2);

    await page.getByTestId('pick-wave').click();
    await expect(page.getByTestId('wave-status')).toHaveText('PICKED');
    await expect(page.getByTestId('pick-wave')).toHaveCount(0);
  });

  test('damaged units wait in quarantine until released into a bin', async ({ page, request }) => {
    const session = await apiSession(request);
    const floor = await newFloor(request, session);
    await received(request, session, floor, 10, 3);
    await signIn(page);
    await openFloor(page, floor.id);
    await page.getByTestId('tab-stock').click();

    const quarantined = page.getByTestId('stock-row').filter({ hasText: 'QUARANTINE' });
    await expect(quarantined).toHaveCount(1);
    await expect(quarantined.locator('td').last()).toHaveText('3');

    const form = page.getByTestId('release-quarantine');
    await form.getByLabel('Bin', { exact: true }).fill(floor.binId);
    await form.getByLabel('SKU', { exact: true }).fill(floor.skuId);
    await form.getByLabel('Quantity', { exact: true }).fill('3');
    await form.getByLabel('Reason', { exact: true }).fill('Inspected and sound');
    await form.getByRole('button', { name: 'Release' }).click();

    await expect(quarantined).toHaveCount(0);
    const binned = page.getByTestId('stock-row').filter({ hasText: floor.binCode });
    await expect(binned.locator('td').last()).toHaveText('3');
  });

  test('without the rights to work the floor, stock and setup are read but nothing is offered', async ({ page, request }) => {
    const session = await apiSession(request);
    const floor = await newFloor(request, session);
    await stocked(request, session, floor, 5);
    await withoutPermissions(page, ['WAREHOUSE_OPERATE', 'INVENTORY_ADJUST', 'WAREHOUSE_SETUP']);
    await signIn(page);
    await openFloor(page, floor.id);

    await expect(page.getByTestId('asn-row')).toHaveCount(1);
    await expect(page.getByTestId('create-asn')).toHaveCount(0);
    await expect(page.getByTestId('putaway')).toHaveCount(0);

    await page.getByTestId('tab-stock').click();
    await expect(page.getByTestId('stock-row').locator('td').last()).toHaveText('5');
    await expect(page.getByTestId('adjust-stock')).toHaveCount(0);
    await expect(page.getByTestId('release-quarantine')).toHaveCount(0);

    await page.getByTestId('tab-outbound').click();
    await expect(page.getByTestId('create-outbound')).toHaveCount(0);

    await page.getByTestId('tab-setup').click();
    await expect(page.getByTestId('bin-row')).toHaveCount(1);
    await expect(page.getByTestId('create-bin')).toHaveCount(0);
    await expect(page.getByTestId('create-sku')).toHaveCount(0);
  });

  test('without the right to read the warehouse, it is not in the sidebar', async ({ page }) => {
    await withoutPermissions(page, ['WAREHOUSE_READ']);
    await signIn(page);
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-warehouse')).toHaveCount(0);
    await expect(page.getByTestId('nav-order-desk')).toBeVisible();
  });
});
