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
import { apiSession, newPlannedLoad, openScreen, printed, signIn, stubPrint, tag, withoutPermissions } from './support';
import { commercialGuide, commercialLane, commercialVendor, deliveredTrip } from './support-commercial';
// Rate cards and rail tariffs
import { commercialRailheads, commercialRateCard, commercialRateCards } from './support-commercial';

/** An amount as the screen shows it, whether or not trailing zeros are kept. */
function amount(value: number): RegExp {
  return new RegExp(`^${value}(\\.0+)?(\\s|$)`);
}

async function openCommercial(page: Page): Promise<void> {
  await openScreen(page, 'nav-commercial', 'Commercial');
}

/** Opens a freight bill by its id, whatever page of the list it is on. */
async function openBill(page: Page, billId: string) {
  const form = page.getByTestId('open-bill');
  await form.getByLabel('Bill id').fill(billId);
  await form.getByRole('button', { name: 'Open' }).click();
  const panel = page.getByTestId('bill-panel');
  await expect(panel).toBeVisible();
  return panel;
}

test.describe('commercial desk', () => {
  test('a rate card is published, given weight bands, and a month\'s fuel surcharge is recorded', async ({ page, request }, info) => {
    const salt = tag(info);
    const session = await apiSession(request);
    const { origin, destination } = await commercialLane(request, session, salt);
    const vendor = await commercialVendor(request, session, `ZZ-CM-RV-${salt}`);
    await signIn(page);
    await openCommercial(page);

    const form = page.getByTestId('publish-tariff');
    await form.getByLabel('Code').fill(`ZZ-CM-CARD-${salt}`);
    await form.getByLabel('Vendor').fill(vendor.id);
    await form.getByLabel('Origin').fill(origin.id);
    await form.getByLabel('Destination').fill(destination.id);
    await form.getByLabel('Vehicle type').fill('RIGID');
    await form.getByLabel('Currency').fill('INR');
    await form.getByLabel('Minimum charge').fill('5000');
    await form.getByRole('button', { name: 'Publish rate card' }).click();

    const card = page.getByTestId('tariff-panel');
    await expect(card.getByTestId('tariff-code')).toHaveText(`ZZ-CM-CARD-${salt}`);
    await expect(card.getByTestId('no-slabs')).toBeVisible();

    await test.step('two weight bands, per kilogram then flat', async () => {
      const band = card.getByTestId('add-slab');
      await band.getByLabel('From kg').fill('0');
      await band.getByLabel('To kg').fill('10000');
      await band.getByLabel('Rate', { exact: true }).fill('4');
      await band.getByRole('button', { name: 'Add band' }).click();
      await expect(card.getByTestId('slab-row')).toHaveCount(1);
      await band.getByLabel('From kg').fill('10000');
      await band.getByLabel('Rate basis').selectOption('FLAT');
      await band.getByLabel('Rate', { exact: true }).fill('45000');
      await band.getByRole('button', { name: 'Add band' }).click();
      await expect(card.getByTestId('slab-row')).toHaveCount(2);
      await expect(card.getByTestId('slab-row').nth(1)).toContainText('FLAT');
    });

    await test.step('a band with a negative rate is refused by name and nothing is added', async () => {
      const band = card.getByTestId('add-slab');
      await band.getByLabel('From kg').fill('20000');
      await band.getByLabel('Rate', { exact: true }).fill('-1');
      await band.getByRole('button', { name: 'Add band' }).click();
      await expect(card.getByTestId('slab-refusal').getByTestId('refusal-code')).toHaveText('validation-failed');
      await expect(card.getByTestId('slab-row')).toHaveCount(2);
    });

    await test.step('a fuel surcharge for a month long past, when no trip of any spec runs', async () => {
      const fuel = page.getByTestId('fuel-surcharge');
      const month = `1999-${String(1 + Math.floor(Math.random() * 12)).padStart(2, '0')}`;
      await fuel.getByLabel('Month').fill(month);
      await fuel.getByLabel('Surcharge %').fill('7.5');
      await fuel.getByRole('button', { name: 'Record surcharge' }).click();
      await expect(page.getByTestId('fuel-recorded')).toHaveText(`Surcharge for ${month} recorded.`);
    });
  });

  test('a routing guide is defined, given a vendor, and retired', async ({ page, request }, info) => {
    const salt = tag(info);
    const session = await apiSession(request);
    const { origin, destination } = await commercialLane(request, session, salt);
    const vendor = await commercialVendor(request, session, `ZZ-CM-GV-${salt}`);
    await signIn(page);
    await openCommercial(page);

    const define = page.getByTestId('define-guide');
    await define.getByLabel('Guide origin').fill(origin.id);
    await define.getByLabel('Guide destination').fill(destination.id);
    await define.getByLabel('Guide vehicle type').fill('RIGID');
    await define.getByLabel('Strategy').selectOption('ROUND_ROBIN');
    await define.getByRole('button', { name: 'Define guide' }).click();

    const panel = page.getByTestId('guide-panel');
    await expect(panel.getByRole('heading', { name: `${origin.code} → ${destination.code} · RIGID` })).toBeVisible();
    await expect(panel.getByTestId('guide-state')).toHaveText('Active');
    await expect(panel.getByTestId('no-vendors')).toBeVisible();

    await test.step('a second guide for the same lane and vehicle is refused by name', async () => {
      await define.getByRole('button', { name: 'Define guide' }).click();
      await expect(page.getByTestId('guide-refusal').getByTestId('refusal-code')).not.toBeEmpty();
    });

    await test.step('a vendor ranked first, answering within half an hour', async () => {
      const add = panel.getByTestId('add-vendor');
      await add.getByLabel('Vendor').fill(vendor.id);
      await add.getByLabel('Rank').fill('1');
      await add.getByLabel('Answers within minutes').fill('30');
      await add.getByLabel('Agreed rate').fill('18000');
      await add.getByRole('button', { name: 'Add vendor' }).click();
      await expect(panel.getByTestId('guide-vendor')).toHaveCount(1);
      await expect(panel.getByTestId('guide-vendor')).toContainText(`${vendor.code} Carriers`);
    });

    await test.step('retired, it takes no more vendors', async () => {
      await panel.getByTestId('retire-guide').click();
      await expect(panel.getByTestId('guide-state')).toHaveText('Retired');
      await expect(panel.getByTestId('add-vendor')).toHaveCount(0);
      await expect(panel.getByTestId('retire-guide')).toHaveCount(0);
    });
  });

  test('a planned load is allocated from the desk and the first vendor\'s offer is accepted', async ({ page, request }, info) => {
    const salt = tag(info);
    const lane = await newPlannedLoad(request, await apiSession(request), salt);
    await signIn(page);
    await openCommercial(page);

    const allocate = page.getByTestId('allocate-load');
    await allocate.getByLabel('Load id to allocate').fill(lane.loadId);
    await allocate.getByRole('button', { name: 'Allocate load' }).click();

    const panel = page.getByTestId('allocation-panel');
    await expect(panel.getByTestId('allocation-status')).toHaveText('OFFERED');
    const offer = panel.getByTestId('offer-row');
    await expect(offer).toHaveCount(1);
    await expect(offer.getByTestId('offer-outcome')).toHaveText('PENDING');
    await expect(offer).toContainText(`${lane.vendors[0].code} Carriers`);

    await offer.getByTestId('accept-offer').click();
    await expect(panel.getByTestId('allocation-status')).toHaveText('AWARDED');
    await expect(panel.getByTestId('allocation-awarded')).toHaveText(`${lane.vendors[0].code} Carriers`);
    await expect(offer.getByTestId('offer-outcome')).toHaveText('ACCEPTED');
    await expect(panel.getByTestId('accept-offer')).toHaveCount(0);
    await expect(panel.getByTestId('cancel-allocation')).toHaveCount(0);

    await test.step('the same load cannot be allocated twice, and the refusal says why', async () => {
      await allocate.getByLabel('Load id to allocate').fill(lane.loadId);
      await allocate.getByRole('button', { name: 'Allocate load' }).click();
      await expect(page.getByTestId('allocate-refusal').getByTestId('refusal-code')).not.toBeEmpty();
    });
  });

  test('a routing guide is found by typing part of its lane and opened', async ({ page, request }, info) => {
    const salt = tag(info);
    const session = await apiSession(request);
    const lane = await commercialLane(request, session, salt);
    await commercialGuide(request, session, lane, 'TRAILER');
    await signIn(page);
    await openCommercial(page);

    const open = page.getByTestId('open-guide');
    await open.getByLabel('Guide id', { exact: true }).fill(`cm-orig-${salt}`);
    const option = page.getByRole('option', { name: new RegExp(`${lane.origin.code} → ${lane.destination.code} · TRAILER`, 'i') });
    await expect(option).toHaveCount(1);
    await option.click();
    await open.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('guide-panel')
      .getByRole('heading', { name: `${lane.origin.code} → ${lane.destination.code} · TRAILER` })).toBeVisible();
  });

  test('the load to allocate is picked by typing part of its load number', async ({ page, request }, info) => {
    const salt = tag(info);
    await newPlannedLoad(request, await apiSession(request), salt);
    await signIn(page);
    await openCommercial(page);

    const allocate = page.getByTestId('allocate-load');
    const input = allocate.getByLabel('Load id to allocate', { exact: true });
    await input.fill(`ar-ld-${salt}`);
    const option = page.getByRole('option', { name: new RegExp(`ZZ-AR-LD-${salt}`, 'i') });
    await expect(option).toHaveCount(1);
    await option.click();
    await allocate.getByRole('button', { name: 'Allocate load' }).click();
    await expect(page.getByTestId('allocation-panel').getByTestId('allocation-status')).toHaveText('OFFERED');
  });

  test('a vendor\'s overclaim disputes the bill, the dispute is settled with a reason, and the bill prints', async ({ page, request }, info) => {
    test.setTimeout(120_000);
    const trip = await deliveredTrip(request, await apiSession(request), tag(info));
    await stubPrint(page);
    await signIn(page);
    await openCommercial(page);

    const panel = await openBill(page, trip.billId);
    await expect(panel.getByRole('heading', { name: trip.billNo })).toBeVisible();
    await expect(panel.getByTestId('bill-status')).toHaveText('DRAFT');
    await expect(panel.getByTestId('bill-computed')).toHaveText(amount(20000));
    await expect(panel.getByTestId('bill-line')).toHaveCount(1);
    await expect(panel.getByTestId('bill-line')).toContainText('BASE_FREIGHT');

    await test.step('a claim of 25 000 against 20 000 is disputed', async () => {
      const match = panel.getByTestId('match-bill');
      await match.getByLabel('Vendor\'s claimed amount').fill('25000');
      await match.getByRole('button', { name: 'Match claim' }).click();
      await expect(panel.getByTestId('match-outcome')).toHaveText('Matched: DISPUTED.');
      await expect(panel.getByTestId('bill-status')).toHaveText('DISPUTED');
      await expect(panel.getByTestId('bill-claimed')).toHaveText(amount(25000));
      await expect(panel.getByTestId('bill-variance')).toHaveText(amount(5000));
      await expect(panel.getByTestId('match-bill')).toHaveCount(0);
    });

    await test.step('settled in the vendor\'s favour, on the record', async () => {
      const resolve = panel.getByTestId('resolve-bill');
      await resolve.getByLabel('Reason for settling').fill('Detention agreed with the vendor on a call');
      await resolve.getByRole('button', { name: /Settle in the vendor/ }).click();
      await expect(panel.getByTestId('bill-status')).toHaveText('APPROVED_FOR_PAYMENT');
      await expect(panel.getByTestId('bill-resolution')).toContainText('Detention agreed with the vendor on a call');
      await expect(panel.getByTestId('resolve-bill')).toHaveCount(0);
    });

    await test.step('the bill prints with its lines', async () => {
      await panel.getByTestId('print-bill').click();
      const slip = panel.getByTestId('bill-slip');
      await expect(slip.getByRole('heading', { name: `Freight bill ${trip.billNo}` })).toBeVisible();
      await expect(slip.getByTestId('slip-line')).toHaveCount(1);
      await slip.getByTestId('print-now').click();
      expect(await printed(page)).toBe(1);
    });
  });
});

