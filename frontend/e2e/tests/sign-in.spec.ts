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
import { signIn, signOut } from './support';

test.describe('signing in', () => {
  test('a wrong password is refused with a message', async ({ page }) => {
    await page.goto('./');
    await page.getByLabel('Password').fill('definitely not the password');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeEnabled();
    await expect(page.getByTestId('sidebar')).toHaveCount(0);
  });

  test('the administrator sees the control tower and can sign out', async ({ page }) => {
    await signIn(page);

    await expect(page.getByTestId('tiles')).toContainText('Open P1');
    await expect(page.getByTestId('who')).toContainText('permissions');

    await signOut(page);
    await expect(page.getByTestId('sidebar')).toHaveCount(0);
  });

  test('a visitor who is not signed in is sent to the sign-in screen', async ({ page }) => {
    await page.goto('./#/partners');

    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });
});
