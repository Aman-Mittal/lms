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
import { registerPartner, signIn, tag } from './support';

test('a registered partner appears in the list and can be found by search', async ({ page }, info) => {
  const code = `E2E-${tag(info)}`.toUpperCase();
  await signIn(page);

  await registerPartner(page, code);

  await page.getByTestId('search').fill('no-such-partner-anywhere');
  await expect(page.getByText('No match.')).toBeVisible();
  await expect(page.getByTestId('row')).toHaveCount(0);

  await page.getByTestId('search').fill(code.toLowerCase());
  await expect(page.getByTestId('row')).toHaveCount(1);
  await expect(page.getByTestId('row')).toContainText(code);
});

test('opening a record shows its details, and closing returns to the list', async ({ page }, info) => {
  const code = `E2E-${tag(info)}`.toUpperCase();
  await signIn(page);
  await registerPartner(page, code);

  await page.getByTestId('row').filter({ hasText: code }).click();

  const panel = page.getByTestId('record-panel');
  await expect(panel).toBeVisible();
  await expect(panel.getByRole('heading', { name: code })).toBeVisible();

  await panel.getByRole('button', { name: 'Close panel' }).click();
  await expect(panel).toHaveCount(0);
});
