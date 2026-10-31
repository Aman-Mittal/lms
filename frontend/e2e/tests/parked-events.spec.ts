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
import { openScreen, signIn, withoutPermissions } from './support';

/*
 * A parked event is made only when a listener fails on every retry, which no
 * request through the published API can arrange; the backend's own integration
 * and end-to-end suites park, replay and discard real events. These specs cover
 * what the screen does with the real list: its filters, its empty state and
 * who may see it.
 */
test.describe('parked events', () => {
  test('the list loads from the real API, parked events by default', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-parked-events', 'Parked events');
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByTestId('parked-status')).toHaveValue('PARKED');
    await expect(page.getByTestId('parked').or(page.getByTestId('no-parked'))).toBeVisible();
  });

  test('a filter that matches nothing shows the empty state', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-parked-events', 'Parked events');
    await page.getByTestId('parked-status').selectOption('DISCARDED');
    await page.getByTestId('parked-type').fill(`com.example.NoSuchEvent${Date.now()}`);
    await page.getByRole('button', { name: 'Filter' }).click();
    await expect(page.getByTestId('no-parked')).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
  });

  test('the screen is offered only to someone who may read parked events', async ({ page }) => {
    await withoutPermissions(page, ['EVENT_DEAD_LETTER_READ']);
    await signIn(page);
    await expect(page.getByTestId('nav-parked-events')).toHaveCount(0);
  });
});
