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
import type { Page, Route } from '@playwright/test';

/*
 * Stubs for the trip desk spec: the API answers from a small in-memory model that keeps the server's rules (a trip
 * only against an awarded load, one live trip per load, the state machine's order, a reason to cancel, no cancelling
 * once dispatched, a load needs consignments to be planned, refusals as problem codes), so the same assertions run
 * against both consoles whatever the demo tenant holds. The requests each console sends are recorded for the spec.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';
export const awardedLoadId = '11111111-2222-4333-8444-555555555555';
export const draftLoadId = '22222222-3333-4444-8555-666666666666';
export const vehicleId = '33333333-4444-4555-8666-777777777777';
export const driverId = '55555555-6666-4777-8888-999999999999';
export const terminalId = '66666666-7777-4888-8999-aaaaaaaaaaaa';
export const consignmentId = '77777777-8888-4999-8aaa-bbbbbbbbbbbb';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) => ({
  status, headers: { ...cors, ...headers }, contentType: 'application/json', body: JSON.stringify(body) });
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);
const none = () => ({ status: 204, headers: cors });
const emptyPage = () => json({ items: [], hasMore: false });

/** What a trip and load desk holder may do; a spec drops some to see what the screens no longer offer. */
export const TRIP_PERMISSIONS = ['TRIP_READ', 'TRIP_EXECUTE', 'LOAD_READ', 'LOAD_CREATE'];

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubTripSession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = TRIP_PERMISSIONS.filter((p) => !dropped.includes(p));
  await page.route('**/api/v1/exceptions/summary', (route) => handle(route, () => json({
    openP1: 0, openP2: 0, openP3: 0, unassigned: 0, settlementHolds: 0, openWithoutTrip: 0 })));
  await page.route('**/api/v1/auth/login', (route) => handle(route, () => json({
    accessToken: 'stub-token', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'stub-refresh',
    refreshTokenExpiresAt: '2099-01-01T00:00:00Z', tokenType: 'Bearer', orgUnitId: unitId, permissions })));
}

async function handle(route: Route, answer: () => ReturnType<typeof json>): Promise<void> {
  if (route.request().method() === 'OPTIONS') {
    return route.fulfill(preflight);
  }
  return route.fulfill(answer());
}

interface Stop {
  id: string; seq: number; kind: string; terminalId: string; terminalCode: string; terminalName: string;
  status: string; arrivedAt?: string; departedAt?: string; outOfOrder: boolean; version: number;
}

interface Trip {
  id: string; tripNo: string; status: string; loadId: string; loadNo: string; vehicleRegistrationNo?: string;
  driverName?: string; originTerminalCode: string; destinationTerminalCode: string; plannedStartAt?: string;
  dispatchedAt?: string; arrivedAt?: string; completedAt?: string; cancelledAt?: string; cancellationReason?: string;
  documentCount: number; createdAt: string; stops: Stop[]; blockDispatch: boolean;
}

interface Load {
  id: string; loadNo: string; status: string; freightMode: string; consignments: string[]; version: number;
}

export interface TripStub {
  trips: Trip[];
  loads: Load[];
  /** What each change sent: its method, path and body. */
  changes: Array<{ method: string; path: string; body: unknown }>;
  /** Puts a trip in the model, as if raised earlier, with two planned stops. */
  seedTrip(over: Partial<Trip> & { tripNo: string }): Trip;
  /** The gate desk's work, which the trip desk leaves alone: the vehicle is let in. */
  gateIn(tripId: string): void;
}

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
const live = (t: Trip) => t.status !== 'CANCELLED' && t.status !== 'COMPLETED';
const CANCELLABLE = ['PLANNED', 'ASSIGNED', 'AT_ORIGIN', 'LOADED'];

function stopsOf(): Stop[] {
  return [
    { id: uuid(), seq: 1, kind: 'PICKUP', terminalId: uuid(), terminalCode: 'ORG', terminalName: 'Origin yard',
      status: 'PLANNED', outOfOrder: false, version: 0 },
    { id: uuid(), seq: 2, kind: 'DROP', terminalId: uuid(), terminalCode: 'DST', terminalName: 'Destination hub',
      status: 'PLANNED', outOfOrder: false, version: 0 },
  ];
}

const tripView = ({ stops: _stops, blockDispatch: _block, ...view }: Trip) => view;
const loadView = (l: Load) => ({
  id: l.id, loadNo: l.loadNo, status: l.status, originTerminalCode: 'ORG', vehicleRegistrationNo: 'MH12AB1234',
  capacityWeightKg: 10000, plannedWeightKg: 100 * l.consignments.length, weightUtilisationPct: l.consignments.length,
  consignmentCount: l.consignments.length, requiresHazmat: false, freightMode: l.freightMode, ltlLeg: 'LINE_HAUL',
  createdAt: new Date().toISOString(), version: l.version });
