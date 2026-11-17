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
import { signIn, withValue } from './support';
import { stubMasterData, stubSession, type MasterStub } from './support-masterdata';

/*
 * Maintaining partners, vehicles, drivers and terminals, in both consoles. The API is stubbed so the same
 * assertions run whatever the demo tenant holds; the stub records what each console sends (the If-Match of
 * every change, the reasons, the list filter) and the spec checks it. Set E2E_STUB_SESSION=1 to stub the
 * sign-in too, for a run with no backend at all.
 *
 * Every data-testid used here is listed in TESTIDS (support-masterdata.ts) and checked against both consoles' sources by
 * master-data-testids.spec.ts, so a console that drops one fails there and not by a timeout here.
 */

let stub: MasterStub;

test.beforeEach(async ({ page }) => {
  if (process.env.E2E_STUB_SESSION) {
    await stubSession(page);
  }
  stub = await stubMasterData(page);
});

async function openList(page: Page, key: 'partners' | 'vehicles' | 'drivers' | 'terminals', title: string) {
  await page.getByTestId(`nav-${key}`).click();
  await expect(page.getByRole('heading', { name: title, level: 1 })).toBeVisible();
  await expect(page.getByText('Loading…')).toHaveCount(0);
}

const rowFor = (page: Page, id: string) => page.locator(`[data-testid="row"][data-id="${id}"]`);
const idOf = (kind: keyof MasterStub['records'], identity: string) =>
  stub.records[kind].find((r) => Object.values(r).includes(identity))!.id;
const last = () => stub.changes[stub.changes.length - 1];

/** Opens a record's panel and waits for it to show the record. */
async function openPanel(page: Page, id: string) {
  await rowFor(page, id).click();
  await expect(page.getByTestId('record-panel')).toBeVisible();
}

async function switchOff(page: Page, button: string, reason: string) {
  await page.getByTestId(button).click();
  await expect(page.getByTestId('confirm-panel')).toBeVisible();
  await page.getByTestId('reason-input').fill(reason);
  await page.getByTestId('confirm-action').click();
}

test.describe('lists', () => {
  test('a list shows the active records by default and every status on request, each row addressed by id', async ({ page }) => {
    await signIn(page);
    await openList(page, 'vehicles', 'Vehicles');

    await expect(page.getByTestId('row')).toHaveCount(1);
    await expect(rowFor(page, idOf('vehicles', 'MH-01-AA-0001'))).toBeVisible();
    await expect(withValue(rowFor(page, idOf('vehicles', 'MH-01-AA-0001')), 'AVAILABLE')).toBeVisible();
    await expect(rowFor(page, idOf('vehicles', 'MH-01-AA-0002'))).toHaveCount(0);

    await page.getByTestId('status-filter').selectOption('ALL');
    await expect(page.getByTestId('row')).toHaveCount(2);
    await expect(withValue(rowFor(page, idOf('vehicles', 'MH-01-AA-0002')), 'INACTIVE')).toBeVisible();

    await page.getByTestId('status-filter').selectOption('INACTIVE');
    await expect(page.getByTestId('row')).toHaveCount(1);
    expect(stub.listQueries.vehicles.some((q) => q.includes('status=INACTIVE'))).toBe(true);
  });

  test('the New button sits in the page header, and a record asks nothing of the list until it is saved', async ({ page }) => {
    await signIn(page);
    await openList(page, 'drivers', 'Drivers');
    await expect(page.locator('.page-header').getByTestId('new-record')).toBeVisible();
  });
});

