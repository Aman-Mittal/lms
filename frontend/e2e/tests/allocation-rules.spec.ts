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
  apiSession, newPlannedLoad, offerLoad, openAllocationRules, retireRules, signIn, tag, withoutPermissions,
  type Lane,
} from './support';

/** Fills the rule form for a rule on the lane and saves it; the vendors are added in the order given. */
async function writeRule(
  page: Page, lane: Lane, name: string, action: 'PREFER' | 'EXCLUDE', vendorIds: string[], priority = 10,
): Promise<void> {
  const form = page.getByTestId('rule-form');
  await form.getByTestId('rule-name-input').fill(name);
  await form.getByTestId('rule-priority').fill(String(priority));
  await form.getByTestId('rule-origin').selectOption(lane.origin.id);
  await form.getByTestId('rule-destination').selectOption(lane.destination.id);
  await form.getByTestId('rule-action-input').selectOption(action);
  for (const id of vendorIds) {
    await form.getByTestId('rule-vendor-pick').selectOption(id);
    await form.getByTestId('rule-vendor-add').click();
  }
  await expect(form.getByTestId('rule-vendor')).toHaveCount(vendorIds.length);
  await form.getByTestId('rule-save').click();
}

function row(page: Page, name: string) {
  return page.getByTestId('rule-row').filter({ hasText: name });
}

async function dryRun(page: Page, loadId: string): Promise<void> {
  await page.getByTestId('dry-run-load').fill(loadId);
  await page.getByTestId('dry-run-go').click();
  await expect(page.getByTestId('dry-run-result')).toBeVisible();
}

