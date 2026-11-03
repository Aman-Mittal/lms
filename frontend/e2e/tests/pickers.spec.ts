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
import {
  apiSession, newConsignment, newDeskOrder, newPlannedLoad, offerLoad, openScreen, signIn, stubTiles, tag, trackingRefOf,
} from './support';
import { newVehicle } from './support-admin';
import { newTicket, newTrip } from './support-control';
import { newFloor } from './support-warehouse';

/**
 * Types part of a name into a picker and chooses the suggestion that matches,
 * as a person would; resolves once the choice is shown in the input.
 */
async function pick(page: Page, scope: Locator, label: string, typed: string, choice: RegExp): Promise<void> {
  const input = scope.getByLabel(label, { exact: true });
  await input.fill(typed);
  const option = page.getByRole('option', { name: choice });
  await expect(option).toHaveCount(1);
  await option.click();
  await expect(input).toHaveValue(choice);
}

test.describe('pickers', () => {
  test('an order is raised by choosing its customer and origin from what is typed', async ({ page, request }, info) => {
    const salt = tag(info);
    const order = await newDeskOrder(request, await apiSession(request), salt, 'NONE');
    await signIn(page);
    await openScreen(page, 'nav-order-desk', 'Order desk');

    const raise = page.getByTestId('raise-order');
    await raise.getByLabel('Order number').fill(order.orderNo);
    await pick(page, raise, 'New order customer id', `customer one ${salt}`, new RegExp(`Desk customer one ${salt}`, 'i'));
    await pick(page, raise, 'New order origin terminal id', `desk orig ${salt}`, new RegExp(`Desk ORIG ${salt}`, 'i'));
    await raise.getByRole('button', { name: 'Raise order' }).click();

    await expect(page.getByTestId('order-panel').getByRole('heading', { name: order.orderNo })).toBeVisible();
    await expect(page.getByTestId('order-customer')).toHaveText(order.customers[0].name);
  });

  test('text that names nothing is not sent, and a pasted id is taken as it is', async ({ page, request }, info) => {
    const salt = tag(info);
    const order = await newDeskOrder(request, await apiSession(request), salt, 'NONE');
    await signIn(page);
    await openScreen(page, 'nav-order-desk', 'Order desk');

    const raise = page.getByTestId('raise-order');
    await raise.getByLabel('Order number').fill(order.orderNo);
    await raise.getByLabel('New order customer id', { exact: true }).fill(`no such customer ${salt}`);
    await expect(page.getByRole('option', { name: /./ })).toHaveCount(0);
    await raise.getByLabel('New order origin terminal id', { exact: true }).fill(order.originId);
    await raise.getByRole('button', { name: 'Raise order' }).click();
    await expect(page.getByTestId('order-panel')).toHaveCount(0);

    await raise.getByLabel('New order customer id', { exact: true }).fill(order.customers[1].id);
    await raise.getByRole('button', { name: 'Raise order' }).click();
    await expect(page.getByTestId('order-customer')).toHaveText(order.customers[1].name);
  });

  test('a vehicle is found by part of its registration to read its documents', async ({ page, request }, info) => {
    const vehicle = await newVehicle(request, await apiSession(request), tag(info));
    await signIn(page);
    await openScreen(page, 'nav-admin-compliance', 'Compliance');

    const panel = page.getByTestId('owner-documents-panel');
    await panel.getByTestId('owner-type').selectOption('VEHICLE');
    await pick(page, panel, 'Owner id', vehicle.registrationNo.toLowerCase().slice(3),
      new RegExp(vehicle.registrationNo, 'i'));
    await panel.getByTestId('open-owner').click();
    await expect(panel.getByTestId('no-documents')).toBeVisible();
  });

  test('a warehouse and a SKU are found by code, whatever else the tenant holds', async ({ page, request }, info) => {
    const floor = await newFloor(request, await apiSession(request));
    await signIn(page);
    await openScreen(page, 'nav-warehouse', 'Warehouse operations');

    const opener = page.getByTestId('open-warehouse');
    await pick(page, opener, 'Warehouse id', floor.code.toLowerCase(), new RegExp(floor.code, 'i'));
    await opener.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('warehouse-desk')).toBeVisible();

    const form = page.getByTestId('create-asn');
    await form.getByLabel('ASN number').fill(`E2E-ASN-${tag(info)}`);
    await pick(page, form, 'Line 1 SKU', floor.skuCode.toLowerCase(), new RegExp(floor.skuCode, 'i'));
    await form.getByLabel('Line 1 quantity').fill('5');
    await form.getByRole('button', { name: 'Create ASN' }).click();
    await expect(page.getByTestId('asn-status')).toHaveText('EXPECTED');

    const put = page.getByTestId('putaway');
    await pick(page, put, 'Bin', floor.binCode.toLowerCase(), new RegExp(`^${floor.binCode}$`, 'i'));
  });
});

