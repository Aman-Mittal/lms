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
import { signIn } from './support';
import {
  consignmentId, otherConsignmentId, stubEway, stubEwaySession, tripId,
} from './support-eway';

/*
 * The e-way bill desk, in both consoles. The API is stubbed so the same assertions run whatever the demo
 * tenant holds; the stub keeps the server's rules and the spec checks what each console sends. Set
 * E2E_STUB_SESSION=1 to stub the sign-in too, for a run with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubEwaySession(page);
  }
});

const rowById = (page: Page, testId: string, id: string) => page.locator(`[data-testid="${testId}"][data-id="${id}"]`);

async function openDesk(page: Page) {
  await page.getByTestId('nav-eway-bills').click();
  await expect(page.getByRole('heading', { name: 'E-way bills', level: 1 })).toBeVisible();
}

async function generate(page: Page, consignment: string, distance = '', document = '') {
  await page.getByTestId('eway-consignment-id').fill(consignment);
  await page.getByTestId('eway-generate-distance').fill(distance);
  await page.getByTestId('eway-generate-document').fill(document);
  await page.getByTestId('eway-generate').click();
}

test.describe('e-way bill desk', () => {
  test('a bill is generated, put on Part-B, extended, its vehicle changed, and cancelled', async ({ page }) => {
    const stub = await stubEway(page);
    await signIn(page);
    await openDesk(page);
    await expect(page.getByTestId('eway-none')).toBeVisible();

    await test.step('generating shows the bill in the list, under its consignment, and opens it', async () => {
      await generate(page, consignmentId, '450');
      await expect(page.getByTestId('eway-note')).toContainText('generated');
      await expect(page.getByTestId('eway-row')).toHaveCount(1);
      await expect(page.getByTestId('eway-consignment-bill')).toHaveCount(1);
      await expect(page.getByTestId('eway-detail-status')).toHaveText('ACTIVE');
      await expect(page.getByTestId('eway-detail-valid-until')).not.toBeEmpty();
      await expect(page.getByTestId('eway-detail-vehicle')).toHaveText('—');
      const id = stub.bills[0].id;
      await expect(rowById(page, 'eway-row', id)).toBeVisible();
      await expect(rowById(page, 'eway-consignment-bill', id)).toBeVisible();
      await expect(page.getByTestId('eway-detail')).toHaveAttribute('data-id', id);
      expect(stub.changes[0].body).toMatchObject({ distanceKm: 450 });
    });

    await test.step('Part-B takes a vehicle, and Enter in the field applies it', async () => {
      await page.getByTestId('eway-vehicle').fill('MH 12 AB 1234');
      await page.getByTestId('eway-vehicle').press('Enter');
      await expect(page.getByTestId('eway-detail-vehicle')).toHaveText('MH12AB1234');
      await expect(rowById(page, 'eway-row', stub.bills[0].id).getByTestId('eway-row-vehicle')).toHaveText('MH12AB1234');
      expect(stub.changes.at(-1)?.body).toMatchObject({ action: 'PART_B', vehicleNo: 'MH 12 AB 1234' });
    });

    await test.step('an extension without a reason is refused with its problem code, and changes nothing', async () => {
      await page.getByTestId('eway-extend').click();
      await expect(page.getByTestId('eway-error')).toContainText('eway-reason-required');
      await expect(page.getByTestId('eway-detail-status')).toHaveText('ACTIVE');
    });

    await test.step('with a reason it is extended', async () => {
      await page.getByTestId('eway-reason').fill('road closed');
      await page.getByTestId('eway-extend').click();
      await expect(page.getByTestId('eway-detail-status')).toHaveText('EXTENDED');
      await expect(page.getByTestId('eway-detail-extensions')).toHaveText('1');
      await expect(page.getByTestId('eway-error')).toHaveCount(0);
      expect(stub.changes.at(-1)?.body).toMatchObject({ action: 'EXTEND', reason: 'road closed' });
    });

    await test.step('the vehicle is changed with a reason, and cancelling ends the bill', async () => {
      await page.getByTestId('eway-vehicle').fill('MH 14 ZZ 0001');
      await page.getByTestId('eway-change-vehicle').click();
      await expect(page.getByTestId('eway-detail-vehicle')).toHaveText('MH14ZZ0001');
      await page.getByTestId('eway-reason').fill('goods not moved');
      await page.getByTestId('eway-cancel').click();
      await expect(page.getByTestId('eway-detail-status')).toHaveText('CANCELLED');
      await expect(page.getByTestId('eway-detail-cancel-reason')).toHaveText('goods not moved');
      await expect(rowById(page, 'eway-row', stub.bills[0].id).getByTestId('eway-row-status')).toHaveText('CANCELLED');
      await expect(page.getByTestId('eway-part-b')).toHaveCount(0);
      await expect(page.getByTestId('eway-cancel')).toHaveCount(0);
    });
  });

  test('a refusal and a second bill for the same consignment show their problem codes', async ({ page }) => {
    const stub = await stubEway(page);
    await signIn(page);
    await openDesk(page);

    await generate(page, consignmentId, '', 'LR-REFUSE-1');
    await expect(page.getByTestId('eway-error')).toContainText('eway-bill-refused');
    await expect(page.getByTestId('eway-row')).toHaveCount(0);
    expect(stub.bills).toHaveLength(0);

    await generate(page, consignmentId);
    await expect(page.getByTestId('eway-row')).toHaveCount(1);
    await expect(page.getByTestId('eway-error')).toHaveCount(0);

    await generate(page, consignmentId);
    await expect(page.getByTestId('eway-error')).toContainText('eway-bill-already-live');
    await expect(page.getByTestId('eway-row')).toHaveCount(1);
  });

  test('a consignment is found by a bill number, and a number nobody holds says so', async ({ page }) => {
    const stub = await stubEway(page);
    const bill = stub.seed({ billNo: '123456789012', consignmentId, trackingRef: 'LR-777' });
    await signIn(page);
    await openDesk(page);

    await page.getByTestId('eway-lookup-no').fill('123456789012');
    await page.getByTestId('eway-lookup-no').press('Enter');
    await expect(page.getByTestId('eway-lookup-result')).toHaveAttribute('data-id', bill.id);
    await expect(page.getByTestId('eway-lookup-consignment')).toHaveText('LR-777');
    await expect(page.getByTestId('eway-lookup-consignment')).toHaveAttribute('data-id', consignmentId);
    await expect(page.getByTestId('eway-lookup-status')).toHaveText('ACTIVE');
    expect(stub.lookups.at(-1)).toContain('/by-number/123456789012');

    await page.getByTestId('eway-lookup-open').click();
    await expect(page.getByTestId('eway-detail')).toHaveAttribute('data-id', bill.id);

    await page.getByTestId('eway-lookup-no').fill('999999999999');
    await page.getByTestId('eway-lookup-no').press('Enter');
    await expect(page.getByTestId('eway-lookup-missing')).toBeVisible();
    await expect(page.getByTestId('eway-lookup-result')).toHaveCount(0);
    await expect(page.getByTestId('eway-error')).toHaveCount(0);
  });

  test('the list is filtered by status and by text, and Enter in either field applies it', async ({ page }) => {
    const stub = await stubEway(page);
    stub.seed({ billNo: '100000000001', trackingRef: 'LR-AAA' });
    stub.seed({ billNo: '100000000002', trackingRef: 'LR-ZED', status: 'CANCELLED', cancelReason: 'x' });
    stub.seed({ billNo: '100000000003', trackingRef: 'LR-BBB' });
    await signIn(page);
    await openDesk(page);
    await expect(page.getByTestId('eway-row')).toHaveCount(3);

    await page.getByTestId('eway-filter-q').fill('zed');
    await page.getByTestId('eway-filter-q').press('Enter');
    await expect(page.getByTestId('eway-row')).toHaveCount(1);
    expect(stub.listQueries.at(-1)).toContain('q=zed');

    await page.getByTestId('eway-filter-q').fill('');
    await page.getByTestId('eway-filter-status').selectOption('EXPIRED');
    await page.getByTestId('eway-filter-q').press('Enter');
    await expect(page.getByTestId('eway-none')).toBeVisible();
    expect(stub.listQueries.at(-1)).toContain('status=EXPIRED');

    await page.getByTestId('eway-filter-status').selectOption('CANCELLED');
    await page.getByTestId('eway-filter-apply').click();
    await expect(page.getByTestId('eway-row')).toHaveCount(1);
    await expect(page.getByTestId('eway-row-status')).toHaveText('CANCELLED');
  });

  test('the expiring-soon list shows only flagged live bills, and each opens', async ({ page }) => {
    const stub = await stubEway(page);
    stub.seed({ billNo: '200000000001', trackingRef: 'LR-SOON', expiringSoon: true });
    stub.seed({ billNo: '200000000002', trackingRef: 'LR-LATER' });
    stub.seed({ billNo: '200000000003', trackingRef: 'LR-GONE', status: 'CANCELLED', cancelReason: 'x', expiringSoon: true });
    await signIn(page);
    await openDesk(page);

    await expect(page.getByTestId('eway-row')).toHaveCount(3);
    await expect(page.getByTestId('eway-expiring-row')).toHaveCount(1);
    await expect(page.getByTestId('eway-expiring-no')).toHaveText('200000000001');
    await expect(page.getByTestId('eway-expiring-valid-until')).not.toBeEmpty();

    await page.getByTestId('eway-expiring-open').click();
    await expect(page.getByTestId('eway-detail-no')).toHaveText('200000000001');
    await expect(page.getByTestId('eway-detail-expiring')).toBeVisible();

    await page.getByTestId('eway-reason').fill('held at the border');
    await page.getByTestId('eway-extend').click();
    await expect(page.getByTestId('eway-detail-status')).toHaveText('EXTENDED');
    await expect(page.getByTestId('eway-expiring-row')).toHaveCount(0);
    await expect(page.getByTestId('eway-expiring-none')).toBeVisible();
  });

  test('Part-B for a trip answers per consignment: updated, skipped and refused', async ({ page }) => {
    const stub = await stubEway(page);
    const first = stub.seed({ billNo: '300000000001', consignmentId, trackingRef: 'LR-100' });
    const refused = stub.seed({ billNo: '300000000003', consignmentId: stub.tripConsignments[2].id, trackingRef: 'LR-REFUSE-300' });
    await signIn(page);
    await openDesk(page);

    await page.getByTestId('eway-trip-id').fill(tripId);
    await page.getByTestId('eway-trip-vehicle').fill('mh 12 tr 0001');
    await page.getByTestId('eway-trip-vehicle').press('Enter');

    await expect(page.getByTestId('eway-trip-item')).toHaveCount(3);
    const item = (id: string) => rowById(page, 'eway-trip-item', id);
    await expect(item(consignmentId).getByTestId('eway-trip-item-outcome')).toHaveText('UPDATED');
    await expect(item(otherConsignmentId).getByTestId('eway-trip-item-outcome')).toHaveText('SKIPPED');
    await expect(item(otherConsignmentId).getByTestId('eway-trip-item-code')).toHaveText('eway-bill-missing');
    await expect(item(stub.tripConsignments[2].id).getByTestId('eway-trip-item-outcome')).toHaveText('REFUSED');
    await expect(item(stub.tripConsignments[2].id).getByTestId('eway-trip-item-code')).toHaveText('eway-bill-refused');
    await expect(page.getByTestId('eway-trip-summary')).toContainText('1 of 3');
    expect(stub.changes.at(-1)).toMatchObject({ path: expect.stringContaining(`/trips/${tripId}/eway-bills/part-b`),
      body: { vehicleNo: 'mh 12 tr 0001' } });

    // The updated bill carries the vehicle; the refused one does not.
    await expect(rowById(page, 'eway-row', first.id).getByTestId('eway-row-vehicle')).toHaveText('MH12TR0001');
    await expect(rowById(page, 'eway-row', refused.id).getByTestId('eway-row-vehicle')).toHaveText('—');
  });

  test('without the right to manage, the forms and the actions are not offered', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubEwaySession(page, ['EWAY_BILL_MANAGE']);
    const stub = await stubEway(page);
    const bill = stub.seed({ billNo: '400000000001', trackingRef: 'LR-RO' });
    await signIn(page);
    await openDesk(page);

    await expect(page.getByTestId('eway-row')).toHaveCount(1);
    await expect(page.getByTestId('eway-generate')).toHaveCount(0);
    await expect(page.getByTestId('eway-trip-form')).toHaveCount(0);
    await page.getByTestId('eway-row-open').click();
    await expect(page.getByTestId('eway-detail')).toHaveAttribute('data-id', bill.id);
    await expect(page.getByTestId('eway-part-b')).toHaveCount(0);
    await expect(page.getByTestId('eway-extend')).toHaveCount(0);
    await expect(page.getByTestId('eway-cancel')).toHaveCount(0);
    // Looking things up is still allowed.
    await page.getByTestId('eway-lookup-no').fill('400000000001');
    await page.getByTestId('eway-lookup-no').press('Enter');
    await expect(page.getByTestId('eway-lookup-consignment')).toHaveText('LR-RO');
    expect(stub.changes).toHaveLength(0);
  });
});
