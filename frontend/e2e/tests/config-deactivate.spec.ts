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
import { expect, test, type Locator, type Page } from '@playwright/test';
import { openScreen, signIn } from './support';
import { guideId, stubConfig, stubConfigSession, warehouseId, type ConfigStub, type Kind } from './support-config';

/*
 * Switching configuration off and on, in both consoles: allocation rules, organisational units, catalogue charges,
 * webhooks, custom fields, warehouse bins and SKUs, routing-guide vendors, and voiding a vendor invoice. The API is
 * stubbed so the same assertions run whatever the demo tenant holds; the stub records what each console sends (the
 * If-Match of every change, the reasons, the list filter) and the spec checks it. Set E2E_STUB_SESSION=1 to stub the
 * sign-in too, for a run with no backend at all.
 *
 * Every data-testid used here is listed in TESTIDS (support-config.ts) and checked against both consoles' sources by
 * config-testids.spec.ts, so a console that drops one fails there and not by a timeout here.
 */

let stub: ConfigStub;

test.beforeEach(async ({ page }) => {
  stub = await stubConfig(page);
  // Registered after the model's catch-all, so the sign-in is answered here.
  if (process.env.E2E_STUB_SESSION) {
    await stubConfigSession(page);
  }
});

const byName = (kind: Kind, field: string, value: string) => stub.records[kind].find((r) => r[field] === value)!;
const last = () => stub.changes[stub.changes.length - 1];
const switches = () => stub.changes.filter((c) => /\/(deactivate|reactivate|void)$/.test(c.path));

/** Presses a switch button inside `scope`, says why, and confirms. */
async function switchOver(page: Page, scope: Locator, button: string, reason: string) {
  await scope.getByTestId(button).click();
  await expect(page.getByTestId('confirm-dialog')).toBeVisible();
  await page.getByTestId('reason-input').fill(reason);
  await page.getByTestId('confirm-accept').click();
}

test.describe('allocation rules', () => {
  const row = (page: Page, name: string) => page.getByTestId('rule-row').filter({ hasText: name });

  test('the default list is the active rules; the filter brings the others, and a switch needs a reason and sends the version', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-allocation-rules', 'Allocation rules');

    await expect(page.getByTestId('rule-row')).toHaveCount(1);
    await page.getByTestId('status-filter').selectOption('INACTIVE');
    await expect(page.getByTestId('rule-row')).toHaveCount(1);
    await expect(row(page, 'Old seasonal rule')).toBeVisible();
    await page.getByTestId('status-filter').selectOption('ALL');
    await expect(page.getByTestId('rule-row')).toHaveCount(2);
    expect(stub.listQueries.allocationRules.some((q) => q.includes('status=INACTIVE'))).toBe(true);

    await page.getByTestId('status-filter').selectOption('');
    const target = row(page, 'Prefer Swift');
    await target.getByTestId('deactivate-record').click();
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('reason-error')).toBeVisible();
    expect(switches()).toHaveLength(0);

    await page.getByTestId('reason-input').fill('Seasonal pause');
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('rule-row')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'POST', ifMatch: '0', body: { reason: 'Seasonal pause' } });
    expect(last().path).toMatch(/\/allocation-rules\/[^/]+\/deactivate$/);

    await page.getByTestId('status-filter').selectOption('ALL');
    await switchOver(page, row(page, 'Prefer Swift'), 'reactivate-record', 'Peak season');
    expect(last().path).toMatch(/\/reactivate$/);
    expect(last()).toMatchObject({ ifMatch: '1', body: { reason: 'Peak season' } });
    await expect(row(page, 'Prefer Swift').getByTestId('rule-status')).toHaveAttribute('data-value', 'ACTIVE');
  });

  test('a rule somebody else changed says so, and an edit from a stale version is refused with the readable notice', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-allocation-rules', 'Allocation rules');
    const rule = byName('allocationRules', 'name', 'Prefer Swift');

    await row(page, 'Prefer Swift').getByTestId('deactivate-record').click();
    stub.touch('allocationRules', rule.id);
    await page.getByTestId('reason-input').fill('Seasonal pause');
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('action-error')).toContainText('Someone else changed this record');
    expect(rule.active).toBe(true);

    await page.getByTestId('confirm-dismiss').click();
    await row(page, 'Prefer Swift').getByTestId('rule-edit').click();
    await expect(page.getByTestId('rule-form')).toBeVisible();
    stub.touch('allocationRules', rule.id);
    await page.getByTestId('rule-priority').fill('7');
    await page.getByTestId('rule-save').click();
    await expect(page.getByTestId('conflict-notice')).toContainText('Someone else changed this record');
    expect(last()).toMatchObject({ method: 'PUT' });
    expect(rule.priority).toBe(10);
  });
});