test.describe('partners', () => {
  test('a partner is created, edited under If-Match, and a stale edit says so and reloads', async ({ page }) => {
    await signIn(page);
    await openList(page, 'partners', 'Partners');
    await expect(page.getByTestId('row')).toHaveCount(1); // the suspended partner is not in the default list

    await test.step('creating validates inline, then saves', async () => {
      await page.getByTestId('new-record').click();
      await page.getByTestId('save-record').click();
      await expect(page.getByTestId('error-code')).toBeVisible();
      await expect(page.getByTestId('error-legalName')).toBeVisible();
      expect(stub.changes).toHaveLength(0);

      await page.getByTestId('field-code').fill('NEWCO');
      await page.getByTestId('field-legalName').fill('New Co Pvt Ltd');
      await page.getByTestId('field-partnerType').selectOption('VENDOR');
      await page.getByTestId('field-taxId').fill('TAX-1');
      await page.getByTestId('save-record').click();
      await expect(page.getByTestId('record-form')).toHaveCount(0);
      expect(last().body).toMatchObject({ code: 'NEWCO', legalName: 'New Co Pvt Ltd', partnerType: 'VENDOR', taxId: 'TAX-1' });
      await expect(page.getByTestId('row').filter({ hasText: 'NEWCO' })).toBeVisible();
    });

    await test.step('editing sends the version it read in If-Match', async () => {
      const id = idOf('partners', 'ACME');
      await openPanel(page, id);
      await page.getByTestId('edit-record').click();
      await expect(page.getByTestId('field-legalName')).toHaveValue('Acme Retail');
      await page.getByTestId('field-legalName').fill('Acme Retail Ltd');
      await page.getByTestId('field-paymentTerms').fill('NET30');
      await page.getByTestId('save-record').click();
      await expect(page.getByTestId('record-form')).toHaveCount(0);
      expect(last()).toMatchObject({ method: 'PUT', ifMatch: '0' });
      expect(last().body).toMatchObject({ legalName: 'Acme Retail Ltd', paymentTerms: 'NET30' });
      await expect(rowFor(page, id)).toContainText('Acme Retail Ltd');
    });

    await test.step('a stale version is a readable message, the form is reloaded, and a retry succeeds', async () => {
      const id = idOf('partners', 'ACME');
      await openPanel(page, id);
      await page.getByTestId('edit-record').click();
      await expect(page.getByTestId('field-legalName')).toHaveValue('Acme Retail Ltd'); // read, at version 1
      stub.touch('partners', id);
      stub.records.partners.find((r) => r.id === id)!.legalName = 'Acme (changed elsewhere)';
      await page.getByTestId('field-legalName').fill('Mine');
      await page.getByTestId('save-record').click();
      await expect(page.getByTestId('conflict-notice')).toBeVisible();
      await expect(page.getByTestId('conflict-notice')).not.toContainText('concurrent-modification');
      await expect(page.getByTestId('field-legalName')).toHaveValue('Acme (changed elsewhere)');
      await page.getByTestId('field-legalName').fill('Mine');
      await page.getByTestId('save-record').click();
      await expect(page.getByTestId('record-form')).toHaveCount(0);
      expect(last().ifMatch).toBe('2');
    });
  });

  test('deactivating asks for a confirmation and a reason; the partner leaves the default list and can be reactivated', async ({ page }) => {
    await signIn(page);
    await openList(page, 'partners', 'Partners');
    const id = idOf('partners', 'ACME');
    await openPanel(page, id);

    await page.getByTestId('deactivate-record').click();
    await expect(page.getByTestId('confirm-panel')).toBeVisible();
    await page.getByTestId('confirm-action').click();
    await expect(page.getByTestId('reason-error')).toBeVisible();
    expect(stub.changes).toHaveLength(0);
    await page.getByTestId('cancel-action').click();
    await expect(page.getByTestId('confirm-panel')).toHaveCount(0);

    await switchOff(page, 'deactivate-record', 'Credit review');
    await expect(rowFor(page, id)).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'POST', ifMatch: '0', body: { target: 'SUSPENDED', reason: 'Credit review' } });

    await page.getByTestId('status-filter').selectOption('SUSPENDED');
    await openPanel(page, id);
    await expect(page.getByTestId('deactivate-record')).toHaveCount(0);
    await switchOff(page, 'reactivate-record', 'Review passed');
    expect(last().body).toMatchObject({ target: 'ACTIVE', reason: 'Review passed' });
    await page.getByTestId('status-filter').selectOption('');
    await expect(rowFor(page, id)).toBeVisible();
  });

  test('blacklisting is confirmed with a reason and is final', async ({ page }) => {
    await signIn(page);
    await openList(page, 'partners', 'Partners');
    const id = idOf('partners', 'ACME');
    await openPanel(page, id);
    await switchOff(page, 'blacklist-record', 'Fraud');
    expect(last().body).toMatchObject({ target: 'BLACKLISTED', reason: 'Fraud' });
    await page.getByTestId('status-filter').selectOption('BLACKLISTED');
    await openPanel(page, id);
    await expect(page.getByTestId('reactivate-record')).toHaveCount(0);
    await expect(page.getByTestId('blacklist-record')).toHaveCount(0);
  });
});

