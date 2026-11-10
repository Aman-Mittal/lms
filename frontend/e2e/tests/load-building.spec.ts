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
  apiSession, assignToLoad, newBulkOrder, newPlannedLoad, offerLoad, planLoad, printed, removeFromLoadElsewhere,
  signIn, stubPrint, tag, withoutPermissions,
} from './support';

const LARGE = 150;

/** Opens the load-building screen on a load. */
async function openLoad(page: Page, loadId: string): Promise<void> {
  await page.getByTestId('nav-load-building').click();
  await expect(page.getByRole('heading', { name: 'Load building', level: 1 })).toBeVisible();
  await page.getByTestId('load-id').fill(loadId);
  await page.getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('load-panel')).toBeVisible();
}

/** Lists the consignments of one order that are waiting for a load. */
async function findWaiting(page: Page, orderId: string): Promise<void> {
  await page.getByTestId('order-filter').fill(orderId);
  await page.getByRole('button', { name: 'Find waiting consignments' }).click();
}

test.describe('load building', () => {
  test('150 consignments of one order go on a load together, in order, and print as a manifest', async ({ page, request }, info) => {
    test.setTimeout(240_000);
    const bulk = await newBulkOrder(request, await apiSession(request), tag(info), Array(LARGE).fill('GENERAL'));
    await stubPrint(page);
    await signIn(page);
    await openLoad(page, bulk.loadId);
    await expect(page.getByTestId('nothing-aboard')).toBeVisible();
    await expect(page.getByTestId('load-count')).toHaveText('0');

    await findWaiting(page, bulk.orderId);
    await expect(page.getByTestId('queue-row')).toHaveCount(LARGE);
    await page.getByTestId('select-all').check();
    await expect(page.getByTestId('add-selected')).toHaveText(`Add selected (${LARGE})`);
    await page.getByTestId('add-selected').click();

    await expect(page.getByTestId('bulk-added')).toHaveText(`Added ${LARGE} consignments to the load.`);
    await expect(page.getByTestId('queue-empty')).toBeVisible();
    await expect(page.getByTestId('load-count')).toHaveText(String(LARGE));
    await expect(page.getByTestId('load-weight')).toContainText('15000');
    const aboard = page.getByTestId('aboard-table').getByTestId('aboard-row');
    await expect(aboard).toHaveCount(LARGE);

    await test.step('every consignment is aboard once, in the order they were listed', async () => {
      const refs = await aboard.locator('td:nth-child(2)').allTextContents();
      expect(new Set(refs)).toEqual(new Set(bulk.consignments.map((c) => c.trackingRef)));
      expect(refs).toEqual([...refs].sort());
      await expect(aboard.last().locator('td').first()).toHaveText(String(LARGE));
    });

    await test.step('the manifest lists all of them and prints', async () => {
      await page.getByTestId('print-manifest').click();
      await expect(page.getByTestId('manifest').getByTestId('manifest-row')).toHaveCount(LARGE);
      await page.getByTestId('print-now').click();
      expect(await printed(page)).toBe(1);
    });
  });

  test('a batch that cannot board is refused whole, naming each consignment and why', async ({ page, request }, info) => {
    const bulk = await newBulkOrder(request, await apiSession(request), tag(info), ['FOOD', 'TOXIC', 'GENERAL']);
    const poison = bulk.consignments[1];
    await signIn(page);
    await openLoad(page, bulk.loadId);
    await findWaiting(page, bulk.orderId);
    await expect(page.getByTestId('queue-row')).toHaveCount(3);
    await page.getByTestId('select-all').check();
    await page.getByTestId('add-selected').click();

    await expect(page.getByTestId('bulk-error')).toContainText('cannot take');
    // Each row leads with the consignment it refuses; the other party to a clash is named in its text too.
    const refusal = page.getByTestId('bulk-refusals').getByTestId('refusal')
      .filter({ has: page.locator('strong', { hasText: poison.trackingRef }) });
    await expect(refusal).toContainText('load-not-hazmat-certified');
    // The lorry is not certified for dangerous goods, and food will not ride with poison;
    // the general cargo is not named, and nothing at all went on.
    await expect(page.getByTestId('refusal').filter({ hasText: bulk.consignments[2].trackingRef })).toHaveCount(0);
    await expect(page.getByTestId('nothing-aboard')).toBeVisible();
    await expect(page.getByTestId('load-count')).toHaveText('0');
    await expect(page.getByTestId('queue-row')).toHaveCount(3);

    await test.step('without the poison the rest board', async () => {
      await page.getByTestId('pick').and(page.getByLabel(`Select ${poison.trackingRef}`)).uncheck();
      await expect(page.getByTestId('add-selected')).toHaveText('Add selected (2)');
      await page.getByTestId('add-selected').click();
      await expect(page.getByTestId('bulk-added')).toHaveText('Added 2 consignments to the load.');
      await expect(page.getByTestId('bulk-refusals')).toHaveCount(0);
      await expect(page.getByTestId('aboard-row')).toHaveCount(2);
      await expect(page.getByTestId('queue-row')).toHaveCount(1);
    });
  });

  test('without the right to build loads, a load is read and printed but nothing is offered to add', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const bulk = await newBulkOrder(request, session, tag(info), ['GENERAL']);
    await assignToLoad(request, session, bulk.loadId, bulk.consignments[0].id);
    await withoutPermissions(page, ['LOAD_CREATE']);
    await stubPrint(page);
    await signIn(page);
    await openLoad(page, bulk.loadId);

    await expect(page.getByTestId('aboard-row')).toHaveCount(1);
    await expect(page.getByTestId('add-panel')).toHaveCount(0);
    await expect(page.getByTestId('remove-consignment')).toHaveCount(0);
    await expect(page.getByTestId('load-actions')).toHaveCount(0);
    await page.getByTestId('print-manifest').click();
    await page.getByTestId('print-now').click();
    expect(await printed(page)).toBe(1);
  });

  test('a load that does not exist is reported, not shown', async ({ page }) => {
    await signIn(page);
    await page.getByTestId('nav-load-building').click();
    await page.getByTestId('load-id').fill(crypto.randomUUID());
    await page.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByTestId('load-panel')).toHaveCount(0);
  });
});

