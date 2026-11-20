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
import { openScreen, signIn } from './support';
import {
  consignmentId, draftOrderId, otherTerminalId, stubSmallOps, stubSmallOpsSession, terminalId, validatedOrderId,
  type SmallOpsStub,
} from './support-small-ops';

/*
 * The small API-only operations, in both consoles: approval policies (set and reset), a number series' revision under
 * If-Match, one line added to a draft order and one cancelled on a validated order, the order close policy, an e-way
 * bill recorded by hand and route corridors imported and cleared. The API is stubbed so the same assertions run
 * whatever the demo tenant holds; the stub keeps the server's rules and the spec checks what each console sends. Set
 * E2E_STUB_SESSION=1 to stub the sign-in too, for a run with no backend at all.
 */

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubSmallOpsSession(page);
  }
});

/** The last change the stub saw whose path ends as given. */
function sent(stub: SmallOpsStub, method: string, ending: string) {
  return stub.changes.filter((c) => c.method === method && c.path.endsWith(ending)).at(-1);
}

test.describe('approval policies', () => {
  test('a policy is set, then reset to its default; a mandatory one cannot be switched off', async ({ page }) => {
    const stub = await stubSmallOps(page);
    await signIn(page);
    await openScreen(page, 'nav-approvals', 'Approvals');
    const policies = page.getByTestId('approval-policies');
    await expect(policies.getByTestId('apol-row')).toHaveCount(2);

    await test.step('setting a policy sends every value and shows the result', async () => {
      await policies.getByTestId('apol-row').first().getByRole('button').click();
      await expect(policies.getByTestId('apol-steps')).toHaveValue('ORDER_APPROVE');
      await policies.getByTestId('apol-required').check();
      await policies.getByTestId('apol-steps').fill('order_approve, finance_head');
      await policies.getByTestId('apol-ordered').check();
      await policies.getByTestId('apol-due').fill('60');
      await policies.getByTestId('apol-expiry').selectOption('AUTO_APPROVE');
      await policies.getByTestId('apol-set').click();
      await expect(policies.getByTestId('apol-done')).toHaveText('Policy for ORDER_CANCELLATION set.');
      expect(sent(stub, 'PUT', '/policies/ORDER_CANCELLATION')?.body).toEqual({
        required: true, decidePermissions: ['ORDER_APPROVE', 'FINANCE_HEAD'], ordered: true, allowSelfApproval: false,
        dueMinutes: 60, onExpiry: 'AUTO_APPROVE',
      });
      const row = policies.getByTestId('apol-row').first();
      await expect(row).toContainText('ORDER_APPROVE → FINANCE_HEAD');
      await expect(row).toContainText('AUTO_APPROVE');
    });

    await test.step('resetting takes the policy back to the default', async () => {
      await policies.getByTestId('apol-reset').click();
      await expect(policies.getByTestId('apol-done')).toHaveText('Policy for ORDER_CANCELLATION reset to the default.');
      expect(sent(stub, 'DELETE', '/policies/ORDER_CANCELLATION')).toBeDefined();
      await expect(policies.getByTestId('apol-row').first()).not.toContainText('FINANCE_HEAD');
      await expect(policies.getByTestId('apol-steps')).toHaveValue('ORDER_APPROVE');
    });

    await test.step('a policy the server insists on cannot be switched off', async () => {
      await policies.getByTestId('apol-row').nth(1).getByRole('button').click();
      await expect(policies.getByTestId('apol-required')).toBeDisabled();
      await expect(policies.getByTestId('apol-required')).toBeChecked();
    });
  });

  test('without the right to manage policies they are listed but not editable', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubSmallOpsSession(page, ['APPROVAL_POLICY_MANAGE']);
    await stubSmallOps(page);
    await signIn(page);
    await openScreen(page, 'nav-approvals', 'Approvals');
    const policies = page.getByTestId('approval-policies');
    await expect(policies.getByTestId('apol-row')).toHaveCount(2);
    await expect(policies.getByRole('button')).toHaveCount(0);
    await expect(policies.getByTestId('apol-form')).toHaveCount(0);
  });
});