test.describe('organisational units', () => {
  const row = (page: Page, name: string) => page.getByTestId('org-unit-row').filter({ hasText: name });

  test('a unit that still has active units or users refuses with its own words; a free one switches off and comes back', async ({ page }) => {
    stub.refuse.set(byName('orgUnits', 'name', 'North zone').id, {
      code: 'org-unit-in-use', detail: 'North zone still has 2 active unit(s) beneath it and 0 active user(s)' });
    await signIn(page);
    await openScreen(page, 'nav-admin-org-units', 'Organisation units');
    await expect(page.getByTestId('org-unit-row')).toHaveCount(3);

    await switchOver(page, row(page, 'North zone'), 'deactivate-record', 'Closing the zone');
    await expect(page.getByTestId('action-error')).toContainText('still has 2 active unit(s)');
    await page.getByTestId('confirm-dismiss').click();

    await switchOver(page, row(page, 'Gurgaon branch'), 'deactivate-record', 'Branch closed');
    await expect(row(page, 'Gurgaon branch')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'POST', ifMatch: '0', body: { reason: 'Branch closed' } });

    await page.getByTestId('status-filter').selectOption('ALL');
    await expect(page.getByTestId('org-unit-row')).toHaveCount(4);
    await switchOver(page, row(page, 'Gurgaon branch'), 'reactivate-record', 'Branch reopened');
    await expect(row(page, 'Gurgaon branch').getByTestId('deactivate-record')).toBeVisible();
  });
});

test.describe('charge catalogue', () => {
  const row = (page: Page, code: string) => page.getByTestId('cc-row').filter({ hasText: code });

  test('an inactive charge leaves the default list and comes back, and a stale edit says so', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-charge-catalogue', 'Charge catalogue');
    await expect(page.getByTestId('cc-row')).toHaveCount(1);

    await switchOver(page, row(page, 'LOADING'), 'deactivate-record', 'Not charged any more');
    await expect(page.getByTestId('cc-row')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'POST', ifMatch: '0', body: { reason: 'Not charged any more' } });

    await page.getByTestId('status-filter').selectOption('ALL');
    await expect(page.getByTestId('cc-row')).toHaveCount(2);
    await switchOver(page, row(page, 'TOLL'), 'reactivate-record', 'Tolls are back');
    expect(last().path).toMatch(/\/reactivate$/);

    const loading = byName('chargeCatalogue', 'code', 'LOADING');
    await row(page, 'LOADING').getByTestId('cc-edit').click();
    await expect(page.getByTestId('cc-name')).toHaveValue('Loading');
    stub.touch('chargeCatalogue', loading.id);
    await page.getByTestId('cc-name').fill('Loading and unloading');
    await page.getByTestId('cc-save').click();
    await expect(page.getByTestId('conflict-notice')).toContainText('Someone else changed this record');
  });
});