test.describe('vehicles', () => {
  test('a vehicle is registered with its owner picked, edited, switched off with a reason and switched back on', async ({ page }) => {
    await signIn(page);
    await openList(page, 'vehicles', 'Vehicles');

    await page.getByTestId('new-record').click();
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('error-registrationNo')).toBeVisible();
    await page.getByTestId('field-registrationNo').fill('MH-02-ZZ-9999');
    await page.getByTestId('field-category').fill('TRUCK');
    await page.getByTestId('field-vehicleType').fill('TRUCK_32FT');
    await page.getByTestId('field-grossWeightKg').fill('25000');
    await page.getByTestId('field-tareWeightKg').fill('9000');
    await page.getByTestId('field-maxVolumeM3').fill('60');
    await page.getByTestId('field-ownerPartnerId').fill(idOf('partners', 'ACME'));
    await page.getByTestId('field-hazmatCertified').check();
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('record-form')).toHaveCount(0);
    expect(last().body).toMatchObject({
      registrationNo: 'MH-02-ZZ-9999', vehicleType: 'TRUCK_32FT', grossWeightKg: 25000, tareWeightKg: 9000,
      maxVolumeM3: 60, ownerPartnerId: idOf('partners', 'ACME'), hazmatCertified: true, reeferCapable: false,
    });
    const created = idOf('vehicles', 'MH-02-ZZ-9999');
    await expect(rowFor(page, created)).toBeVisible();

    await openPanel(page, created);
    await page.getByTestId('edit-record').click();
    await expect(page.getByTestId('field-registrationNo')).toBeDisabled();
    await page.getByTestId('field-vehicleType').fill('TRUCK_40FT');
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('record-form')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'PUT', ifMatch: '0' });
    expect(last().body).toMatchObject({ vehicleType: 'TRUCK_40FT' });

    await switchOff(page, 'deactivate-record', 'Sold');
    await expect(rowFor(page, created)).toHaveCount(0);
    expect(last().path).toMatch(/\/vehicles\/.+\/deactivate$/);
    expect(last().body).toEqual({ reason: 'Sold' });

    await page.getByTestId('status-filter').selectOption('INACTIVE');
    await openPanel(page, created);
    await switchOff(page, 'reactivate-record', 'Bought back');
    expect(last().path).toMatch(/\/vehicles\/.+\/reactivate$/);
    await page.getByTestId('status-filter').selectOption('');
    await expect(withValue(rowFor(page, created), 'AVAILABLE')).toBeVisible();
  });
});

test.describe('drivers', () => {
  test('a driver is registered, edited, switched off and on again', async ({ page }) => {
    await signIn(page);
    await openList(page, 'drivers', 'Drivers');

    await page.getByTestId('new-record').click();
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('error-fullName')).toBeVisible();
    await page.getByTestId('field-fullName').fill('Meena Joshi');
    await page.getByTestId('field-phone').fill('9111111111');
    await page.getByTestId('field-licenceNo').fill('DL-0099');
    await page.getByTestId('field-licenceClass').fill('HMV');
    await page.getByTestId('field-licenceExpiresOn').fill('2031-05-01');
    await page.getByTestId('field-hazmatEndorsed').check();
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('record-form')).toHaveCount(0);
    expect(last().body).toMatchObject({ fullName: 'Meena Joshi', licenceNo: 'DL-0099', licenceExpiresOn: '2031-05-01', hazmatEndorsed: true });
    const created = idOf('drivers', 'DL-0099');

    await openPanel(page, created);
    await page.getByTestId('edit-record').click();
    await expect(page.getByTestId('field-licenceNo')).toBeDisabled();
    await page.getByTestId('field-phone').fill('9222222222');
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('record-form')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'PUT', ifMatch: '0', body: { phone: '9222222222' } });

    await switchOff(page, 'deactivate-record', 'Left the company');
    await expect(rowFor(page, created)).toHaveCount(0);
    await page.getByTestId('status-filter').selectOption('INACTIVE');
    await openPanel(page, created);
    await switchOff(page, 'reactivate-record', 'Rejoined');
    await page.getByTestId('status-filter').selectOption('');
    await expect(rowFor(page, created)).toBeVisible();
  });
});

