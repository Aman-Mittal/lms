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
 * Stubs for the milk-run and itinerary desk spec: the API answers from a small in-memory model that keeps the server's
 * rules (a route needs suppliers, each once and none of them the plant; stops are recorded only while a run is under
 * way and in order; a trip carries one circuit; an itinerary's legs join end to start; rail legs move one milestone at
 * a time and road legs not at all; refusals as problem codes), so the same assertions run against both consoles
 * whatever the demo tenant holds. The requests each console sends are recorded for the spec.
 */

export const unitId = '44444444-5555-4666-8777-888888888888';
export const plantId = '11111111-2222-4333-8444-555555555551';
export const supplierA = '11111111-2222-4333-8444-555555555552';
export const supplierB = '11111111-2222-4333-8444-555555555553';
export const railheadA = '11111111-2222-4333-8444-555555555554';
export const railheadB = '11111111-2222-4333-8444-555555555555';
export const carrierId = '11111111-2222-4333-8444-555555555556';
export const tripId = '11111111-2222-4333-8444-555555555557';
export const busyTripId = '11111111-2222-4333-8444-555555555558';
export const cancelledTripId = '11111111-2222-4333-8444-555555555559';

const cors = { 'access-control-allow-origin': '*', 'access-control-expose-headers': 'ETag' };
const preflight = { status: 204, headers: { ...cors, 'access-control-allow-headers': '*', 'access-control-allow-methods': '*' } };
const json = (body: unknown, status = 200, headers: Record<string, string> = {}) => ({
  status, headers: { ...cors, ...headers }, contentType: 'application/json', body: JSON.stringify(body) });
const problem = (status: number, code: string, detail: string) => json({ title: 'Refused', status, code, detail }, status);
const none = () => ({ status: 204, headers: cors });
const emptyPage = () => json({ items: [], hasMore: false });

/** What a milk-run and itinerary desk holder may do; a spec drops some to see what the screens no longer offer. */
export const MILK_PERMISSIONS = [
  'MILKRUN_READ', 'MILKRUN_PLAN', 'MILKRUN_EXECUTE', 'MULTIMODAL_READ', 'MULTIMODAL_PLAN', 'MULTIMODAL_TRACK',
];

