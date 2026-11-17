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
import { signIn } from './support';
import { consignmentId, stubForms, stubFormsSession, type FormsStub } from './support-forms';

/*
 * Forms and feedback, in both consoles: every field has a visible label, a refusal is told as one banner (the plain
 * sentence, the server's words, the code), an empty sign-in is stopped by the browser and never reaches the API, a submit
 * button stays off while its request runs, and a picker hands over the id of what was chosen. The API is stubbed so the
 * same assertions run whatever the demo tenant holds; set E2E_STUB_SESSION=1 to stub the sign-in too, for a run with no
 * backend at all.
 */

let stub: FormsStub;

test.beforeEach(async ({ page }) => {
  stub = await stubForms(page);
  // Registered after the stub's catch-all, so the sign-in is answered here.
  if (process.env.E2E_STUB_SESSION) {
    await stubFormsSession(page);
  }
});

async function openPartners(page: import('@playwright/test').Page) {
  await page.getByTestId('nav-partners').click();
  await expect(page.getByRole('heading', { name: 'Partners', level: 1 })).toBeVisible();
}

async function register(page: import('@playwright/test').Page) {
  const form = page.getByTestId('register-partner');
  await form.getByPlaceholder('Code').fill('ACME');
  await form.getByPlaceholder('Legal name').fill('Acme Traders');
  await form.getByRole('button', { name: 'Register' }).click();
  return form;
}

test.describe('forms and feedback', () => {
  test('every field has a visible label, and a refusal is linked to the fields it came from', async ({ page }) => {
    await signIn(page);
    await openPartners(page);
    const form = page.getByTestId('register-partner');

    // The placeholders stay (the specs find fields by them); the labels are added beside them.
    await expect(form.locator('.field-label', { hasText: 'Code' })).toBeVisible();
    await expect(form.locator('.field-label', { hasText: 'Legal name' })).toBeVisible();
    await expect(form.locator('label.field-label', { hasText: 'Partner type' })).toBeVisible();
    await expect(form.getByLabel('Partner type')).toBeVisible();
    await expect(form.getByPlaceholder('Code')).toBeVisible();
    // One label per field, however many times the page has been scanned.
    await page.waitForTimeout(300);
    await expect(form.locator('.field-label', { hasText: 'Code' })).toHaveCount(1);
    await expect(form.locator('.field-label', { hasText: 'Legal name' })).toHaveCount(1);

    await register(page);
    const banner = page.getByTestId('register-error');
    await expect(banner).toBeVisible();
    // The link is made a moment after the banner appears, so wait for it.
    await expect(form.getByPlaceholder('Code')).toHaveAttribute('aria-describedby', /.+/);
    const described = await form.getByPlaceholder('Code').getAttribute('aria-describedby');
    await expect(page.locator(`[id="${described}"]`)).toContainText('Someone else changed this record');
  });

  test('a 409 concurrent-modification is told in plain words, with the server detail and the code in small print', async ({ page }) => {
    await signIn(page);
    await openPartners(page);
    await register(page);

    const banner = page.getByTestId('register-error');
    await expect(banner).toBeVisible();
    await expect(banner).toHaveAttribute('role', 'alert');
    await expect(banner).toContainText('Someone else changed this record while you were working on it. Reload it and try again.');
    await expect(banner).toContainText('The register changed since it was read');
    await expect(banner.locator('.banner__code code')).toHaveText('concurrent-modification');
    expect(stub.registrations).toHaveLength(1);
  });

  test('a sign-in with the password left empty is stopped by the browser and never sent to the API', async ({ page }) => {
    await page.goto('./');
    const password = page.getByLabel('Password');
    await page.getByRole('button', { name: 'Sign in' }).click();

    await expect.poll(() => password.evaluate((el: HTMLInputElement) => el.validity.valueMissing)).toBe(true);
    await page.waitForTimeout(300);
    expect(stub.logins).toBe(0);
    await expect(page.getByRole('heading', { name: 'Control tower', level: 1 })).toHaveCount(0);
  });

  test('a submit button stays off while its request runs, and comes back when it is answered', async ({ page }) => {
    stub.registerDelayMs = 800;
    await signIn(page);
    await openPartners(page);
    const form = await register(page);

    const button = form.getByRole('button', { name: 'Register' });
    await expect(button).toBeDisabled();
    await expect(button).toBeEnabled();
    expect(stub.registrations).toHaveLength(1);
  });

  test('a picker hands over the id of what was chosen, shown by its name', async ({ page }) => {
    await signIn(page);
    await page.getByTestId('nav-pod').click();
    await expect(page.getByRole('heading', { name: 'Proof of delivery desk', level: 1 })).toBeVisible();

    const input = page.getByTestId('pod-consignment-id');
    await input.fill('TRK');
    const option = page.getByRole('option', { name: /TRK-0042/ });
    await expect(option).toHaveCount(1);
    await option.click();
    await expect(input).toHaveValue(/TRK-0042/);
    await expect(page.locator('label.field-label', { hasText: 'Consignment id' })).toBeVisible();

    await page.getByTestId('pod-lookup').getByRole('button', { name: 'Look up' }).click();
    await expect.poll(() => stub.lookups.length).toBeGreaterThan(0);
    expect(stub.lookups[0]).toContain(consignmentId);
  });
});