test.describe('allocation rules', () => {
  test('a PREFER rule written here is listed, decides the dry run, and is named on the allocation it decides',
    async ({ page, request }, info) => {
      const salt = tag(info);
      const lane = await newPlannedLoad(request, await apiSession(request), salt);
      const [a, b, c] = lane.vendors;
      const name = `Prefer C then A ${salt}`;
      try {
        await signIn(page);
        await openAllocationRules(page);

        await test.step('without a rule the routing guide decides: rank 1 is asked first', async () => {
          await dryRun(page, lane.loadId);
          await expect(page.getByTestId('dry-run-rule')).toHaveText('No rule fires');
          await expect(page.getByTestId('dry-run-next')).toContainText(a.code);
        });

        await writeRule(page, lane, name, 'PREFER', [c.id, a.id]);
        await expect(row(page, name)).toBeVisible();
        await expect(row(page, name).getByTestId('rule-status')).toHaveText('Active');
        await expect(row(page, name).getByTestId('rule-action')).toHaveText('PREFER');
        // The order the vendors are asked in is the order they were added.
        await expect(row(page, name).getByTestId('rule-vendors')).toHaveText(new RegExp(`${c.code}.*→.*${a.code}`));

        await test.step('the dry run names the rule and asks its first vendor first', async () => {
          await dryRun(page, lane.loadId);
          await expect(page.getByTestId('dry-run-rule')).toHaveText(`${name} (PREFER)`);
          await expect(page.getByTestId('dry-run-next')).toContainText(c.code);
          await expect(page.getByTestId('dry-run-order').first()).toContainText(c.code);
          await expect(page.getByTestId('dry-run-order')).toHaveCount(3);
          await expect(page.getByTestId('dry-run-order').nth(1)).toContainText(a.code);
          await expect(page.getByTestId('dry-run-order').nth(2)).toContainText(b.code);
        });

        await test.step('offering the load records the rule, and the allocation shows it', async () => {
          const allocation = await offerLoad(request, await apiSession(request), lane.loadId);
          await page.getByTestId('allocation-id').fill(allocation);
          await page.getByTestId('allocation-go').click();
          await expect(page.getByTestId('allocation-rule')).toHaveText(`${name} (PREFER)`);
          await expect(page.getByTestId('allocation-status')).not.toBeEmpty();
        });
      } finally {
        await retireRules(request, salt);
      }
    });

  test('an EXCLUDE rule skips its vendor, and retiring it gives the guide back its say', async ({ page, request }, info) => {
    const salt = tag(info);
    const lane = await newPlannedLoad(request, await apiSession(request), salt);
    const [a, b] = lane.vendors;
    const name = `Never A ${salt}`;
    try {
      await signIn(page);
      await openAllocationRules(page);
      await writeRule(page, lane, name, 'EXCLUDE', [a.id]);
      await expect(row(page, name).getByTestId('rule-vendors')).toHaveText(new RegExp(a.code));

      await dryRun(page, lane.loadId);
      await expect(page.getByTestId('dry-run-rule')).toHaveText(`${name} (EXCLUDE)`);
      await expect(page.getByTestId('dry-run-next')).toContainText(b.code);
      await expect(page.getByTestId('dry-run-order')).toHaveCount(2);

      await row(page, name).getByTestId('rule-retire').click();
      await expect(row(page, name).getByTestId('rule-status')).toHaveText('Retired');
      await expect(row(page, name).getByTestId('rule-reinstate')).toBeVisible();

      await dryRun(page, lane.loadId);
      await expect(page.getByTestId('dry-run-rule')).toHaveText('No rule fires');
      await expect(page.getByTestId('dry-run-next')).toContainText(a.code);
    } finally {
      await retireRules(request, salt);
    }
  });

  test('a rule is edited in place: a new priority and vendor order are kept', async ({ page, request }, info) => {
    const salt = tag(info);
    const lane = await newPlannedLoad(request, await apiSession(request), salt);
    const [, b, c] = lane.vendors;
    const name = `Edited ${salt}`;
    try {
      await signIn(page);
      await openAllocationRules(page);
      await writeRule(page, lane, name, 'PREFER', [b.id]);
      await expect(row(page, name)).toBeVisible();

      await row(page, name).getByTestId('rule-edit').click();
      const form = page.getByTestId('rule-form');
      await expect(form.getByTestId('rule-name-input')).toHaveValue(name);
      await expect(form.getByTestId('rule-vendor')).toHaveCount(1);
      await form.getByTestId('rule-vendor-pick').selectOption(c.id);
      await form.getByTestId('rule-vendor-add').click();
      await form.getByTestId('rule-priority').fill('7');
      await form.getByTestId('rule-save').click();

      await expect(row(page, name).getByTestId('rule-vendors')).toHaveText(new RegExp(`${b.code}.*→.*${c.code}`));
      await expect(row(page, name).locator('td').first()).toHaveText('7');
      await dryRun(page, lane.loadId);
      await expect(page.getByTestId('dry-run-next')).toContainText(b.code);
    } finally {
      await retireRules(request, salt);
    }
  });

  test('the API refusing a rule is shown as it was said, and nothing is saved', async ({ page, request }, info) => {
    const salt = tag(info);
    const lane = await newPlannedLoad(request, await apiSession(request), salt);
    const name = `Bad band ${salt}`;
    try {
      await signIn(page);
      await openAllocationRules(page);
      const form = page.getByTestId('rule-form');
      await form.getByTestId('rule-min-weight').fill('9000');
      await form.getByTestId('rule-max-weight').fill('1000');
      await writeRule(page, lane, name, 'PREFER', [lane.vendors[0].id]);

      await expect(form.getByTestId('rule-error')).toBeVisible();
      await expect(form.getByTestId('rule-error')).not.toBeEmpty();
      await expect(row(page, name)).toHaveCount(0);
      // The form keeps what was typed, so the mistake can be corrected.
      await expect(form.getByTestId('rule-name-input')).toHaveValue(name);
    } finally {
      await retireRules(request, salt);
    }
  });

  test('without the right to manage rules, they can be read and dry-run but not changed', async ({ page, request }, info) => {
    const salt = tag(info);
    const lane = await newPlannedLoad(request, await apiSession(request), salt);
    await withoutPermissions(page, ['ALLOCATION_RULE_MANAGE']);
    await signIn(page);
    await openAllocationRules(page);

    await expect(page.getByTestId('rule-form')).toHaveCount(0);
    await expect(page.getByTestId('rule-edit')).toHaveCount(0);
    await expect(page.getByTestId('rule-retire')).toHaveCount(0);
    await dryRun(page, lane.loadId);
    await expect(page.getByTestId('dry-run-next')).toContainText(lane.vendors[0].code);
  });

  test('without the right to read rules, the screen is not offered', async ({ page }) => {
    await withoutPermissions(page, ['ALLOCATION_RULE_READ', 'ALLOCATION_RULE_MANAGE']);
    await signIn(page);

    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-delivery')).toBeVisible();
    await expect(page.getByTestId('nav-allocation-rules')).toHaveCount(0);
  });
});