test.describe('number series revision', () => {
  async function openSeries(page: Page) {
    await openScreen(page, 'nav-admin-number-series', 'Number series');
    await page.getByTestId('series-row').getByRole('button').first().click();
    await expect(page.getByTestId('series-detail')).toBeVisible();
    await expect(page.getByTestId('revise-prefix')).toHaveValue('LR');
  }

  test('a revision goes under If-Match; a stale one is refused and re-read; a used format is locked', async ({ page }) => {
    const stub = await stubSmallOps(page);
    await signIn(page);
    await openSeries(page);
    const revise = page.getByTestId('series-revise');

    await test.step('revising sends the version read and the whole format', async () => {
      await revise.getByTestId('revise-prefix').fill('LRN');
      await revise.getByTestId('revise-description').fill('Renamed');
      await revise.getByTestId('revise-reuse').check();
      await revise.getByTestId('revise-apply').click();
      await expect(revise.getByTestId('revise-done')).toBeVisible();
      const put = sent(stub, 'PUT', `/number-series/${stub.series.id}`);
      expect(put?.ifMatch?.replace(/"/g, '')).toBe('3');
      expect(put?.body).toMatchObject({
        prefix: 'LRN', yearCode: 'START_YEAR', counterLength: 5, yearStartMonth: 4, reuseReleased: true, description: 'Renamed',
      });
      await expect(page.getByTestId('series-row')).toContainText('LRN-2026-00001');
    });

    await test.step('a series changed by somebody else is refused, and the form shows what it is now', async () => {
      stub.reviseElsewhere();
      await revise.getByTestId('revise-description').fill('Mine');
      await revise.getByTestId('revise-apply').click();
      await expect(revise.getByTestId('revise-refusal')).toHaveText('concurrent-modification');
      await expect(revise.getByTestId('revise-description')).toHaveValue('Renamed');
      await revise.getByTestId('revise-description').fill('Mine');
      await revise.getByTestId('revise-apply').click();
      await expect(revise.getByTestId('revise-done')).toBeVisible();
      expect(stub.series.description).toBe('Mine');
    });

    await test.step('once numbers have been issued the format is locked but the description is not', async () => {
      stub.issued(7);
      await revise.getByTestId('revise-prefix').fill('XX');
      await revise.getByTestId('revise-apply').click();
      await expect(revise.getByTestId('revise-refusal')).toHaveText('number-series-in-use');
      await revise.getByTestId('revise-prefix').fill('LRN');
      await revise.getByTestId('revise-description').fill('Still editable');
      await revise.getByTestId('revise-apply').click();
      await expect(revise.getByTestId('revise-done')).toBeVisible();
      expect(stub.series.description).toBe('Still editable');
    });
  });
});

test.describe('order lines and close policy', () => {
  async function openOrder(page: Page, id: string) {
    await openScreen(page, 'nav-order-desk', 'Order desk');
    await page.getByTestId('order-id').fill(id);
    await page.getByTestId('open-order').getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('order-panel')).toBeVisible();
  }

  test('one line is appended to a draft order', async ({ page }) => {
    const stub = await stubSmallOps(page);
    await signIn(page);
    await openOrder(page, draftOrderId);
    await expect(page.getByTestId('order-line')).toHaveCount(1);
    await expect(page.getByTestId('cancel-lines')).toHaveCount(0);
    const form = page.getByTestId('append-line');
    await expect(form.getByTestId('append-line-no')).toHaveValue('2');

    await form.getByTestId('append-material').fill('BOLT');
    await form.getByTestId('append-description').fill('Bolts, boxed');
    await form.getByTestId('append-quantity').fill('12');
    await form.getByTestId('append-uom').fill('BOX');
    await form.getByTestId('append-weight').fill('48.5');
    await form.getByTestId('append-destination').fill(terminalId);
    await form.getByTestId('append-submit').click();
    await expect(page.getByTestId('line-op-done')).toHaveText('Line 2 appended.');
    await expect(page.getByTestId('order-line')).toHaveCount(2);
    expect(sent(stub, 'POST', `/orders/${draftOrderId}/lines`)?.body).toMatchObject({
      lineNo: 2, materialCode: 'BOLT', materialDescription: 'Bolts, boxed', materialClass: 'GENERAL', quantity: 12, uom: 'BOX',
      deadWeightKg: 48.5, destinationTerminalId: terminalId,
    });
    await expect(form.getByTestId('append-line-no')).toHaveValue('3');
    await expect(form.getByTestId('append-material')).toHaveValue('');
  });

  test('a line of a validated order is cancelled under its version, and the last live one is refused', async ({ page }) => {
    const stub = await stubSmallOps(page);
    await signIn(page);
    await openOrder(page, validatedOrderId);
    await expect(page.getByTestId('append-line')).toHaveCount(0);
    await expect(page.getByTestId('cancel-line')).toHaveCount(2);

    await page.getByTestId('cancel-line-reason').fill('customer dropped it');
    await page.getByTestId('cancel-line').first().click();
    await expect(page.getByTestId('line-op-done')).toContainText('Line 1 cancelled');
    const cancel = sent(stub, 'POST', '/cancellation');
    expect(cancel?.ifMatch?.replace(/"/g, '')).toBe('4');
    expect(cancel?.body).toEqual({ reason: 'customer dropped it' });
    await expect(page.getByTestId('line-closed').first()).toHaveText('CANCELLED');
    await expect(page.getByTestId('order-version')).toHaveText('5');
    await expect(page.getByTestId('cancel-line')).toHaveCount(1);

    await page.getByTestId('cancel-line').click();
    await expect(page.getByTestId('line-op-refusal')).toHaveText('order-line-last');
    expect(stub.orders[1].lines.filter((l) => !l.cancelled)).toHaveLength(1);
  });

  test('the close policy is read and set', async ({ page }) => {
    const stub = await stubSmallOps(page);
    await signIn(page);
    await openScreen(page, 'nav-order-desk', 'Order desk');
    const settings = page.getByTestId('close-settings');
    await settings.getByText('Order close policy').click();
    await expect(settings.getByTestId('close-settings-state')).toContainText('Tolerance 2%');
    await expect(settings.getByTestId('close-settings-state')).toContainText('platform default');

    await settings.getByTestId('close-tolerance').fill('7.5');
    await settings.getByTestId('close-auto-fulfil').check();
    await settings.getByTestId('close-settings-apply').click();
    await expect(settings.getByTestId('close-settings-done')).toBeVisible();
    expect(sent(stub, 'PUT', '/orders/close-settings')?.body).toEqual({ tolerancePercent: 7.5, autoFulfil: true });
    await expect(settings.getByTestId('close-settings-state')).toContainText('Tolerance 7.5%');
    await expect(settings.getByTestId('close-settings-state')).toContainText('set by this organisation');
  });

  test('without the rights the line operations and the policy form are not offered', async ({ page }) => {
    test.skip(!process.env.E2E_STUB_SESSION, 'dropping rights is done on the stubbed sign-in');
    await stubSmallOpsSession(page, ['ORDER_UPDATE', 'ORDER_FORCE_CLOSE']);
    await stubSmallOps(page);
    await signIn(page);
    await openOrder(page, validatedOrderId);
    await expect(page.getByTestId('cancel-lines')).toHaveCount(0);
    await expect(page.getByTestId('append-line')).toHaveCount(0);
    const settings = page.getByTestId('close-settings');
    await settings.getByText('Order close policy').click();
    await expect(settings.getByTestId('close-settings-state')).toBeVisible();
    await expect(settings.getByTestId('close-settings-form')).toHaveCount(0);
  });
});

test.describe('e-way bill recorded by hand', () => {
  test('a bill issued elsewhere is recorded against its consignment, once', async ({ page }) => {
    const stub = await stubSmallOps(page);
    await signIn(page);
    await openScreen(page, 'nav-eway-bills', 'E-way bills');
    const form = page.getByTestId('eway-record-form');

    async function record(billNo: string) {
      await form.getByTestId('eway-record-consignment').fill(consignmentId);
      await form.getByTestId('eway-record-no').fill(billNo);
      await form.getByTestId('eway-record-valid-until').fill('2026-12-01T12:00');
      await form.getByTestId('eway-record-vehicle').fill('MH12AB1234');
      await form.getByTestId('eway-record-distance').fill('300');
      await form.getByTestId('eway-record-submit').click();
    }

    await record('123456789012');
    await expect(page.getByTestId('eway-record-done')).toHaveText('Bill 123456789012 recorded.');
    const body = sent(stub, 'POST', '/eway-bills/record')?.body as { validUntil: string };
    expect(body).toMatchObject({ billNo: '123456789012', vehicleNo: 'MH12AB1234', distanceKm: 300 });
    expect(body.validUntil).toMatch(/^2026-12-01T/);
    await expect(page.getByTestId('eway-row')).toHaveCount(1);
    await expect(page.getByTestId('eway-row-no')).toHaveText('123456789012');
    await expect(page.getByTestId('eway-record-no')).toHaveValue('');

    await record('123456789012');
    await expect(page.getByTestId('eway-record-refusal')).toHaveText('eway-bill-number-taken');
    await expect(page.getByTestId('eway-row')).toHaveCount(1);
  });
});

test.describe('route corridors', () => {
  test('a corridor is imported for a lane and cleared again', async ({ page }) => {
    const stub = await stubSmallOps(page);
    await signIn(page);
    await openScreen(page, 'nav-exception-desk', 'Exception desk');
    const corridors = page.getByTestId('corridors');
    await expect(corridors.getByTestId('corridor-row')).toHaveCount(1);
    await expect(corridors.getByTestId('corridor-name')).toHaveText('Old route');

    async function fill(points: string) {
      await corridors.getByTestId('corridor-origin').fill(terminalId);
      await corridors.getByTestId('corridor-destination').fill(otherTerminalId);
      await corridors.getByTestId('corridor-name-input').fill('Ring road');
      await corridors.getByTestId('corridor-width').fill('250');
      await corridors.getByTestId('corridor-points').fill(points);
      await corridors.getByTestId('corridor-import').click();
    }

    await test.step('a malformed point is named and nothing is sent', async () => {
      await fill('77.1, 28.6\nnot a point');
      await expect(corridors.getByTestId('corridor-error')).toContainText('Line 2');
      expect(sent(stub, 'PUT', '/corridors')).toBeUndefined();
    });

    await test.step('importing replaces the lane\'s corridor', async () => {
      await corridors.getByTestId('corridor-points').fill('77.1, 28.6\n75.0 24.0\n72.8, 19.0');
      await corridors.getByTestId('corridor-import').click();
      await expect(corridors.getByTestId('corridor-done')).toHaveText('Corridor Ring road imported.');
      expect(sent(stub, 'PUT', '/corridors')?.body).toEqual({
        originTerminalId: terminalId, destinationTerminalId: otherTerminalId, name: 'Ring road',
        points: [[77.1, 28.6], [75, 24], [72.8, 19]], widthMetres: 250,
      });
      await expect(corridors.getByTestId('corridor-row')).toHaveCount(1);
      await expect(corridors.getByTestId('corridor-name')).toHaveText('Ring road');
    });

    await test.step('clearing takes the lane back to the straight line', async () => {
      await corridors.getByTestId('corridor-clear').click();
      await expect(corridors.getByTestId('corridor-done')).toHaveText('Corridor Ring road cleared.');
      expect(stub.changes.filter((c) => c.method === 'DELETE')).toHaveLength(1);
      await expect(corridors.getByTestId('corridor-row')).toHaveCount(0);
      await expect(corridors.getByTestId('no-corridors')).toBeVisible();
    });
  });
});