test.describe('load maintenance', () => {
  test('a consignment comes off a planned load, which is then un-planned and cancelled', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const bulk = await newBulkOrder(request, session, tag(info), ['GENERAL', 'GENERAL', 'GENERAL']);
    for (const c of bulk.consignments) {
      await assignToLoad(request, session, bulk.loadId, c.id);
    }
    await planLoad(request, session, bulk.loadId);
    const [first, middle, last] = bulk.consignments;
    await signIn(page);
    await openLoad(page, bulk.loadId);
    await expect(page.getByTestId('load-status')).toHaveText('PLANNED');
    await expect(page.getByTestId('aboard-row')).toHaveCount(3);

    await test.step('the middle consignment comes off and the drops close up', async () => {
      await page.getByRole('button', { name: `Remove ${middle.trackingRef}` }).click();
      await expect(page.getByTestId('load-done')).toHaveText(`${middle.trackingRef} is off the load.`);
      await expect(page.getByTestId('load-count')).toHaveText('2');
      await expect(page.getByTestId('load-weight')).toContainText('200');
      const aboard = page.getByTestId('aboard-table').getByTestId('aboard-row');
      await expect(aboard).toHaveCount(2);
      await expect(aboard.locator('td:nth-child(2)')).toHaveText([first.trackingRef, last.trackingRef]);
      await expect(aboard.last().locator('td').first()).toHaveText('2');
      await expect(page.getByTestId('load-status')).toHaveText('PLANNED');
    });

    await test.step('the load is opened for building again', async () => {
      await page.getByTestId('unplan-load').click();
      await expect(page.getByTestId('load-done')).toHaveText('The load is open for building again.');
      await expect(page.getByTestId('load-status')).toHaveText('DRAFT');
      await expect(page.getByTestId('unplan-load')).toHaveCount(0);
    });

    await test.step('the load is cancelled with a reason, and its freight released', async () => {
      await page.getByTestId('cancel-reason').fill('the yard is closed today');
      await page.getByTestId('cancel-load').click();
      await expect(page.getByTestId('load-done')).toHaveText('The load is cancelled and its consignments released.');
      await expect(page.getByTestId('load-status')).toHaveText('CANCELLED');
      await expect(page.getByTestId('nothing-aboard')).toBeVisible();
      await expect(page.getByTestId('load-actions')).toHaveCount(0);
      await expect(page.getByTestId('add-panel')).toHaveCount(0);
    });
  });

  test('a load out with a vendor is refused by code', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const lane = await newPlannedLoad(request, session, tag(info));
    await offerLoad(request, session, lane.loadId);
    await signIn(page);
    await openLoad(page, lane.loadId);

    await page.getByTestId('unplan-load').click();
    await expect(page.getByTestId('load-refusal')).toHaveText('load-allocated');
    await expect(page.getByTestId('load-done')).toHaveCount(0);
    await expect(page.getByTestId('load-status')).toHaveText('PLANNED');
  });

  test('a change made on a screen another planner has overtaken is refused, and the screen catches up', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const bulk = await newBulkOrder(request, session, tag(info), ['GENERAL', 'GENERAL']);
    for (const c of bulk.consignments) {
      await assignToLoad(request, session, bulk.loadId, c.id);
    }
    const [taken, left] = bulk.consignments;
    await signIn(page);
    await openLoad(page, bulk.loadId);
    await expect(page.getByTestId('aboard-row')).toHaveCount(2);

    await removeFromLoadElsewhere(request, session, bulk.loadId, taken.id);
    await page.getByRole('button', { name: `Remove ${left.trackingRef}` }).click();

    await expect(page.getByTestId('load-refusal')).toHaveText('concurrent-modification');
    await expect(page.getByTestId('aboard-row')).toHaveCount(1);
    await expect(page.getByTestId('load-count')).toHaveText('1');
    await test.step('at the version now on screen the change goes through', async () => {
      await page.getByRole('button', { name: `Remove ${left.trackingRef}` }).click();
      await expect(page.getByTestId('load-done')).toHaveText(`${left.trackingRef} is off the load.`);
      await expect(page.getByTestId('nothing-aboard')).toBeVisible();
    });
  });
});