const consignmentView = (id: string, n: number) => ({
  id, trackingRef: `LR-${n}`, status: 'PLANNED', consigneeName: 'Consignee', destinationTerminalCode: 'DST',
  totalDeadWeightKg: 100, materialClasses: 'GENERAL', containsHazmat: false });

export async function stubTrips(page: Page): Promise<TripStub> {
  const stub: TripStub = {
    trips: [], loads: [
      { id: awardedLoadId, loadNo: 'LOAD-AWARDED', status: 'AWARDED', freightMode: 'FTL', consignments: [], version: 0 },
      { id: draftLoadId, loadNo: 'LOAD-DRAFT', status: 'DRAFT', freightMode: 'FTL', consignments: [], version: 0 },
    ],
    changes: [],
    seedTrip: (over) => {
      const trip: Trip = {
        id: uuid(), status: 'PLANNED', loadId: uuid(), loadNo: 'LOAD-X', originTerminalCode: 'ORG',
        destinationTerminalCode: 'DST', documentCount: 0, createdAt: new Date().toISOString(), stops: stopsOf(),
        blockDispatch: false, ...over,
      };
      stub.trips.unshift(trip);
      return trip;
    },
    gateIn: (tripId) => {
      const trip = stub.trips.find((t) => t.id === tripId);
      if (trip) {
        trip.status = 'AT_ORIGIN';
      }
    },
  };
  const note = (route: Route, body: unknown) => {
    const request = route.request();
    stub.changes.push({ method: request.method(), path: new URL(request.url()).pathname, body });
  };

  await page.route(/\/api\/v1\/(vehicles|drivers|terminals|consignments\/unassigned)(\?.*)?$/, (route) => handle(route, emptyPage));

  await page.route(/\/api\/v1\/trips(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const parts = url.pathname.split('/').filter(Boolean).slice(3); // after api/v1/trips
    const method = request.method();
    if (parts.length === 0 && method === 'GET') {
      return route.fulfill(emptyPage());
    }
    if (parts.length === 0 && method === 'POST') {
      const body = request.postDataJSON() as { tripNo: string; loadId: string; plannedStartAt?: string };
      note(route, body);
      const load = stub.loads.find((l) => l.id === body.loadId);
      if (!load) {
        return route.fulfill(problem(404, 'not-found', 'No such load'));
      }
      if (load.status !== 'AWARDED') {
        return route.fulfill(problem(409, 'load-not-awarded', `Load ${load.loadNo} is ${load.status}`));
      }
      if (stub.trips.some((t) => t.tripNo === body.tripNo)) {
        return route.fulfill(problem(409, 'trip-no-taken', 'That trip number is taken'));
      }
      if (stub.trips.some((t) => t.loadId === load.id && live(t))) {
        return route.fulfill(problem(409, 'load-already-has-trip', 'The load already has a live trip'));
      }
      const trip = stub.seedTrip({ tripNo: body.tripNo, loadId: load.id, loadNo: load.loadNo, plannedStartAt: body.plannedStartAt });
      return route.fulfill(json({ id: trip.id }, 201));
    }
    const trip = stub.trips.find((t) => t.id === parts[0]);
    if (!trip) {
      return route.fulfill(problem(404, 'not-found', 'No such trip'));
    }
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill(json(tripView(trip)));
    }
    if (parts[1] === 'stops' && parts.length === 2 && method === 'GET') {
      return route.fulfill(json({ stops: trip.stops, etag: '"1"' }, 200, { ETag: '"1"' }));
    }
    if (parts[1] === 'stops' && parts[3] && method === 'POST') {
      const stop = trip.stops.find((s) => s.id === parts[2]);
      note(route, undefined);
      if (!stop) {
        return route.fulfill(problem(404, 'not-found', 'No such stop'));
      }
      const arriving = parts[3] === 'arrival';
      if (arriving ? stop.status !== 'PLANNED' : stop.status !== 'ARRIVED') {
        return route.fulfill(problem(409, 'trip-stop-illegal-transition', 'The stop is not in a state for that'));
      }
      stop.status = arriving ? 'ARRIVED' : 'DEPARTED';
      stop[arriving ? 'arrivedAt' : 'departedAt'] = new Date().toISOString();
      return route.fulfill(none());
    }
    if (method !== 'POST') {
      return route.fulfill(problem(404, 'not-found', 'No such route'));
    }
    const body = request.postDataJSON() as { vehicleId?: string; driverId?: string; reason?: string } | null;
    note(route, body);
    const now = new Date().toISOString();
    const illegal = () => route.fulfill(problem(409, 'trip-illegal-transition', `Trip ${trip.tripNo} is ${trip.status}`));
    switch (parts[1]) {
      case 'assignment':
        if (trip.status !== 'PLANNED') {
          return illegal();
        }
        if (body?.vehicleId === inactiveVehicleId) {
          return route.fulfill(problem(409, 'vehicle-inactive', 'That vehicle is switched off'));
        }
        trip.status = 'ASSIGNED';
        trip.vehicleRegistrationNo = 'MH12AB1234';
        trip.driverName = body?.driverId ? 'Asha Driver' : undefined;
        return route.fulfill(none());
      case 'loaded':
        if (trip.status !== 'AT_ORIGIN') {
          return illegal();
        }
        trip.status = 'LOADED';
        return route.fulfill(none());
      case 'dispatch':
        if (trip.status !== 'LOADED') {
          return illegal();
        }
        if (trip.blockDispatch) {
          return route.fulfill(problem(409, 'dispatch-blocked', 'Blocked: e-way bill missing; driver licence expired'));
        }
        trip.status = 'DISPATCHED';
        trip.dispatchedAt = now;
        return route.fulfill(none());
      case 'in-transit':
        if (trip.status !== 'DISPATCHED') {
          return illegal();
        }
        trip.status = 'IN_TRANSIT';
        return route.fulfill(none());
      case 'arrival':
        if (trip.status !== 'IN_TRANSIT') {
          return illegal();
        }
        trip.status = 'AT_DESTINATION';
        trip.arrivedAt = now;
        return route.fulfill(none());
      case 'completion':
        if (trip.status !== 'AT_DESTINATION') {
          return illegal();
        }
        trip.status = 'COMPLETED';
        trip.completedAt = now;
        return route.fulfill(none());
      case 'cancellation':
        if (!(body?.reason ?? '').trim()) {
          return route.fulfill(problem(400, 'validation-failed', 'A reason is required'));
        }
        if (!CANCELLABLE.includes(trip.status)) {
          return route.fulfill(problem(409, 'trip-not-cancellable', `Trip ${trip.tripNo} has left the yard`));
        }
        trip.status = 'CANCELLED';
        trip.cancelledAt = now;
        trip.cancellationReason = body?.reason?.trim();
        return route.fulfill(none());
      default:
        return route.fulfill(problem(404, 'not-found', 'No such route'));
    }
  });

  await page.route(/\/api\/v1\/loads(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const parts = new URL(request.url()).pathname.split('/').filter(Boolean).slice(3); // after api/v1/loads
    const method = request.method();
    if (parts.length === 0 && method === 'GET') {
      return route.fulfill(emptyPage());
    }
    if (parts.length === 0 && method === 'POST') {
      const body = request.postDataJSON() as { loadNo: string };
      note(route, body);
      if (stub.loads.some((l) => l.loadNo === body.loadNo)) {
        return route.fulfill(problem(409, 'load-no-taken', 'That load number is taken'));
      }
      const load: Load = { id: uuid(), loadNo: body.loadNo, status: 'DRAFT', freightMode: 'FTL', consignments: [], version: 0 };
      stub.loads.push(load);
      return route.fulfill(json({ id: load.id }, 201));
    }
    const load = stub.loads.find((l) => l.id === parts[0]);
    if (!load) {
      return route.fulfill(problem(404, 'not-found', 'No such load'));
    }
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill(json(loadView(load), 200, { ETag: `"${load.version}"` }));
    }
    if (parts[1] === 'consignments' && parts.length === 2 && method === 'GET') {
      return route.fulfill(json(load.consignments.map((id, i) => consignmentView(id, i + 1))));
    }
    if (parts[1] === 'consignments' && parts[2] === 'bulk' && method === 'POST') {
      const body = request.postDataJSON() as { consignmentIds: string[] };
      note(route, body);
      load.consignments.push(...body.consignmentIds);
      load.version += 1;
      return route.fulfill(none());
    }
    if (parts[1] === 'plan' && method === 'POST') {
      note(route, undefined);
      if (load.consignments.length === 0) {
        return route.fulfill(problem(409, 'load-is-empty', 'The load has nothing aboard'));
      }
      load.status = 'PLANNED';
      load.version += 1;
      return route.fulfill(none());
    }
    return route.fulfill(problem(404, 'not-found', 'No such route'));
  });
  return stub;
}

/** A vehicle the stub treats as switched off. */
export const inactiveVehicleId = '88888888-9999-4aaa-8bbb-cccccccccccc';
