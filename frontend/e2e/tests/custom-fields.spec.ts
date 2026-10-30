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
import { registerPartner, retireFields, signIn, signOut, tag } from './support';

/*
 * The whole custom-fields story as a person lives it, in one test so that the
 * steps share the fields they define: define two fields in Settings, fill them
 * in on a partner, see the platform refuse a blank required field, sign out
 * and back in to prove the values were stored by the server rather than kept
 * by the page, and finally retire the fields.
 *
 * The demo tenant is shared, and a required field would otherwise outlive the
 * test and trip up every later write to a partner, so retiring them is part
 * of the story and not just housekeeping.
 */
test('define custom fields, fill them in on a partner, and retire them', async ({ page, request }, info) => {
  const id = tag(info);
  const poKey = `po_${id}`;
  const tierKey = `tier_${id}`;
  const poLabel = `PO ${id}`;
  const tierLabel = `Tier ${id}`;
  const partner = `E2E-${id}`.toUpperCase();

  try {
    await signIn(page);

    await test.step('define a required text field and a choice in Settings', async () => {
      await page.getByTestId('nav-custom-fields').click();
      await expect(page.getByRole('heading', { name: 'Custom fields', level: 1 })).toBeVisible();
      await page.getByTestId('kind').selectOption('PARTNER');

      const add = page.getByTestId('add-field');
      await add.getByLabel('Key').fill(poKey);
      await add.getByLabel('Label').fill(poLabel);
      await add.getByLabel('Type').selectOption('TEXT');
      await add.getByLabel('Required').check();
      await add.getByRole('button', { name: 'Add field' }).click();
      await expect(page.getByTestId(`def-${poKey}`)).toContainText('Active');

      await add.getByLabel('Key').fill(tierKey);
      await add.getByLabel('Label').fill(tierLabel);
      await add.getByLabel('Type').selectOption('SELECT');
      await add.getByLabel('Options').fill('GOLD, SILVER');
      await add.getByRole('button', { name: 'Add field' }).click();
      await expect(page.getByTestId(`def-${tierKey}`)).toContainText('GOLD, SILVER');
      await expect(page.getByTestId(`def-${poKey}`)).toContainText('Yes');
    });

    await test.step('a bad definition is refused with the reason', async () => {
      const add = page.getByTestId('add-field');
      await add.getByLabel('Key').fill('Not A Valid Key');
      await add.getByLabel('Label').fill('Whatever');
      await add.getByLabel('Type').selectOption('TEXT');
      await add.getByRole('button', { name: 'Add field' }).click();
      await expect(page.getByRole('alert')).toContainText('lower-case');
    });

    await test.step('the fields appear on a partner and a blank required one is refused', async () => {
      await registerPartner(page, partner);
      await page.getByTestId('row').filter({ hasText: partner }).click();

      const form = page.getByTestId('custom-fields');
      await expect(form).toBeVisible();
      await form.getByTestId(`cf-${tierKey}`).selectOption('GOLD');
      await form.getByRole('button', { name: 'Save' }).click();

      await expect(page.getByRole('alert')).toContainText(poKey);
      await expect(page.getByRole('alert')).toContainText('required');
    });

    await test.step('filling the required field saves', async () => {
      const form = page.getByTestId('custom-fields');
      await form.getByTestId(`cf-${poKey}`).fill('PO-7781');
      await form.getByRole('button', { name: 'Save' }).click();

      await expect(page.getByTestId('custom-fields-saved')).toHaveText('Saved');
    });

    await test.step('the values were stored by the server: sign out, sign in, look again', async () => {
      await signOut(page);
      await signIn(page);
      await page.getByTestId('nav-partners').click();
      await page.getByTestId('row').filter({ hasText: partner }).click();

      const form = page.getByTestId('custom-fields');
      await expect(form.getByTestId(`cf-${poKey}`)).toHaveValue('PO-7781');
      await expect(form.getByTestId(`cf-${tierKey}`)).toHaveValue('GOLD');
      // The required field is announced as required, by its label.
      await expect(form.getByText(`${poLabel} *`)).toBeVisible();
    });

    await test.step('retiring the fields takes them off the form', async () => {
      await page.getByTestId('nav-custom-fields').click();
      await page.getByTestId('kind').selectOption('PARTNER');
      await page.getByTestId(`toggle-${poKey}`).click();
      await expect(page.getByTestId(`def-${poKey}`)).toContainText('Retired');
      await page.getByTestId(`toggle-${tierKey}`).click();
      await expect(page.getByTestId(`def-${tierKey}`)).toContainText('Retired');

      await page.getByTestId('nav-partners').click();
      await page.getByTestId('row').filter({ hasText: partner }).click();
      await expect(page.getByTestId('record-panel')).toBeVisible();
      await expect(page.getByTestId(`cf-${poKey}`)).toHaveCount(0);
      await expect(page.getByTestId(`cf-${tierKey}`)).toHaveCount(0);
    });
  } finally {
    await retireFields(request, 'PARTNER', [poKey, tierKey]);
  }
});