test.describe('what each person is offered', () => {
  test('without the rights to change them, bills are read and printed but nothing can be published, offered or settled', async ({ page, request }, info) => {
    test.setTimeout(120_000);
    const trip = await deliveredTrip(request, await apiSession(request), tag(info));
    await withoutPermissions(page, ['TARIFF_MANAGE', 'ROUTING_GUIDE_MANAGE', 'LOAD_ALLOCATE', 'INVOICE_APPROVE']);
    await stubPrint(page);
    await signIn(page);
    await openCommercial(page);

    await expect(page.getByTestId('tariffs')).toHaveCount(0);
    await expect(page.getByTestId('guides')).toHaveCount(0);
    await expect(page.getByTestId('allocations')).toBeVisible();
    await expect(page.getByTestId('allocate-load')).toHaveCount(0);

    const panel = await openBill(page, trip.billId);
    await expect(panel.getByTestId('bill-status')).toHaveText('DRAFT');
    await expect(panel.getByTestId('match-bill')).toHaveCount(0);
    await panel.getByTestId('print-bill').click();
    await panel.getByTestId('bill-slip').getByTestId('print-now').click();
    expect(await printed(page)).toBe(1);
  });

  test('without any commercial right, the desk is not in the sidebar', async ({ page }) => {
    await withoutPermissions(page, ['INVOICE_READ', 'TARIFF_MANAGE', 'ROUTING_GUIDE_MANAGE', 'LOAD_ALLOCATE']);
    await signIn(page);

    await expect(page.getByTestId('sidebar')).toBeVisible();
    await expect(page.getByTestId('nav-commercial')).toHaveCount(0);
    await expect(page.getByTestId('nav-client-billing')).toBeVisible();
  });

  test('a bill that does not exist is reported, not shown', async ({ page }) => {
    await signIn(page);
    await openCommercial(page);
    const form = page.getByTestId('open-bill');
    await form.getByLabel('Bill id').fill(crypto.randomUUID());
    await form.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('bill-refusal')).toBeVisible();
    await expect(page.getByTestId('bill-panel')).toHaveCount(0);
  });
});

