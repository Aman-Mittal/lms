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

import { expect, type APIRequestContext } from '@playwright/test';
import type { Session } from './support';

/*
 * Data for the commercial desk's specs, made through the API the way a planner
 * and a settlement clerk would. Codes start ZZ-CM so they sort after every other
 * spec's data on the shared demo tenant.
 */

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

async function call(request: APIRequestContext, session: Session, path: string, data: unknown) {
  const response = await request.post(`${API}${path}`, {
    headers: { Authorization: `Bearer ${session.token}` },
    data,
  });
  expect(response.ok(), `POST ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return response.status() === 204 ? null : response.json();
}

function between(low: number, high: number): number {
  return Number((low + Math.random() * (high - low)).toFixed(6));
}

/** An instant `minutes` from now, as the API takes one. */
function at(minutes: number): string {
  return new Date(Date.now() + minutes * 60_000).toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** A `YYYY-MM-DD` day `days` from today. */
function day(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}

export interface Place {
  id: string;
  code: string;
}

/** A vendor through verification to active, so it can be named on a guide or a rate card. */
export async function commercialVendor(request: APIRequestContext, session: Session, code: string): Promise<Place> {
  const vendor = await call(request, session, '/api/v1/partners', {
    orgUnitId: session.orgUnitId, code, legalName: `${code} Carriers`, partnerType: 'VENDOR',
    taxId: `TAX-${crypto.randomUUID()}`,
  });
  await call(request, session, `/api/v1/partners/${vendor.id}/transitions`, { target: 'PENDING_VERIFICATION' });
  await call(request, session, `/api/v1/partners/${vendor.id}/transitions`, { target: 'ACTIVE' });
  return { id: vendor.id as string, code };
}

/** Two customer sites far from each other's geofences: a lane nobody else uses. */
export async function commercialLane(
  request: APIRequestContext, session: Session, salt: string,
): Promise<{ origin: Place; destination: Place }> {
  const site = async (name: string): Promise<Place> => {
    const code = `ZZ-CM-${name}-${salt}`;
    const made = await call(request, session, '/api/v1/terminals/point-radius', {
      orgUnitId: session.orgUnitId, code, name: `Commercial ${name} ${salt}`, category: 'CUSTOMER_SITE',
      centre: { lat: between(24, 60), lon: between(10, 170) }, radiusMetres: 500, dockCount: 2, avgDwellMinutes: 45,
    });
    return { id: made.id as string, code };
  };
  return { origin: await site('ORIG'), destination: await site('DEST') };
}

/** A routing guide for the lane and vehicle type, defined through the API; returns its id. */
export async function commercialGuide(
  request: APIRequestContext, session: Session, lane: { origin: Place; destination: Place }, vehicleType: string,
): Promise<string> {
  const made = await call(request, session, '/api/v1/routing-guides', {
    orgUnitId: session.orgUnitId, originTerminalId: lane.origin.id, destinationTerminalId: lane.destination.id,
    vehicleType,
  });
  return made.id as string;
}

export interface DeliveredTrip {
  tripNo: string;
  billId: string;
  billNo: string;
}

/**
 * A 5 000 kg trip on its own lane, priced by a rate card of 4 a kilogram, run and
 * delivered within the last two hours -- so its bill is a draft of 20 000 with one
 * base freight line. Completion raises the bill after the trip's transaction, so
 * this waits for it.
 */
export async function deliveredTrip(request: APIRequestContext, session: Session, salt: string): Promise<DeliveredTrip> {
  const org = session.orgUnitId;
  const { origin, destination } = await commercialLane(request, session, salt);
  const vendor = await commercialVendor(request, session, `ZZ-CM-V-${salt}`);

  const tariff = await call(request, session, '/api/v1/tariffs', {
    orgUnitId: org, code: `ZZ-CM-RC-${salt}`, vendorPartnerId: vendor.id, originTerminalId: origin.id,
    destinationTerminalId: destination.id, vehicleType: 'RIGID', currency: 'INR', effectiveFrom: day(-30),
    detentionFreeHours: 2, detentionHourlyRate: 0, additionalDropFee: 0, minimumCharge: 0,
  });
  await call(request, session, `/api/v1/tariffs/${tariff.id}/slabs`, { minWeightKg: 0, rateBasis: 'PER_KG', rateAmount: 4 });

  const guide = await call(request, session, '/api/v1/routing-guides', {
    orgUnitId: org, originTerminalId: origin.id, destinationTerminalId: destination.id, vehicleType: 'RIGID',
  });
  await call(request, session, `/api/v1/routing-guides/${guide.id}/vendors`, {
    vendorPartnerId: vendor.id, rankNo: 1, responseSlaMinutes: 60, agreedRate: 20000,
  });

  const vehicle = await call(request, session, '/api/v1/vehicles', {
    orgUnitId: org, registrationNo: `MH-CM-${salt.toUpperCase()}`, category: 'TRUCK', vehicleType: 'RIGID',
    axleConfig: '2-AXLE', grossWeightKg: 26000, tareWeightKg: 6000, maxVolumeM3: 60,
    hazmatCertified: false, reeferCapable: false,
  });
  await call(request, session, `/api/v1/vehicles/${vehicle.id}/documents`, {
    documentType: 'INSURANCE', documentNo: `INS-CM-${salt}`, issuingAuthority: 'IRDAI',
    issuedOn: day(-365), expiresOn: day(365),
  });
  const driver = await call(request, session, '/api/v1/drivers', {
    orgUnitId: org, fullName: `Driver ${salt}`, phone: '+910000000001', licenceNo: `DL-CM-${salt}`,
    licenceClass: 'HEAVY', licenceAuthority: 'RTO', licenceExpiresOn: day(365), hazmatEndorsed: false,
  });

  const customer = await call(request, session, '/api/v1/partners', {
    orgUnitId: org, code: `ZZ-CM-C-${salt}`, legalName: 'Commercial customer', partnerType: 'CUSTOMER',
    taxId: `TAX-${crypto.randomUUID()}`,
  });
  const consignee = await call(request, session, '/api/v1/partners', {
    orgUnitId: org, code: `ZZ-CM-N-${salt}`, legalName: 'Commercial consignee', partnerType: 'CONSIGNEE',
    taxId: `TAX-${crypto.randomUUID()}`,
  });
  const order = await call(request, session, '/api/v1/orders', {
    orgUnitId: org, customerPartnerId: customer.id, orderNo: `ZZ-CM-SO-${salt}`, originTerminalId: origin.id,
    requestedPickupAt: at(240), source: 'API',
  });
  await call(request, session, `/api/v1/orders/${order.id}/lines`, {
    lineNo: 1, materialCode: 'GOODS', materialDescription: 'General goods', materialClass: 'GENERAL',
    quantity: 10, uom: 'CASE', deadWeightKg: 5000, consigneePartnerId: consignee.id,
    destinationTerminalId: destination.id,
  });
  await call(request, session, `/api/v1/orders/${order.id}/validate`, {});
  const consignments = await call(request, session, `/api/v1/orders/${order.id}/consignments`, {});
  const load = await call(request, session, '/api/v1/loads', {
    orgUnitId: org, loadNo: `ZZ-CM-LD-${salt}`, originTerminalId: origin.id, vehicleId: vehicle.id,
  });
  await call(request, session, `/api/v1/loads/${load.id}/consignments`, { consignmentId: consignments[0].id });
  await call(request, session, `/api/v1/loads/${load.id}/plan`, {});
  const allocation = await call(request, session, '/api/v1/allocations', { loadId: load.id });
  await call(request, session, `/api/v1/allocations/${allocation.id}/accept`, { vendorPartnerId: vendor.id });

  const tripNo = `TR-ZZ-CM-${salt}`;
  const trip = await call(request, session, '/api/v1/trips', { tripNo, loadId: load.id, plannedStartAt: at(-180) });
  const t = `/api/v1/trips/${trip.id}`;
  await call(request, session, `${t}/assignment`, { vehicleId: vehicle.id, driverId: driver.id });
  await call(request, session, `${t}/gate-in`, { terminalId: origin.id, at: at(-120) });
  await call(request, session, `${t}/weighings`, { terminalId: origin.id, type: 'TARE', weightKg: 6000, at: at(-120) });
  await call(request, session, `${t}/weighings`, { terminalId: origin.id, type: 'GROSS', weightKg: 11000, at: at(-120) });
  await call(request, session, `${t}/loaded`, { at: at(-91) });
  await call(request, session, `${t}/documents`, { documentType: 'CONSIGNMENT_NOTE', documentRef: `LR-${trip.id}` });
  await call(request, session, `${t}/documents`, { documentType: 'EWAY_BILL', documentRef: `EW-${trip.id}` });
  await call(request, session, `${t}/dispatch`, { at: at(-90) });
  await call(request, session, `${t}/in-transit`, {});
  await call(request, session, `${t}/arrival`, { at: at(-20) });
  await call(request, session, `${t}/completion`, { at: at(-10) });

  // Bills list in bill-number order, and the cursor is a bill number: start just below this one's.
  const billNo = `FB-${tripNo}`;
  const justBelow = billNo.slice(0, -1);
  let billId = '';
  await expect.poll(async () => {
    const page = await request.get(`${API}/api/v1/freight-bills?cursor=${encodeURIComponent(justBelow)}&limit=5`, {
      headers: { Authorization: `Bearer ${session.token}` },
    });
    const found = ((await page.json()).items as Array<{ id: string; billNo: string }>).find((b) => b.billNo === billNo);
    billId = found?.id ?? '';
    return billId;
  }, { message: `the freight bill of ${tripNo}`, timeout: 30_000 }).not.toBe('');
  return { tripNo, billId, billNo };
}

// Rate cards and rail tariffs

/** A rate card on its own lane with two weight bands: 4 a kilogram up to 10 000 kg, then a flat 45 000. */
export async function commercialRateCard(
  request: APIRequestContext, session: Session, salt: string,
): Promise<{ id: string; code: string; vendor: Place }> {
  const { origin, destination } = await commercialLane(request, session, `RC-${salt}`);
  const vendor = await commercialVendor(request, session, `ZZ-CM-RCV-${salt}`);
  const code = `ZZ-CM-RC-${salt}`;
  const card = await call(request, session, '/api/v1/tariffs', {
    orgUnitId: session.orgUnitId, code, vendorPartnerId: vendor.id, originTerminalId: origin.id,
    destinationTerminalId: destination.id, vehicleType: 'RIGID', currency: 'INR', effectiveFrom: day(-1),
    minimumCharge: 5000,
  });
  await call(request, session, `/api/v1/tariffs/${card.id}/slabs`, {
    minWeightKg: 10000, rateBasis: 'FLAT', rateAmount: 45000,
  });
  await call(request, session, `/api/v1/tariffs/${card.id}/slabs`, {
    minWeightKg: 0, maxWeightKg: 10000, rateBasis: 'PER_KG', rateAmount: 4,
  });
  return { id: card.id as string, code, vendor };
}

/** Two railheads far apart and an active rail carrier: what a rail rate card is published for. */
export async function commercialRailheads(
  request: APIRequestContext, session: Session, salt: string,
): Promise<{ from: Place; to: Place; carrier: Place }> {
  const railhead = async (name: string): Promise<Place> => {
    const code = `ZZ-CM-RH-${name}-${salt}`;
    const made = await call(request, session, '/api/v1/terminals/point-radius', {
      orgUnitId: session.orgUnitId, code, name: `Railhead ${name} ${salt}`, category: 'RAILHEAD',
      centre: { lat: between(24, 60), lon: between(10, 170) }, radiusMetres: 750, dockCount: 2, avgDwellMinutes: 45,
    });
    return { id: made.id as string, code };
  };
  return {
    from: await railhead('FROM'), to: await railhead('TO'),
    carrier: await commercialVendor(request, session, `ZZ-CM-RAIL-${salt}`),
  };
}

/** `count` rate cards of one vendor on one lane, one per vehicle type: more than a page of the listing. */
export async function commercialRateCards(
  request: APIRequestContext, session: Session, salt: string, count: number,
): Promise<Place> {
  const { origin, destination } = await commercialLane(request, session, `RM-${salt}`);
  const vendor = await commercialVendor(request, session, `ZZ-CM-RMV-${salt}`);
  for (let start = 0; start < count; start += 10) {
    await Promise.all(Array.from({ length: Math.min(10, count - start) }, (_, i) => call(request, session, '/api/v1/tariffs', {
      orgUnitId: session.orgUnitId, code: `ZZ-CM-RM-${salt}-${start + i + 1}`, vendorPartnerId: vendor.id,
      originTerminalId: origin.id, destinationTerminalId: destination.id, vehicleType: `RIGID-${start + i + 1}`,
      effectiveFrom: day(-1),
    })));
  }
  return vendor;
}
