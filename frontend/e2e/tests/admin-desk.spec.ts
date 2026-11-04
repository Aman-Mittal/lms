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
import { DEMO_EMAIL, apiSession, openScreen, signIn, tag, withoutPermissions } from './support';
import {
  accessAttributesOf, adminUserId, deletePolicy, inDays, newVehicle, restoreAccessAttributes,
} from './support-admin';

/** Opens the demo administrator in the users list and waits until its attributes are read. */
async function openAdministrator(page: Page) {
  await openScreen(page, 'nav-admin-users', 'Users');
  await page.getByTestId('users-row').filter({ hasText: DEMO_EMAIL }).getByRole('button').click();
  const detail = page.getByTestId('user-detail');
  await expect(detail.getByTestId('user-permissions')).toContainText(/[1-9]\d* permissions/);
  await expect(detail.getByTestId('attr-region')).toBeVisible();
  return detail;
}

test.describe('users', () => {
  test('a user\'s access attributes are set in the console and read back', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const userId = await adminUserId(request, session);
    const before = await accessAttributesOf(request, session, userId);
    // Region and freight modes only ever narrow through a policy that names them, and no
    // seeded policy does: setting them on the shared administrator changes what nobody may do.
    const region = `E2E-${tag(info).toUpperCase()}`;
    try {
      await signIn(page);
      let detail = await openAdministrator(page);
      await detail.getByTestId('attr-region').fill(region);
      for (const mode of ['FTL', 'RAIL']) {
        await detail.getByTestId(`attr-mode-${mode}`).check();
      }
      await detail.getByTestId('save-attributes').click();
      await expect(page.getByTestId('admin-message')).toContainText('Saved');

      const saved = await accessAttributesOf(request, session, userId);
      expect(saved.region).toBe(region);
      expect(saved.freightModes).toEqual(expect.arrayContaining(['FTL', 'RAIL']));
      // A PUT of the whole set: what the form did not touch is sent back as it was.
      expect(saved.approvalLimit).toEqual(before.approvalLimit);
      expect(saved.partnerId).toEqual(before.partnerId);

      await test.step('read again, the screen shows what was saved', async () => {
        await openScreen(page, 'nav-admin-policies', 'Access policies');
        detail = await openAdministrator(page);
        await expect(detail.getByTestId('attr-region')).toHaveValue(region);
        await expect(detail.getByTestId('attr-mode-FTL')).toBeChecked();
        await expect(detail.getByTestId('attr-mode-RAIL')).toBeChecked();
      });

      await test.step('a region the API does not accept is refused by name', async () => {
        await detail.getByTestId('attr-region').fill('not a region');
        await detail.getByTestId('save-attributes').click();
        await expect(page.getByTestId('admin-error')).toBeVisible();
        expect((await accessAttributesOf(request, session, userId)).region).toBe(region);
      });
    } finally {
      await restoreAccessAttributes(request, session, userId, before);
    }
  });
});

test.describe('access policies', () => {
  test('a policy is created, refused twice for a reason it names, revised and deleted', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const code = `e2e-${tag(info)}`;
    // A DENY whose condition never holds: it is consulted on every audit read and vetoes none.
    const never = JSON.stringify({ attr: 'subject.region', op: 'eq', value: `NEVER-${tag(info).toUpperCase()}` });
    const form = page.getByTestId('policy-create');
    try {
      await signIn(page);
      await openScreen(page, 'nav-admin-policies', 'Access policies');

      await test.step('a condition that is not JSON is refused before it is sent', async () => {
        await form.getByTestId('policy-code').fill(code);
        await form.getByTestId('policy-action').fill('AUDIT_READ');
        await form.getByTestId('policy-effect').selectOption('DENY');
        await form.getByTestId('policy-description').fill('Never bites: an end-to-end check');
        await form.getByTestId('policy-condition').fill('region is north');
        await form.getByTestId('create-policy').click();
        await expect(page.getByTestId('admin-refusal')).toHaveText('condition-not-json');
        await expect(page.getByTestId('policies-row').filter({ hasText: code })).toHaveCount(0);
      });

      await test.step('created', async () => {
        await form.getByTestId('policy-condition').fill(never);
        await form.getByTestId('create-policy').click();
        await expect(page.getByTestId('policy-detail').getByRole('heading', { name: code })).toBeVisible();
        const row = page.getByTestId('policies-row').filter({ hasText: code });
        await expect(row).toContainText('AUDIT_READ');
        await expect(row).toContainText('DENY');
        await expect(row).toContainText('Yes');
      });

      await test.step('the same code again is refused by the server, by name', async () => {
        await page.getByTestId('close-policy').click();
        await form.getByTestId('policy-code').fill(code);
        await form.getByTestId('policy-action').fill('AUDIT_READ');
        await form.getByTestId('policy-description').fill('A second one');
        await form.getByTestId('policy-condition').fill(never);
        await form.getByTestId('create-policy').click();
        await expect(page.getByTestId('admin-refusal')).toHaveText('access-policy-code-taken');
        await expect(page.getByTestId('policies-row').filter({ hasText: code })).toHaveCount(1);
      });

      await test.step('revised: switched off with a new description', async () => {
        await page.getByTestId('policies-row').filter({ hasText: code }).getByRole('button').click();
        const detail = page.getByTestId('policy-detail');
        await expect(detail.getByTestId('policy-condition')).toHaveValue(/NEVER-/);
        await detail.getByTestId('policy-description').fill('Switched off by an end-to-end check');
        await detail.getByTestId('policy-enabled').uncheck();
        await detail.getByTestId('save-policy').click();
        await expect(page.getByTestId('admin-message')).toHaveText('Policy saved.');
        const row = page.getByTestId('policies-row').filter({ hasText: code });
        await expect(row).toContainText('Switched off by an end-to-end check');
        await expect(row).toContainText('No');
      });

      await test.step('deleted', async () => {
        await page.getByTestId('policy-detail').getByTestId('delete-policy').click();
        await expect(page.getByTestId('policies-row').filter({ hasText: code })).toHaveCount(0);
        await expect(form).toBeVisible();
      });
    } finally {
      await deletePolicy(request, session, code);
    }
  });
});