// Rate cards and rail tariffs

/** Lists one vendor's rate cards and opens the card with the given code. */
async function openRateCard(page: Page, vendorId: string, code: string) {
  const filter = page.getByTestId('rate-card-filter');
  await filter.getByLabel('Vendor filter').fill(vendorId);
  await filter.getByRole('button', { name: 'List' }).click();
  const row = page.getByTestId('rate-card-row').filter({ hasText: code });
  await expect(row).toHaveCount(1);
  await row.click();
  const panel = page.getByTestId('rate-card-panel');
  await expect(panel.getByTestId('rate-card-code')).toHaveText(code);
  return panel;
}

test.describe('rate cards and rail tariffs', () => {
  test('a published rate card is listed and opens with its bands, lightest first', async ({ page, request }, info) => {
    const salt = tag(info);
    const card = await commercialRateCard(request, await apiSession(request), salt);
    await signIn(page);
    await openCommercial(page);

    const panel = await openRateCard(page, card.vendor.id, card.code);
    await expect(page.getByTestId('rate-card-row').filter({ hasText: card.code })).toContainText('2');
    await expect(panel.getByTestId('rate-card-vendor')).toHaveText(`${card.vendor.code} Carriers`);
    await expect(panel.getByTestId('rate-card-state')).toContainText('from ');
    const bands = panel.getByTestId('rate-card-band');
    await expect(bands).toHaveCount(2);
    await expect(bands.nth(0)).toContainText('PER_KG');
    await expect(bands.nth(0).locator('td').first()).toHaveText(amount(0));
    await expect(bands.nth(1)).toContainText('FLAT');
    await expect(bands.nth(1).locator('td').nth(1)).toHaveText('—');

    await test.step('only superseded versions: this one is still in force', async () => {
      const filter = page.getByTestId('rate-card-filter');
      await filter.getByLabel('Versions').selectOption('superseded');
      await filter.getByRole('button', { name: 'List' }).click();
      await expect(page.getByTestId('no-rate-cards')).toBeVisible();
    });
  });

  test('more rate cards than a page are reached with Load more', async ({ page, request }, info) => {
    test.setTimeout(120_000);
    const vendor = await commercialRateCards(request, await apiSession(request), tag(info), 52);
    await signIn(page);
    await openCommercial(page);

    const filter = page.getByTestId('rate-card-filter');
    await filter.getByLabel('Vendor filter').fill(vendor.id);
    await filter.getByRole('button', { name: 'List' }).click();
    await expect(page.getByTestId('rate-card-row')).toHaveCount(50);
    await page.getByTestId('rate-cards-more').click();
    await expect(page.getByTestId('rate-card-row')).toHaveCount(52);
    await expect(page.getByTestId('rate-cards-more')).toHaveCount(0);
  });

  test('a rate card that does not exist is reported by its code', async ({ page }) => {
    await signIn(page);
    await openCommercial(page);
    const form = page.getByTestId('open-rate-card');
    await form.getByLabel('Rate card id').fill(crypto.randomUUID());
    await form.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('rate-card-refusal').getByTestId('refusal-code')).toHaveText('not-found');
    await expect(page.getByTestId('rate-card-panel')).toHaveCount(0);
  });

  test('a rail rate card is published between two railheads and read back', async ({ page, request }, info) => {
    const salt = tag(info);
    const { from, to, carrier } = await commercialRailheads(request, await apiSession(request), salt);
    await signIn(page);
    await openCommercial(page);

    const form = page.getByTestId('publish-rail-tariff');
    await form.getByLabel('Code').fill(`ZZ-CM-RAILCARD-${salt}`);
    await form.getByLabel('Rail carrier').fill(carrier.id);
    await form.getByLabel('Origin railhead').fill(from.id);
    await form.getByLabel('Destination railhead').fill(to.id);
    await form.getByLabel('Per wagon').fill('18000');
    await form.getByLabel('Minimum charge').fill('25000');
    await form.getByRole('button', { name: 'Publish rail rate card' }).click();

    await expect(page.getByTestId('rail-tariff-published')).toContainText(`ZZ-CM-RAILCARD-${salt}`);
    const panel = page.getByTestId('rail-tariff-panel');
    await expect(panel.getByTestId('rail-tariff-code')).toHaveText(`ZZ-CM-RAILCARD-${salt}`);
    await expect(panel.getByTestId('rail-per-wagon')).toHaveText(amount(18000));
    await expect(panel.getByTestId('rail-per-container')).toHaveText('—');
    await expect(page.getByTestId('rail-tariff-row').filter({ hasText: `ZZ-CM-RAILCARD-${salt}` })).toHaveCount(1);

    await test.step('the same pair from a non-railhead is refused by its code', async () => {
      const lane = await commercialLane(request, await apiSession(request), `RX-${salt}`);
      await form.getByLabel('Code').fill(`ZZ-CM-RAILBAD-${salt}`);
      await form.getByLabel('Rail carrier').fill(carrier.id);
      await form.getByLabel('Origin railhead').fill(lane.origin.id);
      await form.getByLabel('Destination railhead').fill(to.id);
      await form.getByLabel('Per wagon').fill('18000');
      await form.getByRole('button', { name: 'Publish rail rate card' }).click();
      await expect(page.getByTestId('rail-tariff-refusal').getByTestId('refusal-code'))
        .toHaveText('rail-tariff-not-railhead');
    });
  });

  test('without the right to manage tariffs, cards are read but nothing can be published', async ({ page, request }, info) => {
    const salt = tag(info);
    const card = await commercialRateCard(request, await apiSession(request), salt);
    await withoutPermissions(page, ['TARIFF_MANAGE']);
    await signIn(page);
    await openCommercial(page);

    await expect(page.getByTestId('publish-tariff')).toHaveCount(0);
    await expect(page.getByTestId('publish-rail-tariff')).toHaveCount(0);
    await expect(page.getByTestId('rail-tariffs')).toBeVisible();
    const panel = await openRateCard(page, card.vendor.id, card.code);
    await expect(panel.getByTestId('rate-card-band')).toHaveCount(2);
  });
});
