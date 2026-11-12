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

import { expect, test, type APIRequestContext, type Page } from '@playwright/test';
import {
  API, apiSession, newBillingPartner, openScreen, publishContract, resetApprovalPolicy, setApprovalPolicy, signIn, tag,
  type Session,
} from './support';

/*
 * A client contract's lifecycle in both consoles: draft, checklist, verification through the
 * approvals inbox, rejection, extension and an added charge.
 *
 * The demo tenant has one administrator, so the spec lets the requester decide (the self-approval
 * toggle the approvals spec uses for its flows) and puts every contract policy back to the code
 * default afterwards, whatever happened.
 */

const TYPES = ['CONTRACT_ACTIVATION', 'CONTRACT_EXTENSION', 'CONTRACT_CHARGE_ADD'];

/** A `YYYY-MM-DD` day this many days from today, in UTC like the API. */
function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

/** Opens the client's desk, whatever it holds. */
async function openClient(page: Page, clientId: string): Promise<void> {
  await openScreen(page, 'nav-client-billing', 'Client billing');
  await page.getByTestId('client-id').fill(clientId);
  await page.getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('client-desk')).toBeVisible();
  await expect(page.getByTestId('contract-lifecycle')).toBeVisible();
}

/** Opens the version row for the contract code. */
async function openVersion(page: Page, code: string): Promise<void> {
  await page.getByTestId('version-row').filter({ hasText: code }).getByTestId('open-version').click();
  await expect(page.getByTestId('contract-panel')).toBeVisible();
}

/** Decides the one open request whose summary mentions the text, in the approvals inbox. */
async function decide(page: Page, text: string, how: 'approve' | 'reject'): Promise<void> {
  await openScreen(page, 'nav-approvals', 'Approvals');
  const row = page.getByTestId('approvals').getByRole('row').filter({ hasText: text });
  await expect(row).toHaveCount(1);
  await row.getByRole('button').first().click();
  await expect(page.getByTestId('approval-detail')).toBeVisible();
  if (how === 'approve') {
    await page.getByTestId('approve').click();
    await expect(page.getByTestId('approval-status')).toHaveText('APPROVED');
  } else {
    await page.getByTestId('reject-reason').fill('rate sheet missing');
    await page.getByTestId('reject').click();
    await expect(page.getByTestId('approval-status')).toHaveText('REJECTED');
  }
}