test.describe('pickers for what has moved', () => {
  test('an order and a load are opened by their numbers', async ({ page, request }, info) => {
    const salt = tag(info);
    const session = await apiSession(request);
    const order = await newDeskOrder(request, session, salt, 'DRAFT');
    const lane = await newPlannedLoad(request, session, salt);
    await signIn(page);

    await openScreen(page, 'nav-order-desk', 'Order desk');
    const opener = page.getByTestId('open-order');
    await pick(page, opener, 'Order id', order.orderNo.toLowerCase(), new RegExp(order.orderNo, 'i'));
    await opener.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('order-panel').getByRole('heading', { name: order.orderNo })).toBeVisible();

    await page.getByTestId('nav-load-building').click();
    const load = page.getByTestId('open-load');
    await pick(page, load, 'Load id', `ar-ld-${salt}`, new RegExp(`ZZ-AR-LD-${salt}`, 'i'));
    await load.getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('load-panel')).toBeVisible();
    expect(lane.loadId).toBeTruthy();
  });

  test('a consignment is found by part of its reference on the delivery, claims and tracking desks', async ({ page, request }) => {
    const session = await apiSession(request);
    const consignment = await newConsignment(request, session);
    const ref = await trackingRefOf(request, session, consignment);
    const tail = ref.slice(-6).toLowerCase();
    const shown = new RegExp(ref, 'i');
    await signIn(page);

    await page.getByTestId('nav-delivery').click();
    await expect(page.getByRole('heading', { name: 'Delivery desk', level: 1 })).toBeVisible();
    await pick(page, page.locator('main'), 'Consignment id', tail, shown);
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('no-proof')).toBeVisible();

    await page.getByTestId('nav-tracking').click();
    await pick(page, page.locator('main'), 'Consignment id', tail, shown);
    await page.getByRole('button', { name: 'Look up' }).click();
    await expect(page.getByTestId('links-panel')).toBeVisible();

    await page.getByTestId('nav-claims').click();
    await pick(page, page.locator('main'), 'Filter by consignment', tail, shown);
    await page.getByRole('button', { name: 'Filter' }).click();
    await expect(page.getByText('No claims against that consignment.')).toBeVisible();
  });

  test('a ticket and a trip are opened on the exception desk by their numbers', async ({ page, request }, info) => {
    const session = await apiSession(request);
    const trip = await newTrip(request, session, tag(info));
    const ticket = await newTicket(request, session, trip.tripId);
    await stubTiles(page);
    await signIn(page);
    await openScreen(page, 'nav-exception-desk', 'Exception desk');

    await pick(page, page.getByTestId('open-ticket'), 'Ticket id', ticket.ticketNo.toLowerCase(),
      new RegExp(ticket.ticketNo, 'i'));
    await page.getByTestId('open-ticket').getByRole('button', { name: 'Open' }).click();
    await expect(page.getByTestId('ticket-panel')).toContainText(ticket.ticketNo);

    const desk = page.getByTestId('trip-desk');
    await pick(page, desk, 'Trip id to watch', trip.tripNo.toLowerCase(), new RegExp(trip.tripNo, 'i'));
    await desk.getByRole('button', { name: 'Open trip' }).click();
    await expect(desk.getByTestId('trip-panel')).toBeVisible();
  });

  test('an allocation is looked up by its load number', async ({ page, request }, info) => {
    const salt = tag(info);
    const session = await apiSession(request);
    const lane = await newPlannedLoad(request, session, salt);
    await offerLoad(request, session, lane.loadId);
    await signIn(page);
    await openScreen(page, 'nav-allocation-rules', 'Allocation rules');

    await pick(page, page.locator('main'), 'Allocation id', `ar-ld-${salt}`, new RegExp(`ZZ-AR-LD-${salt}`, 'i'));
    await page.getByTestId('allocation-go').click();
    await expect(page.getByTestId('allocation-status')).not.toBeEmpty();
  });
});
