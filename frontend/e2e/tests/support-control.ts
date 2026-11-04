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
import { DEMO_EMAIL, newPlannedLoad, offerLoad, type Session } from './support';

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

async function call(
  request: APIRequestContext, session: Session, method: 'GET' | 'POST' | 'PUT', path: string, data?: unknown,
) {
  const response = await request.fetch(`${API}${path}`, {
    method, data, headers: { Authorization: `Bearer ${session.token}` },
  });
  expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return response.status() === 204 ? null : response.json();
}

export interface ControlTrip {
  tripId: string;
  tripNo: string;
}

/**
 * A trip on a lane of its own: a planned load offered for sourcing, taken by the
 * vendor it was offered to, and a trip raised against it. Nothing has moved yet,
 * so the trip has no position and no geofence crossings.
 */
export async function newTrip(request: APIRequestContext, session: Session, salt: string): Promise<ControlTrip> {
  const lane = await newPlannedLoad(request, session, salt);
  const allocationId = await offerLoad(request, session, lane.loadId);
  const offers = await call(request, session, 'GET', `/api/v1/allocations/${allocationId}/offers`) as Array<{
    vendorPartnerId: string; outcome: string;
  }>;
  const pending = offers.find((o) => o.outcome === 'PENDING');
  expect(pending, 'the load was offered to a vendor').toBeTruthy();
  await call(request, session, 'POST', `/api/v1/allocations/${allocationId}/accept`, {
    vendorPartnerId: pending!.vendorPartnerId,
  });
  const tripNo = `ZZ-CT-TRP-${salt}`;
  const trip = await call(request, session, 'POST', '/api/v1/trips', { tripNo, loadId: lane.loadId });
  return { tripId: trip.id as string, tripNo };
}

/** Raises a ticket against the trip; P3 categories such as DOCUMENT_MISMATCH never hold settlement. */
export async function newTicket(
  request: APIRequestContext, session: Session, tripId: string, category = 'DOCUMENT_MISMATCH',
): Promise<{ id: string; ticketNo: string }> {
  const made = await call(request, session, 'POST', '/api/v1/exceptions', {
    tripId, category, description: `Raised by a browser test (${category})`,
  });
  const read = await call(request, session, 'GET', `/api/v1/exceptions/${made.id}`);
  return { id: made.id as string, ticketNo: read.ticketNo as string };
}

/** The demo administrator's user id, to assign tickets to. */
export async function adminUserId(request: APIRequestContext, session: Session): Promise<string> {
  const users = await call(request, session, 'GET', '/api/v1/users') as Array<{ id: string; email: string }>;
  const admin = users.find((u) => u.email === DEMO_EMAIL);
  expect(admin, 'the demo administrator is listed').toBeTruthy();
  return admin!.id;
}

/** Takes a ticket to RESOLVED through the API, as another person on the desk would. */
export async function resolveElsewhere(request: APIRequestContext, session: Session, ticketId: string): Promise<void> {
  const assignee = await adminUserId(request, session);
  await call(request, session, 'POST', `/api/v1/exceptions/${ticketId}/assign`, { assigneeUserId: assignee });
  await call(request, session, 'POST', `/api/v1/exceptions/${ticketId}/investigate`, {});
  await call(request, session, 'POST', `/api/v1/exceptions/${ticketId}/resolve`, { resolution: 'Paperwork corrected' });
}

export async function closeElsewhere(request: APIRequestContext, session: Session, ticketId: string): Promise<void> {
  await call(request, session, 'POST', `/api/v1/exceptions/${ticketId}/close`, { note: 'Closed from another desk' });
}

export interface DetectorSettings {
  etaSlipMinutes: number;
  temperatureExcursionMinutes: number;
}

export async function detectorSettings(request: APIRequestContext, session: Session): Promise<DetectorSettings> {
  const d = await call(request, session, 'GET', '/api/v1/exceptions/detector-settings');
  return { etaSlipMinutes: d.etaSlipMinutes, temperatureExcursionMinutes: d.temperatureExcursionMinutes };
}

/** Puts the tenant's detector settings back, so a spec leaves the shared demo tenant as it found it. */
export async function restoreDetectorSettings(
  request: APIRequestContext, session: Session, settings: DetectorSettings,
): Promise<void> {
  await call(request, session, 'PUT', '/api/v1/exceptions/detector-settings', settings);
}