async function contractOf(request: APIRequestContext, session: Session, id: string) {
  const response = await request.get(`${API}/api/v1/client-billing/contracts/${id}`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

async function versionsOf(request: APIRequestContext, session: Session, clientId: string) {
  const response = await request.get(`${API}/api/v1/client-billing/clients/${clientId}/contracts`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  expect(response.ok()).toBeTruthy();
  return response.json();
}

test.describe('client contract lifecycle', () => {
  let session: Session;

  test.beforeEach(async ({ request }) => {
    session = await apiSession(request);
    for (const type of TYPES) {
      await setApprovalPolicy(request, session, type, { required: true, allowSelfApproval: true });
    }
  });

  test.afterEach(async ({ request }) => {
    for (const type of TYPES) {
      await resetApprovalPolicy(request, session, type);
    }
  });

  test('a draft is checked, edited, verified by approval and goes into force', async ({ page, request }, info) => {
    const client = await newBillingPartner(request, session);
    const code = `LC-${tag(info)}`;
    await signIn(page);
    await openClient(page, client.id);

    await test.step('a draft with no transport price is created', async () => {
      await page.getByTestId('new-contract-code').fill(code);
      await page.getByTestId('new-contract-effectiveFrom').fill(inDays(-10));
      await page.getByTestId('create-draft').click();
      await expect(page.getByTestId('contract-status')).toHaveText('DRAFT');
      await expect(page.getByTestId('check-TRANSPORT_RATE')).toContainText('Price transport');
    });

    await test.step('verification is refused until it is complete, and says which rule fails', async () => {
      await page.getByTestId('request-verification').click();
      await expect(page.getByTestId('contract-refusal')).toHaveText('contract-checklist-incomplete');
      await expect(page.getByTestId('refused-TRANSPORT_RATE')).toBeVisible();
    });

    await test.step('the draft is edited and passes', async () => {
      await page.getByTestId('edit-contract-transportRatePerKg').fill('5');
      await page.getByTestId('save-draft').click();
      await expect(page.getByTestId('contract-message')).toHaveText('Draft saved.');
      await expect(page.getByTestId('check-TRANSPORT_RATE')).toContainText('passes');
    });

    await test.step('verification is requested and the draft waits, frozen', async () => {
      await page.getByTestId('request-verification').click();
      await expect(page.getByTestId('contract-message')).toHaveText('Verification requested: awaiting approval.');
      await expect(page.getByTestId('contract-status')).toHaveText('PENDING_VERIFICATION');
      await expect(page.getByTestId('save-draft')).toHaveCount(0);
    });

    await decide(page, code, 'approve');

    await test.step('the contract is in force', async () => {
      await openClient(page, client.id);
      await expect(page.getByTestId('version-row').filter({ hasText: code })).toContainText('ACTIVE');
    });
  });

  test('a rejection returns the draft with its reason, and a draft can be deleted', async ({ page, request }, info) => {
    const client = await newBillingPartner(request, session);
    const code = `LR-${tag(info)}`;
    await signIn(page);
    await openClient(page, client.id);

    await page.getByTestId('new-contract-code').fill(code);
    await page.getByTestId('new-contract-effectiveFrom').fill(inDays(-10));
    await page.getByTestId('new-contract-transportRatePerKg').fill('5');
    await page.getByTestId('create-draft').click();
    await expect(page.getByTestId('contract-status')).toHaveText('DRAFT');
    await page.getByTestId('request-verification').click();
    await expect(page.getByTestId('contract-status')).toHaveText('PENDING_VERIFICATION');

    await decide(page, code, 'reject');

    await openClient(page, client.id);
    await openVersion(page, code);
    await expect(page.getByTestId('contract-status')).toHaveText('DRAFT');
    await expect(page.getByTestId('contract-rejection')).toContainText('rate sheet missing');

    await page.getByTestId('delete-draft').click();
    await expect(page.getByTestId('contract-message')).toHaveText('Draft deleted.');
    await expect(page.getByTestId('version-row')).toHaveCount(0);
  });

  test('a contract in force is extended and gets a charge, each through approval', async ({ page, request }, info) => {
    const client = await newBillingPartner(request, session);
    const code = `LX-${tag(info)}`;
    const id = await publishContract(request, session, {
      orgUnitId: session.orgUnitId, code, clientPartnerId: client.id, effectiveFrom: inDays(-20),
      validTill: inDays(5), transportRatePerKg: 5,
    });
    await signIn(page);
    await openClient(page, client.id);
    await openVersion(page, code);

    await test.step('the end date moves only once the extension is approved', async () => {
      await page.getByTestId('extend-till').fill(inDays(40));
      await page.getByTestId('extend-reason').fill('customer renewed');
      await page.getByTestId('request-extension').click();
      await expect(page.getByTestId('contract-message')).toHaveText('Extension requested: awaiting approval.');
      expect((await contractOf(request, session, id)).validTill).toBe(inDays(5));
      await decide(page, code, 'approve');
      expect((await contractOf(request, session, id)).validTill).toBe(inDays(40));
    });

    await test.step('a charge becomes a successor version from its day once approved', async () => {
      await openClient(page, client.id);
      await openVersion(page, code);
      await page.getByTestId('charge-code').fill('KITTING');
      await page.getByTestId('charge-rate').fill('3.5');
      await page.getByTestId('charge-from').fill(inDays(10));
      await page.getByTestId('charge-reason').fill('client asked for kitting');
      await page.getByTestId('request-charge').click();
      await expect(page.getByTestId('contract-message')).toHaveText('Charge requested: awaiting approval.');
      expect(await versionsOf(request, session, client.id)).toHaveLength(1);
      await decide(page, code, 'approve');

      const versions = await versionsOf(request, session, client.id);
      expect(versions).toHaveLength(2);
      expect(versions[0].effectiveFrom).toBe(inDays(10));
      expect(versions[1].effectiveTo).toBe(inDays(9));
      await openClient(page, client.id);
      await expect(page.getByTestId('version-row')).toHaveCount(2);
    });
  });
});
