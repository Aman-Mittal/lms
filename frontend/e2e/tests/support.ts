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

import { expect, type APIRequestContext, type Page, type TestInfo } from '@playwright/test';

export const DEMO_EMAIL = 'admin@demo.local';

function password(): string {
  const value = process.env.E2E_DEMO_PASSWORD;
  if (!value) {
    throw new Error('Set E2E_DEMO_PASSWORD to the demo administrator password');
  }
  return value;
}

/** Signs in as the demo administrator and waits for the control tower. */
export async function signIn(page: Page): Promise<void> {
  await page.goto('./');
  await page.getByLabel('Password').fill(password());
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.getByRole('heading', { name: 'Control tower', level: 1 })).toBeVisible();
  await expect(page.getByTestId('tiles')).toBeVisible();
}

export async function signOut(page: Page): Promise<void> {
  await page.getByTestId('sign-out').click();
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
}

/**
 * A short string that is different on every run and for each console, so
 * data from an earlier run or from the other console never collides. Lower
 * case and digits only, because it is also used inside a custom-field key.
 */
export function tag(info: TestInfo): string {
  return `${info.project.name.slice(0, 2)}${Date.now().toString(36)}`;
}

export async function openPartners(page: Page): Promise<void> {
  await page.getByTestId('nav-partners').click();
  await expect(page.getByRole('heading', { name: 'Partners', level: 1 })).toBeVisible();
  await expect(page.getByText('Loading…')).toHaveCount(0);
}

export async function registerPartner(page: Page, code: string): Promise<void> {
  await openPartners(page);
  const form = page.getByTestId('register-partner');
  await form.getByPlaceholder('Code').fill(code);
  await form.getByPlaceholder('Legal name').fill(`${code} Pvt Ltd`);
  await form.getByRole('button', { name: 'Register' }).click();
  await expect(page.getByTestId('row').filter({ hasText: code })).toBeVisible();
}

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

/**
 * Retires custom fields straight through the API, whatever state the page is
 * in. A test that defines a required field must call this from a `finally`:
 * the demo tenant is shared, and a required field left behind by a failed run
 * would make every later write to that kind of record fail for want of a value.
 */
export async function retireFields(
  request: APIRequestContext, kind: string, keys: string[],
): Promise<void> {
  const login = await request.post(`${API}/api/v1/auth/login`, {
    data: { tenantCode: 'demo', email: DEMO_EMAIL, password: password() },
  });
  const headers = { Authorization: `Bearer ${(await login.json()).accessToken}` };
  const listed = await request.get(`${API}/api/v1/custom-fields/definitions?entityType=${kind}`, { headers });
  for (const d of (await listed.json()).items as Array<{
    id: string; key: string; label: string; options: string[]; active: boolean;
  }>) {
    if (keys.includes(d.key) && d.active) {
      await request.put(`${API}/api/v1/custom-fields/definitions/${d.id}`, {
        headers,
        data: { label: d.label, required: false, options: d.options, active: false },
      });
    }
  }
}

// ------------------------------------------------------------ data made through the API

export interface Session {
  token: string;
  orgUnitId: string;
}

/** Signs in to the API as the demo administrator, for specs that build their own data. */
export async function apiSession(request: APIRequestContext): Promise<Session> {
  const login = await request.post(`${API}/api/v1/auth/login`, {
    data: { tenantCode: 'demo', email: DEMO_EMAIL, password: password() },
  });
  expect(login.ok(), 'API sign-in').toBeTruthy();
  const body = await login.json();
  return { token: body.accessToken, orgUnitId: body.orgUnitId };
}

