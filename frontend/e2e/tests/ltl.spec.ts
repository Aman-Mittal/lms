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
import { apiSession, newSite, openScreen, signIn, tag, withoutPermissions } from './support';

test.describe('LTL rating', () => {
  test('a rate card is published, and a second card for the same lane and day is refused', async ({ page }, info) => {
    // Zones of its own: a lane's next card must start after the last one, and
    // the other console's run publishes on the same day.
    const salt = tag(info).toUpperCase();
    const code = `ZZ-LTL-${salt}`;
    await signIn(page);
    await openScreen(page, 'nav-ltl', 'LTL rating');
    await expect(page.getByRole('alert')).toHaveCount(0);

    const form = page.getByTestId('rate-card-form');
    await form.getByTestId('rc-code').fill(code);
    await form.getByTestId('rc-origin').fill(`ZA-${salt}`);
    await form.getByTestId('rc-destination').fill(`ZB-${salt}`);
    await form.getByTestId('rc-basis').selectOption('PER_KG');
    await form.getByTestId('rc-rate').fill('12.5');
    await form.getByTestId('rc-kgcft').fill('6');
    await form.getByTestId('rc-min').fill('20');
    await form.getByTestId('rc-docket').fill('50');
    await form.getByTestId('publish-rate-card').click();
    await expect(page.getByTestId('rate-card-done')).toHaveText(`Rate card ${code} published.`);

    await form.getByTestId('rc-code').fill(`${code}-2`);
    await form.getByTestId('publish-rate-card').click();
    await expect(page.getByRole('alert')).toBeVisible();
  });

  test('a site is put in a zone', async ({ page, request }, info) => {
    const site = await newSite(request, await apiSession(request), `ZZ-LTL-HUB-${tag(info)}`);
    await signIn(page);
    await openScreen(page, 'nav-ltl', 'LTL rating');

    const form = page.getByTestId('zone-form');
    await expect(form.getByTestId('zone-terminal').locator(`option[value="${site.id}"]`)).toHaveCount(1);
    await form.getByTestId('zone-terminal').selectOption(site.id);
    await form.getByTestId('zone-code').fill('ZN-A');
    await form.getByTestId('zone-oda').check();
    await form.getByTestId('assign-zone').click();
    await expect(page.getByTestId('zone-done')).toHaveText('Zone ZN-A assigned.');
  });

  test('docket receivables are listed from the real API', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-ltl', 'LTL rating');
    await expect(page.getByRole('alert')).toHaveCount(0);
    await expect(page.getByTestId('receivables').or(page.getByTestId('no-receivables'))).toBeVisible();
  });

  test('the tariff forms are offered only to someone who may manage tariffs', async ({ page }) => {
    await withoutPermissions(page, ['TARIFF_MANAGE']);
    await signIn(page);
    await openScreen(page, 'nav-ltl', 'LTL rating');
    await expect(page.getByTestId('no-tariff-rights')).toBeVisible();
    await expect(page.getByTestId('rate-card-form')).toHaveCount(0);
    await expect(page.getByTestId('zone-form')).toHaveCount(0);
  });

  test('the screen is not offered without the right to read invoices or manage tariffs', async ({ page }) => {
    await withoutPermissions(page, ['TARIFF_MANAGE', 'INVOICE_READ']);
    await signIn(page);
    await expect(page.getByTestId('nav-ltl')).toHaveCount(0);
  });
});