test.describe('compliance', () => {
  test('a document attached to lapse in five days is on the expiring list', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const vehicle = await newVehicle(request, session, tag(info));
    const documentNo = `INS-${tag(info).toUpperCase()}`;
    await signIn(page);
    await openScreen(page, 'nav-admin-compliance', 'Compliance');

    const panel = page.getByTestId('owner-documents-panel');
    await panel.getByTestId('owner-type').selectOption('VEHICLE');
    await panel.getByTestId('owner-id').fill(vehicle.id);
    await panel.getByTestId('open-owner').click();
    await expect(panel.getByTestId('no-documents')).toBeVisible();

    const attach = panel.getByTestId('attach-document');
    await attach.getByTestId('doc-type').fill('INSURANCE');
    await attach.getByTestId('doc-no').fill(documentNo);
    await attach.getByTestId('doc-authority').fill('Motor insurer');
    await attach.getByTestId('doc-issued').fill(inDays(-360));
    await attach.getByTestId('doc-expires').fill(inDays(5));
    await attach.getByTestId('attach-document-submit').click();
    await expect(page.getByTestId('admin-message')).toHaveText('Document attached.');
    await expect(panel.getByTestId('owner-documents-row').filter({ hasText: documentNo })).toContainText('Expiring');

    const listed = page.getByTestId('expiring-row').filter({ hasText: documentNo });
    await expect(listed).toContainText(vehicle.registrationNo);
    await expect(listed).toContainText('Expiring');

    await test.step('not within a narrower window', async () => {
      await page.getByTestId('expiring-days').fill('1');
      await page.getByTestId('show-expiring').click();
      await expect(page.getByTestId('expiring-row').filter({ hasText: documentNo })).toHaveCount(0);
    });
  });
});

test.describe('what each person is offered', () => {
  test('without the rights, there is no Administration group at all', async ({ page }) => {
    await withoutPermissions(page, ['ACCESS_POLICY_MANAGE', 'VEHICLE_READ']);
    await signIn(page);
    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-partners')).toBeVisible();
    await expect(page.getByTestId('nav-admin')).toHaveCount(0);
  });

  test('without the right to manage access, only compliance is offered, and read-only without VEHICLE_UPDATE',
    async ({ page, request }, info) => {
      const vehicle = await newVehicle(request, await apiSession(request), tag(info));
      await withoutPermissions(page, ['ACCESS_POLICY_MANAGE', 'VEHICLE_UPDATE']);
      await signIn(page);
      await expect(page.getByTestId('nav-admin')).toBeVisible();
      await expect(page.getByTestId('nav-admin-users')).toHaveCount(0);
      await expect(page.getByTestId('nav-admin-policies')).toHaveCount(0);
      await openScreen(page, 'nav-admin-compliance', 'Compliance');
      const panel = page.getByTestId('owner-documents-panel');
      await panel.getByTestId('owner-id').fill(vehicle.id);
      await panel.getByTestId('open-owner').click();
      await expect(panel.getByTestId('no-documents')).toBeVisible();
      await expect(panel.getByTestId('attach-document')).toHaveCount(0);
    });
});