async function post(request: APIRequestContext, session: Session, path: string, data: unknown) {
  const response = await request.post(`${API}${path}`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data,
  });
  expect(response.ok(), `POST ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return response.status() === 204 ? null : response.json();
}

function uuid(): string {
  return crypto.randomUUID();
}

function between(low: number, high: number): number {
  return Number((low + Math.random() * (high - low)).toFixed(6));
}

/**
 * The partners it makes are coded to sort last: lists are ordered by code and show
 * one page, so partners that sorted early would push a later spec's own partner
 * off the first page.
 *
 * Builds a consignment the way a planner would -- a customer, a consignee, two
 * sites, an order with one line, validated and split into consignments -- and
 * returns its id. Nothing is mocked and nothing needs a database, so it runs
 * the same against CI and a local stack. The consignment has not left, which
 * is exactly what the delivery desk should refuse to prove.
 */
export async function newConsignment(request: APIRequestContext, session: Session): Promise<string> {
  const org = session.orgUnitId;
  const customer = await post(request, session, '/api/v1/partners', {
    orgUnitId: org, code: `ZZ-E2E-C-${uuid()}`, legalName: 'E2E customer', partnerType: 'CUSTOMER',
    taxId: `TAX-${uuid()}`,
  });
  const consignee = await post(request, session, '/api/v1/partners', {
    orgUnitId: org, code: `ZZ-E2E-N-${uuid()}`, legalName: 'E2E consignee', partnerType: 'CONSIGNEE',
    taxId: `TAX-${uuid()}`,
  });
  const site = (name: string) => ({
    orgUnitId: org, code: `E2E-${name}-${uuid()}`, name: `E2E ${name}`, category: 'CUSTOMER_SITE',
    centre: { lat: between(24, 60), lon: between(10, 170) }, radiusMetres: 500, dockCount: 2,
    avgDwellMinutes: 45,
  });
  const origin = await post(request, session, '/api/v1/terminals/point-radius', site('ORIG'));
  const destination = await post(request, session, '/api/v1/terminals/point-radius', site('DEST'));
  const order = await post(request, session, '/api/v1/orders', {
    orgUnitId: org, customerPartnerId: customer.id, orderNo: `E2E-SO-${uuid()}`,
    originTerminalId: origin.id,
    requestedPickupAt: new Date(Date.now() + 4 * 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z'),
    source: 'API',
  });
  await post(request, session, `/api/v1/orders/${order.id}/lines`, {
    lineNo: 1, materialCode: 'GOODS', materialDescription: 'General goods', materialClass: 'GENERAL',
    quantity: 10, uom: 'CASE', deadWeightKg: 500, consigneePartnerId: consignee.id,
    destinationTerminalId: destination.id,
  });
  await post(request, session, `/api/v1/orders/${order.id}/validate`, {});
  const consignments = await post(request, session, `/api/v1/orders/${order.id}/consignments`, {});
  return consignments[0].id as string;
}

/** Raises a claim through the API and returns its id and number. */
export async function newClaim(
  request: APIRequestContext, session: Session, consignmentId: string, claimType = 'LOSS',
): Promise<{ id: string; claimNo: string }> {
  const made = await post(request, session, '/api/v1/claims', {
    consignmentId, claimType, amount: 400.5, description: 'Raised by the browser suite',
  });
  const read = await request.get(`${API}/api/v1/claims/${made.id}`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  return { id: made.id, claimNo: (await read.json()).claimNo };
}

export async function openClaims(page: Page): Promise<void> {
  await page.getByTestId('nav-claims').click();
  await expect(page.getByRole('heading', { name: 'Claims', level: 1 })).toBeVisible();
  await expect(page.getByText('Loading…')).toHaveCount(0);
}

/**
 * Takes permissions out of the sign-in answer, before the page sees it, so a
 * screen can be looked at as a person with fewer rights would see it. Only the
 * console is told; the server still knows the real token, which is the point:
 * this checks what the screen offers, and the backend's own tests check what it allows.
 */
export async function withoutPermissions(page: Page, dropped: string[]): Promise<void> {
  await page.route('**/api/v1/auth/login', async (route) => {
    const response = await route.fetch();
    const body = await response.json();
    body.permissions = (body.permissions as string[]).filter((p) => !dropped.includes(p));
    await route.fulfill({ response, json: body });
  });
}

/** Replaces window.print with a counter, so a spec can press Print without a dialog. */
export async function stubPrint(page: Page): Promise<void> {
  await page.addInitScript(() => {
    (window as unknown as { printed: number }).printed = 0;
    window.print = () => { (window as unknown as { printed: number }).printed += 1; };
  });
}

export async function printed(page: Page): Promise<number> {
  return page.evaluate(() => (window as unknown as { printed: number }).printed);
}

// ------------------------------------------------------------ allocation rules

export interface Lane {
  loadId: string;
  origin: { id: string; code: string };
  destination: { id: string; code: string };
  /** Active vendors on the lane's routing guide, in rank order 1, 2, 3. */
  vendors: Array<{ id: string; code: string }>;
}

async function activeVendor(request: APIRequestContext, session: Session, code: string): Promise<string> {
  const vendor = await post(request, session, '/api/v1/partners', {
    orgUnitId: session.orgUnitId, code, legalName: `${code} Carriers`, partnerType: 'VENDOR', taxId: `TAX-${uuid()}`,
  });
  await post(request, session, `/api/v1/partners/${vendor.id}/transitions`, { target: 'PENDING_VERIFICATION' });
  await post(request, session, `/api/v1/partners/${vendor.id}/transitions`, { target: 'ACTIVE' });
  return vendor.id as string;
}

/**
 * A lane with a routing guide of three active vendors and one load planned on it,
 * built as a planner would: sites, a lorry with its insurance, the guide, an
 * order split into a consignment, a load opened on the lorry and planned. Its
 * codes start ZZ so they sort after every other spec's data.
 */
export async function newPlannedLoad(request: APIRequestContext, session: Session, salt: string): Promise<Lane> {
  const org = session.orgUnitId;
  const site = async (name: string) => {
    const code = `ZZ-AR-${name}-${salt}`;
    const made = await post(request, session, '/api/v1/terminals/point-radius', {
      orgUnitId: org, code, name: `Rules ${name} ${salt}`, category: 'CUSTOMER_SITE',
      centre: { lat: between(24, 60), lon: between(10, 170) }, radiusMetres: 500, dockCount: 2, avgDwellMinutes: 45,
    });
    return { id: made.id as string, code };
  };
  const origin = await site('ORIG');
  const destination = await site('DEST');

  const vehicle = await post(request, session, '/api/v1/vehicles', {
    orgUnitId: org, registrationNo: `MH-AR-${salt.toUpperCase()}`, category: 'TRUCK', vehicleType: 'RIGID',
    axleConfig: '2-AXLE', grossWeightKg: 26000, tareWeightKg: 6000, maxVolumeM3: 60,
    hazmatCertified: false, reeferCapable: false,
  });
  const day = (years: number) => new Date(Date.now() + years * 365 * 86_400_000).toISOString().slice(0, 10);
  await post(request, session, `/api/v1/vehicles/${vehicle.id}/documents`, {
    documentType: 'INSURANCE', documentNo: `INS-AR-${salt}`, issuingAuthority: 'IRDAI',
    issuedOn: day(-1), expiresOn: day(1),
  });

  const guide = await post(request, session, '/api/v1/routing-guides', {
    orgUnitId: org, originTerminalId: origin.id, destinationTerminalId: destination.id, vehicleType: 'RIGID',
  });
  const vendors: Array<{ id: string; code: string }> = [];
  for (const [rank, label] of ['A', 'B', 'C'].entries()) {
    const code = `ZZ-AR-V${label}-${salt}`;
    const id = await activeVendor(request, session, code);
    await post(request, session, `/api/v1/routing-guides/${guide.id}/vendors`, {
      vendorPartnerId: id, rankNo: rank + 1, responseSlaMinutes: 60, agreedRate: 20000,
    });
    vendors.push({ id, code });
  }

  const customer = await post(request, session, '/api/v1/partners', {
    orgUnitId: org, code: `ZZ-AR-C-${salt}`, legalName: 'Rules customer', partnerType: 'CUSTOMER', taxId: `TAX-${uuid()}`,
  });
  const consignee = await post(request, session, '/api/v1/partners', {
    orgUnitId: org, code: `ZZ-AR-N-${salt}`, legalName: 'Rules consignee', partnerType: 'CONSIGNEE', taxId: `TAX-${uuid()}`,
  });
  const order = await post(request, session, '/api/v1/orders', {
    orgUnitId: org, customerPartnerId: customer.id, orderNo: `ZZ-AR-SO-${salt}`, originTerminalId: origin.id,
    requestedPickupAt: new Date(Date.now() + 4 * 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z'), source: 'API',
  });
  await post(request, session, `/api/v1/orders/${order.id}/lines`, {
    lineNo: 1, materialCode: 'GOODS', materialDescription: 'General goods', materialClass: 'GENERAL',
    quantity: 10, uom: 'CASE', deadWeightKg: 5000, consigneePartnerId: consignee.id,
    destinationTerminalId: destination.id,
  });
  await post(request, session, `/api/v1/orders/${order.id}/validate`, {});
  const consignments = await post(request, session, `/api/v1/orders/${order.id}/consignments`, {});
  const load = await post(request, session, '/api/v1/loads', {
    orgUnitId: org, loadNo: `ZZ-AR-LD-${salt}`, originTerminalId: origin.id, vehicleId: vehicle.id,
  });
  await post(request, session, `/api/v1/loads/${load.id}/consignments`, { consignmentId: consignments[0].id });
  await post(request, session, `/api/v1/loads/${load.id}/plan`, {});
  return { loadId: load.id as string, origin, destination, vendors };
}

/** Offers a load for sourcing, which is when a rule fires; returns the allocation's id. */
export async function offerLoad(request: APIRequestContext, session: Session, loadId: string): Promise<string> {
  return (await post(request, session, '/api/v1/allocations', { loadId })).id as string;
}

/**
 * Retires every active rule whose name contains `salt`. The demo tenant is
 * shared, and a rule left active by a failed run would decide later offers.
 */
export async function retireRules(request: APIRequestContext, salt: string): Promise<void> {
  const session = await apiSession(request);
  const headers = { Authorization: `Bearer ${session.token}` };
  const listed = await request.get(`${API}/api/v1/allocation-rules?active=true&limit=200`, { headers });
  for (const r of (await listed.json()).items as Array<{
    id: string; name: string; priority: number; originTerminalId?: string; destinationTerminalId?: string;
    minWeightKg?: number; maxWeightKg?: number; materialClass?: string; action: string;
    vendors: Array<{ vendorPartnerId: string }>;
  }>) {
    if (r.name.includes(salt)) {
      await request.put(`${API}/api/v1/allocation-rules/${r.id}`, {
        headers,
        data: {
          name: r.name, priority: r.priority, active: false, originTerminalId: r.originTerminalId,
          destinationTerminalId: r.destinationTerminalId, minWeightKg: r.minWeightKg, maxWeightKg: r.maxWeightKg,
          materialClass: r.materialClass, action: r.action, vendorPartnerIds: r.vendors.map((v) => v.vendorPartnerId),
        },
      });
    }
  }
}

export async function openAllocationRules(page: Page): Promise<void> {
  await page.getByTestId('nav-allocation-rules').click();
  await expect(page.getByRole('heading', { name: 'Allocation rules', level: 1 })).toBeVisible();
  await expect(page.getByText('Loading…')).toHaveCount(0);
}

// ----------------------------------------------------------------------- maps

/** A transparent 1x1 PNG, served in place of every map tile. */
const BLANK_TILE = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

/**
 * Answers every map tile request from the test itself. The public tile servers
 * allow light use only, and a test must not depend on them; the count says
 * whether the map asked for tiles at all, which is how a spec knows it drew.
 */
export async function stubTiles(page: Page): Promise<{ count: () => number }> {
  let count = 0;
  await page.route('https://tile.openstreetmap.org/**', async (route) => {
    count += 1;
    await route.fulfill({ status: 200, contentType: 'image/png', body: BLANK_TILE });
  });
  return { count: () => count };
}

export async function openScreen(page: Page, nav: string, heading: string): Promise<void> {
  await page.getByTestId(nav).click();
  await expect(page.getByRole('heading', { name: heading, exact: true, level: 1 })).toBeVisible();
  await expect(page.getByText('Loading…')).toHaveCount(0);
}

export interface Site {
  id: string;
  code: string;
  lat: number;
  lon: number;
}

async function siteAt(
  request: APIRequestContext, session: Session, code: string, category: string, lat: number, lon: number,
): Promise<Site> {
  const made = await post(request, session, '/api/v1/terminals/point-radius', {
    orgUnitId: session.orgUnitId, code, name: `Map ${code}`, category,
    centre: { lat, lon }, radiusMetres: 750, dockCount: 2, avgDwellMinutes: 45,
  });
  return { id: made.id as string, code, lat, lon };
}

/** A railhead-to-railhead itinerary of one rail leg, planned for tomorrow. Codes start ZZ to sort last. */
export async function newRailItinerary(
  request: APIRequestContext, session: Session, salt: string,
): Promise<{ id: string; itineraryNo: string; shipmentRef: string; from: Site; to: Site }> {
  const from = await siteAt(request, session, `ZZ-MP-RH1-${salt}`, 'RAILHEAD', between(20, 24), between(72, 76));
  const to = await siteAt(request, session, `ZZ-MP-RH2-${salt}`, 'RAILHEAD', between(26, 30), between(77, 81));
  const carrier = await activeVendor(request, session, `ZZ-MP-RAIL-${salt}`);
  const at = (hours: number) => new Date(Date.now() + hours * 3600_000).toISOString().replace(/\.\d{3}Z$/, 'Z');
  const shipmentRef = `ZZ-MP-SHIP-${salt}`;
  const made = await post(request, session, '/api/v1/itineraries', {
    shipmentRef, currency: 'INR',
    legs: [{
      mode: 'RAIL', fromTerminalId: from.id, toTerminalId: to.id, plannedDepartureAt: at(24), plannedArrivalAt: at(48),
      bookingRef: `BK-${salt}`, unitType: 'CONTAINER', unitCount: 2, carrierPartnerId: carrier, costAmount: 42000,
    }],
  });
  const read = await request.get(`${API}/api/v1/itineraries/${made.id}`, {
    headers: { Authorization: `Bearer ${session.token}` },
  });
  return { id: made.id as string, itineraryNo: (await read.json()).itineraryNo as string, shipmentRef, from, to };
}

/** A milk-run route from a plant round two suppliers, in force from tomorrow. */
export async function newMilkRunRoute(
  request: APIRequestContext, session: Session, salt: string,
): Promise<{ id: string; code: string; plant: Site; stops: Site[] }> {
  const plant = await siteAt(request, session, `ZZ-MP-PLANT-${salt}`, 'PLANT', between(18, 19), between(73, 74));
  const stops = [
    await siteAt(request, session, `ZZ-MP-S1-${salt}`, 'CUSTOMER_SITE', between(19, 19.5), between(73, 74)),
    await siteAt(request, session, `ZZ-MP-S2-${salt}`, 'CUSTOMER_SITE', between(19.5, 20), between(73, 74)),
  ];
  const code = `ZZ-MP-MR-${salt}`;
  const made = await post(request, session, '/api/v1/milk-run-routes', {
    code, name: `Map circuit ${salt}`, plantTerminalId: plant.id,
    effectiveFrom: new Date(Date.now() + 86_400_000).toISOString().slice(0, 10),
    days: ['MON', 'WED', 'FRI'], departureTime: '06:00', timeZone: 'Asia/Kolkata', runRate: 9000, perStopFee: 500,
    currency: 'INR',
    stops: stops.map((s, i) => ({
      terminalId: s.id, windowOpenMin: 30 + i * 60, windowCloseMin: 90 + i * 60, plannedDwellMin: 20,
    })),
  });
  return { id: made.id as string, code, plant, stops };
}

/** A vehicle that has reported one position, which the network map should show. */
export async function newReportingVehicle(
  request: APIRequestContext, session: Session, salt: string,
): Promise<{ id: string; registrationNo: string; lat: number; lon: number }> {
  const registrationNo = `MH-MP-${salt.toUpperCase()}`;
  const vehicle = await post(request, session, '/api/v1/vehicles', {
    orgUnitId: session.orgUnitId, registrationNo, category: 'TRUCK', vehicleType: 'RIGID',
    axleConfig: '2-AXLE', grossWeightKg: 26000, tareWeightKg: 6000, maxVolumeM3: 60,
    hazmatCertified: false, reeferCapable: false,
  });
  const lat = between(12, 13);
  const lon = between(77, 78);
  await post(request, session, '/api/v1/telematics/pings', {
    pings: [{ vehicleId: vehicle.id, recordedAt: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'), lat, lon, speedKph: 40 }],
  });
  return { id: vehicle.id as string, registrationNo, lat, lon };
}

// ------------------------------------------------------------- LTL rating

/** A site of the given kind at a random point, coded to sort last. */
export async function newSite(
  request: APIRequestContext, session: Session, code: string, category = 'HUB',
): Promise<Site> {
  return siteAt(request, session, code, category, between(10, 30), between(70, 90));
}