test.describe('webhooks', () => {
  test('a subscription is switched off from its panel, and a stale edit reloads it with the notice', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-webhooks', 'Webhooks');
    await expect(page.getByTestId('webhooks-row')).toHaveCount(1);

    await page.getByTestId('webhooks-row').filter({ hasText: 'ERP feed' }).getByRole('button').click();
    const detail = page.getByTestId('webhook-detail');
    await expect(detail).toBeVisible();

    const hook = byName('webhooks', 'name', 'ERP feed');
    stub.touch('webhooks', hook.id);
    await detail.getByTestId('edit-name').fill('ERP feed v2');
    await page.getByTestId('save-webhook').click();
    await expect(page.getByTestId('conflict-notice')).toContainText('Someone else changed this record');
    expect(hook.name).toBe('ERP feed');

    await switchOver(page, page.getByTestId('webhook-detail'), 'deactivate-record', 'Receiver down');
    expect(last()).toMatchObject({ method: 'POST', ifMatch: '1', body: { reason: 'Receiver down' } });
    await page.getByTestId('status-filter').selectOption('ALL');
    await expect(page.getByTestId('webhooks-row')).toHaveCount(2);
    await expect(page.getByTestId('webhooks-row').filter({ hasText: 'ERP feed' })).toContainText('Inactive');
  });
});

test.describe('custom fields', () => {
  test('a field is switched off with a reason and shown again through the filter', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-custom-fields', 'Custom fields');
    await page.getByTestId('kind').selectOption('PARTNER');
    await expect(page.getByTestId('def-credit_note')).toBeVisible();
    await expect(page.getByTestId('def-old_tier')).toHaveCount(0);

    await switchOver(page, page.getByTestId('def-credit_note'), 'deactivate-record', 'Not used');
    await expect(page.getByTestId('def-credit_note')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'POST', ifMatch: '0', body: { reason: 'Not used' } });

    await page.getByTestId('status-filter').selectOption('INACTIVE');
    await expect(page.getByTestId('def-credit_note')).toContainText('Inactive');
    await switchOver(page, page.getByTestId('def-old_tier'), 'reactivate-record', 'Needed again');
    expect(last().path).toMatch(/\/reactivate$/);
  });
});

test.describe('warehouse bins and SKUs', () => {
  test('a bin holding stock refuses to switch off in its own words; filters and switches work on both lists', async ({ page }) => {
    stub.refuse.set(byName('bins', 'code', 'A-01').id, {
      code: 'bin-not-empty', detail: 'Bin A-01 still holds 10 unit(s); move or adjust them out first' });
    await signIn(page);
    await openScreen(page, 'nav-warehouse', 'Warehouse operations');
    await page.getByTestId('warehouse-id').fill(warehouseId);
    await page.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('warehouse-desk')).toBeVisible();
    await page.getByTestId('tab-setup').click();
    await expect(page.getByTestId('bin-row')).toHaveCount(2);
    await expect(page.getByTestId('sku-row')).toHaveCount(1);

    const bin = (code: string) => page.getByTestId('bin-row').filter({ hasText: code });
    await switchOver(page, bin('A-01'), 'deactivate-record', 'Racking removed');
    await expect(page.getByTestId('action-error')).toContainText('still holds 10 unit(s)');
    await page.getByTestId('confirm-dismiss').click();

    await switchOver(page, bin('A-02'), 'deactivate-record', 'Racking removed');
    await expect(page.getByTestId('bin-row')).toHaveCount(1);
    expect(last().path).toContain(`/warehouses/${warehouseId}/bins/`);
    expect(last()).toMatchObject({ ifMatch: '0', body: { reason: 'Racking removed' } });
    await page.getByTestId('bin-status-filter').selectOption('ALL');
    await expect(page.getByTestId('bin-row')).toHaveCount(3);
    await switchOver(page, bin('A-02'), 'reactivate-record', 'Racking back');

    const sku = (code: string) => page.getByTestId('sku-row').filter({ hasText: code });
    await switchOver(page, sku('WIDGET'), 'deactivate-record', 'Range closed');
    await expect(page.getByTestId('sku-row')).toHaveCount(0);
    await page.getByTestId('sku-status-filter').selectOption('ALL');
    await expect(page.getByTestId('sku-row')).toHaveCount(2);
    await switchOver(page, sku('GADGET'), 'reactivate-record', 'Range extended');
    expect(last().path).toMatch(/\/skus\/[^/]+\/reactivate$/);
  });
});