test.describe('terminals', () => {
  test('a point-radius terminal is created and edited; a polygon is entered as lines of latitude and longitude', async ({ page }) => {
    await signIn(page);
    await openList(page, 'terminals', 'Terminals');

    await page.getByTestId('new-record').click();
    await page.getByTestId('field-code').fill('PORT-1');
    await page.getByTestId('field-name').fill('Harbour');
    await page.getByTestId('field-functionalCategory').selectOption('PORT');
    await page.getByTestId('field-centreLat').fill('18.95');
    await page.getByTestId('field-centreLon').fill('72.84');
    await page.getByTestId('field-radiusMetres').fill('500');
    await page.getByTestId('field-dockCount').fill('6');
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('record-form')).toHaveCount(0);
    expect(last().path).toMatch(/\/terminals\/point-radius$/);
    expect(last().body).toMatchObject({ code: 'PORT-1', name: 'Harbour', category: 'PORT', centre: { lat: 18.95, lon: 72.84 }, radiusMetres: 500, dockCount: 6 });

    await page.getByTestId('new-record').click();
    await page.getByTestId('field-code').fill('YARD-1');
    await page.getByTestId('field-name').fill('Yard');
    await page.getByTestId('field-functionalCategory').selectOption('HUB');
    await page.getByTestId('field-geofenceType').selectOption('POLYGON');
    await page.getByTestId('field-ring').fill('19.00, 72.80\n19.00, 72.81\n19.01, 72.81\n19.01, 72.80');
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('record-form')).toHaveCount(0);
    expect(last().path).toMatch(/\/terminals\/polygon$/);
    expect((last().body.ring as unknown[]).length).toBe(4);

    const id = idOf('terminals', 'WH-1');
    await openPanel(page, id);
    await page.getByTestId('edit-record').click();
    await page.getByTestId('field-name').fill('Main warehouse (north)');
    await page.getByTestId('field-radiusMetres').fill('350');
    await page.getByTestId('save-record').click();
    await expect(page.getByTestId('record-form')).toHaveCount(0);
    expect(last()).toMatchObject({ method: 'PUT', ifMatch: '0' });
    expect(last().body).toMatchObject({ name: 'Main warehouse (north)', radiusMetres: 350, version: 0 });
  });

  test('a terminal with open work refuses to switch off, in words; a free one switches off and on', async ({ page }) => {
    await signIn(page);
    await openList(page, 'terminals', 'Terminals');
    const id = idOf('terminals', 'WH-1');
    stub.busyTerminals.add(id);
    await openPanel(page, id);

    await switchOff(page, 'deactivate-record', 'Closing');
    await expect(page.getByTestId('action-error')).toContainText('open trips');
    await expect(rowFor(page, id)).toBeVisible();

    stub.busyTerminals.clear();
    await page.getByTestId('confirm-action').click();
    await expect(rowFor(page, id)).toHaveCount(0);

    await page.getByTestId('status-filter').selectOption('INACTIVE');
    await openPanel(page, id);
    await switchOff(page, 'reactivate-record', 'Reopened');
    await page.getByTestId('status-filter').selectOption('');
    await expect(withValue(rowFor(page, id), 'ACTIVE')).toBeVisible();
  });
});