/** Signs in without a backend; used only when E2E_STUB_SESSION is set. */
export async function stubMilkSession(page: Page, dropped: string[] = []): Promise<void> {
  const permissions = MILK_PERMISSIONS.filter((p) => !dropped.includes(p));
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

interface RunStop {
  sequence: number; terminalId: string; terminalCode: string; status: string; arrivedAt?: string; departedAt?: string;
  pickedUpKg?: number; skipReason?: string;
}

interface Run {
  id: string; routeId: string; routeCode: string; runDate: string; status: string; tripId?: string; tripNo?: string;
  startedAt?: string; completedAt?: string; stopsVisited: number; stopsSkipped: number; chargeAmount: number;
  totalPickedUpKg: number; stops: RunStop[];
}

interface Leg {
  id: string; seq: number; mode: string; status: string; fromTerminalId: string; fromTerminalCode: string;
  toTerminalId: string; toTerminalCode: string; plannedDepartureAt: string; plannedArrivalAt: string;
  bookingRef?: string; trainNo?: string; carrierCode: string;
}

interface Itinerary {
  id: string; itineraryNo: string; shipmentRef: string; status: string; currency: string; totalCost: number; legs: Leg[];
}

export interface MilkStub {
  /** The routes published, as the console sent them. */
  routes: Array<{ id: string; code: string }>;
  runs: Run[];
  itineraries: Itinerary[];
  /** What each change sent: its method, path and body. */
  changes: Array<{ method: string; path: string; body: unknown }>;
  /** The trip's dispatch, which the milk-run desk leaves alone: a run that has a trip is under way. */
  startRun(runId: string): void;
}

let sequence = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++sequence).padStart(12, '0')}`;
const NEXT_RAIL: Record<string, string> = {
  PLANNED: 'PLACED', PLACED: 'LOADED', LOADED: 'DEPARTED', DEPARTED: 'ARRIVED', ARRIVED: 'RELEASED' };

const runView = ({ stops: _stops, ...view }: Run) => view;
const summaryOf = (i: Itinerary) => ({
  id: i.id, itineraryNo: i.itineraryNo, shipmentRef: i.shipmentRef, status: i.status, currency: i.currency,
  legCount: i.legs.length, totalCost: i.totalCost, connectionMissed: false, createdAt: new Date().toISOString() });

export async function stubMilk(page: Page): Promise<MilkStub> {
  const stub: MilkStub = {
    routes: [], runs: [], itineraries: [], changes: [],
    startRun: (runId) => {
      const run = stub.runs.find((r) => r.id === runId);
      if (run) {
        run.status = 'IN_PROGRESS';
        run.startedAt = new Date().toISOString();
      }
    },
  };
  const note = (route: Route, body: unknown) => {
    const request = route.request();
    stub.changes.push({ method: request.method(), path: new URL(request.url()).pathname, body });
  };

  await page.route(/\/api\/v1\/(vehicles|drivers|partners|trips)(\?.*)?$/, (route) => handle(route, emptyPage));
  await page.route(/\/api\/v1\/terminals(\/[^/?]+)?(\?.*)?$/, (route) => {
    if (route.request().method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const id = new URL(route.request().url()).pathname.split('/')[4];
    return route.fulfill(id ? json({ id, code: `T-${id.slice(-3)}`, name: 'Terminal', category: 'YARD' }) : emptyPage());
  });

  await page.route(/\/api\/v1\/milk-run-routes(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    if (request.method() !== 'POST') {
      return route.fulfill(emptyPage());
    }
    const body = request.postDataJSON() as {
      code: string; plantTerminalId: string; days: string[]; stops: Array<{ terminalId: string; windowOpenMin: number }>;
    };
    note(route, body);
    if (body.stops.some((s) => s.terminalId === body.plantTerminalId)) {
      return route.fulfill(problem(409, 'milk-run-plant-as-stop', 'The plant is where the circuit ends'));
    }
    if (new Set(body.stops.map((s) => s.terminalId)).size !== body.stops.length) {
      return route.fulfill(problem(409, 'milk-run-supplier-repeated', 'A circuit calls at each supplier once'));
    }
    const id = uuid();
    stub.routes.push({ id, code: body.code });
    return route.fulfill(json({ id }, 201));
  });

  await page.route(/\/api\/v1\/milk-runs(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const url = new URL(request.url());
    const parts = url.pathname.split('/').filter(Boolean).slice(3); // after api/v1/milk-runs
    const method = request.method();
    if (parts.length === 0 && method === 'GET') {
      const code = url.searchParams.get('routeCode');
      return route.fulfill(json({ items: stub.runs.filter((r) => !code || r.routeCode === code).map(runView), hasMore: false }));
    }
    if (parts[0] === 'generate' && method === 'POST') {
      const body = request.postDataJSON() as { fromDate?: string; days?: number } | null;
      note(route, body);
      const days = body?.days ?? 7;
      let made = 0;
      for (const r of stub.routes) {
        for (let d = 0; d < days; d++) {
          const date = new Date(Date.parse(`${body?.fromDate ?? '2026-12-01'}T00:00:00Z`) + d * 86_400_000).toISOString().slice(0, 10);
          if (!stub.runs.some((x) => x.routeId === r.id && x.runDate === date)) {
            stub.runs.push({
              id: uuid(), routeId: r.id, routeCode: r.code, runDate: date, status: 'PLANNED', stopsVisited: 0, stopsSkipped: 0,
              chargeAmount: 0, totalPickedUpKg: 0,
              stops: [{ sequence: 1, terminalId: supplierA, terminalCode: 'SUP-A', status: 'PENDING' },
                { sequence: 2, terminalId: supplierB, terminalCode: 'SUP-B', status: 'PENDING' }],
            });
            made++;
          }
        }
      }
      return route.fulfill(json({ generated: made }));
    }
    const run = stub.runs.find((r) => r.id === parts[0]);
    if (!run) {
      return route.fulfill(problem(404, 'not-found', 'No such run'));
    }
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill(json(runView(run)));
    }
    if (parts[1] === 'stops' && parts.length === 2 && method === 'GET') {
      return route.fulfill(json(run.stops));
    }
    if (method !== 'POST') {
      return route.fulfill(problem(404, 'not-found', 'No such route'));
    }
    const body = request.postDataJSON() as { tripId?: string; quantityKg?: number; reason?: string } | null;
    note(route, body);
    if (parts[1] === 'trip') {
      if (body?.tripId === cancelledTripId) {
        return route.fulfill(problem(409, 'trip-cancelled', 'The trip is cancelled'));
      }
      if (body?.tripId === busyTripId) {
        return route.fulfill(problem(409, 'trip-already-carries-a-circuit', 'The trip already carries a circuit'));
      }
      run.tripId = body?.tripId;
      run.tripNo = 'T-900';
      return route.fulfill(none());
    }
    if (run.status !== 'IN_PROGRESS') {
      return route.fulfill(problem(409, 'circuit-run-not-in-progress', `The run is ${run.status}`));
    }
    if (parts[1] === 'completion') {
      run.status = 'COMPLETED';
      run.completedAt = new Date().toISOString();
      run.chargeAmount = 100 + 10 * run.stopsVisited;
      return route.fulfill(none());
    }
    const stop = run.stops.find((s) => s.sequence === Number(parts[2]));
    if (parts[1] !== 'stops' || !stop) {
      return route.fulfill(problem(404, 'not-found', 'No such stop'));
    }
    const illegal = () => route.fulfill(problem(409, 'circuit-stop-illegal-transition', `The stop is ${stop.status}`));
    const now = new Date().toISOString();
    switch (parts[3]) {
      case 'arrival':
        if (stop.status !== 'PENDING') {
          return illegal();
        }
        stop.status = 'ARRIVED';
        stop.arrivedAt = now;
        return route.fulfill(none());
      case 'pickup':
        if (stop.status !== 'ARRIVED') {
          return illegal();
        }
        stop.pickedUpKg = body?.quantityKg;
        run.totalPickedUpKg += body?.quantityKg ?? 0;
        return route.fulfill(none());
      case 'departure':
        if (stop.status !== 'ARRIVED') {
          return route.fulfill(problem(409, 'circuit-stop-departs-before-arrival', 'Arrive first'));
        }
        stop.status = 'DEPARTED';
        stop.departedAt = now;
        run.stopsVisited += 1;
        return route.fulfill(none());
      case 'skip':
        if (stop.status !== 'PENDING') {
          return illegal();
        }
        stop.status = 'SKIPPED';
        stop.skipReason = body?.reason;
        run.stopsSkipped += 1;
        return route.fulfill(none());
      default:
        return route.fulfill(problem(404, 'not-found', 'No such route'));
    }
  });

  await page.route(/\/api\/v1\/itineraries(\/.*)?(\?.*)?$/, async (route) => {
    const request = route.request();
    if (request.method() === 'OPTIONS') {
      return route.fulfill(preflight);
    }
    const parts = new URL(request.url()).pathname.split('/').filter(Boolean).slice(3); // after api/v1/itineraries
    const method = request.method();
    if (parts.length === 0 && method === 'GET') {
      return route.fulfill(json({ items: stub.itineraries.map(summaryOf), hasMore: false }));
    }
    if (parts.length === 0 && method === 'POST') {
      const body = request.postDataJSON() as {
        shipmentRef: string; legs: Array<{ mode: string; fromTerminalId: string; toTerminalId: string;
          plannedDepartureAt: string; plannedArrivalAt: string; bookingRef?: string; trainNo?: string }>;
      };
      note(route, body);
      for (let i = 1; i < body.legs.length; i++) {
        if (body.legs[i].fromTerminalId !== body.legs[i - 1].toTerminalId) {
          return route.fulfill(problem(409, 'itinerary-not-contiguous', `Leg ${i + 1} does not start where leg ${i} ends`));
        }
      }
      if (body.legs.some((l) => l.mode === 'RAIL' && !l.bookingRef)) {
        return route.fulfill(problem(409, 'rail-leg-needs-booking', 'A rail leg needs a booking reference'));
      }
      const n = stub.itineraries.length + 1;
      const itinerary: Itinerary = {
        id: uuid(), itineraryNo: `IT-${n}`, shipmentRef: body.shipmentRef, status: 'PLANNED', currency: 'INR', totalCost: 0,
        legs: body.legs.map((l, i) => ({
          id: uuid(), seq: i + 1, mode: l.mode, status: 'PLANNED', fromTerminalId: l.fromTerminalId,
          fromTerminalCode: `T-${l.fromTerminalId.slice(-3)}`, toTerminalId: l.toTerminalId,
          toTerminalCode: `T-${l.toTerminalId.slice(-3)}`, plannedDepartureAt: l.plannedDepartureAt,
          plannedArrivalAt: l.plannedArrivalAt, bookingRef: l.bookingRef, trainNo: l.trainNo, carrierCode: 'CARRIER',
        })),
      };
      stub.itineraries.unshift(itinerary);
      return route.fulfill(json({ id: itinerary.id }, 201));
    }
    const itinerary = stub.itineraries.find((i) => i.id === parts[0]);
    if (!itinerary) {
      return route.fulfill(problem(404, 'not-found', 'No such itinerary'));
    }
    if (parts.length === 1 && method === 'GET') {
      return route.fulfill(json({ ...summaryOf(itinerary), legs: itinerary.legs, costByCarrier: [] }));
    }
    if (parts[1] === 'legs' && parts[3] === 'milestones' && method === 'POST') {
      const body = request.postDataJSON() as { milestone: string };
      note(route, body);
      const leg = itinerary.legs.find((l) => l.seq === Number(parts[2]));
      if (!leg) {
        return route.fulfill(problem(404, 'leg-not-found', 'No such leg'));
      }
      if (leg.mode !== 'RAIL') {
        return route.fulfill(problem(409, 'leg-not-rail', 'Only rail legs are moved here'));
      }
      if (NEXT_RAIL[leg.status] !== body.milestone) {
        return route.fulfill(problem(409, 'leg-illegal-transition', `Leg ${leg.seq} is ${leg.status}`));
      }
      leg.status = body.milestone;
      itinerary.status = itinerary.legs.every((l) => l.status === 'RELEASED') ? 'COMPLETED' : 'IN_PROGRESS';
      return route.fulfill(none());
    }
    return route.fulfill(problem(404, 'not-found', 'No such route'));
  });
  return stub;
}