test.describe('routing guide vendors', () => {
  test('a vendor is switched off and on on its guide, and one with an open offer says why not', async ({ page }) => {
    stub.refuse.set(byName('guideVendors', 'vendorName', 'Steady Haulage').id, {
      code: 'routing-guide-vendor-in-use', detail: 'Vendor holds 1 open offer(s) on this guide; wait for the answer' });
    await signIn(page);
    await openScreen(page, 'nav-commercial', 'Commercial');
    const open = page.getByTestId('open-guide');
    await open.getByLabel('Guide id', { exact: true }).fill(guideId);
    await open.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('guide-panel')).toBeVisible();
    await expect(page.getByTestId('guide-vendor')).toHaveCount(2);

    const vendor = (name: string) => page.getByTestId('guide-vendor').filter({ hasText: name });
    await switchOver(page, vendor('Steady Haulage'), 'deactivate-record', 'Contract on hold');
    await expect(page.getByTestId('action-error')).toContainText('open offer');
    await page.getByTestId('confirm-dismiss').click();

    await switchOver(page, vendor('Swift Carriers'), 'deactivate-record', 'Contract on hold');
    await expect(page.getByTestId('guide-vendor')).toHaveCount(1);
    expect(last().path).toMatch(/\/routing-guides\/[^/]+\/vendors\/[^/]+\/deactivate$/);
    expect(last()).toMatchObject({ ifMatch: '0', body: { reason: 'Contract on hold' } });

    await page.getByTestId('guide-panel').getByTestId('status-filter').selectOption('ALL');
    await expect(page.getByTestId('guide-vendor')).toHaveCount(3);
    await switchOver(page, vendor('Lapsed Lines'), 'reactivate-record', 'Back in contract');
    expect(last().path).toMatch(/\/reactivate$/);
  });
});

test.describe('vendor invoices', () => {
  test('an unpaid invoice is voided with a reason; one with a payment against it offers no void', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-payables', 'Vendor payables');
    const row = (no: string) => page.getByTestId('inv-row').filter({ hasText: no });

    await row('VI-PART').click();
    await expect(page.getByTestId('inv-detail')).toBeVisible();
    await expect(page.getByTestId('void-record')).toHaveCount(0);

    await row('VI-OPEN').click();
    await expect(page.getByTestId('inv-detail')).toHaveAttribute('aria-label', 'Invoice VI-OPEN');
    await page.getByTestId('void-record').click();
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('reason-error')).toBeVisible();
    await page.getByTestId('reason-input').fill('Raised against the wrong bill');
    await page.getByTestId('confirm-accept').click();
    await expect(page.getByTestId('detail-status')).toHaveAttribute('data-value', 'VOID');
    await expect(page.getByTestId('detail-void-reason')).toContainText('Raised against the wrong bill');
    await expect(page.getByTestId('void-record')).toHaveCount(0);
    await expect(page.getByTestId('pay-form')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'POST', ifMatch: '0', body: { reason: 'Raised against the wrong bill' } });
    expect(last().path).toMatch(/\/vendor-invoices\/[^/]+\/void$/);
  });
});

test.describe('access policies', () => {
  test('an edit made from a stale version says someone else changed it, and reloads the policy', async ({ page }) => {
    await signIn(page);
    await openScreen(page, 'nav-admin-policies', 'Access policies');
    await page.getByTestId('policies-row').filter({ hasText: 'no-sunday-approvals' }).getByRole('button').click();
    await expect(page.getByTestId('policy-detail')).toBeVisible();

    const policy = byName('accessPolicies', 'code', 'no-sunday-approvals');
    stub.touch('accessPolicies', policy.id);
    await page.getByTestId('policy-description').fill('No approvals on Sunday');
    await page.getByTestId('save-policy').click();
    await expect(page.getByTestId('conflict-notice')).toContainText('Someone else changed this record');
    expect(policy.description).toBe('No Sunday approvals');
    expect(last()).toMatchObject({ method: 'PUT', ifMatch: '0' });

    await page.getByTestId('policy-description').fill('No approvals on Sunday');
    await page.getByTestId('save-policy').click();
    await expect(page.getByTestId('admin-message')).toHaveText('Policy saved.');
    expect(last()).toMatchObject({ method: 'PUT', ifMatch: '1' });
  });
});
