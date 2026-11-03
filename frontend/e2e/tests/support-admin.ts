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
import { DEMO_EMAIL, type Session } from './support';

// Data the administration specs make and put back straight through the API.

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

async function send(
  request: APIRequestContext, session: Session, method: 'GET' | 'PUT' | 'POST' | 'DELETE', path: string, data?: unknown,
) {
  const response = await request.fetch(`${API}${path}`, {
    method, headers: { Authorization: `Bearer ${session.token}` }, data,
  });
  expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return response.status() === 204 ? null : response.json();
}

/** The demo administrator's own user id, found by the address it signs in with. */
export async function adminUserId(request: APIRequestContext, session: Session): Promise<string> {
  const users = (await send(request, session, 'GET', '/api/v1/users')) as Array<{ id: string; email: string }>;
  const me = users.find((u) => u.email.toLowerCase() === DEMO_EMAIL);
  expect(me, 'the demo administrator is listed').toBeTruthy();
  return me!.id;
}

export interface AccessAttributes {
  region?: string;
  freightModes?: string[];
  partnerId?: string;
  approvalLimit?: number;
}

export async function accessAttributesOf(
  request: APIRequestContext, session: Session, userId: string,
): Promise<AccessAttributes> {
  return send(request, session, 'GET', `/api/v1/users/${userId}/access-attributes`);
}

/**
 * Puts a user's attributes back as they were read. Called from a `finally`: the demo
 * administrator is shared by every spec, and a region or mode left behind would be carried
 * by every later sign-in.
 */
export async function restoreAccessAttributes(
  request: APIRequestContext, session: Session, userId: string, attributes: AccessAttributes,
): Promise<void> {
  await send(request, session, 'PUT', `/api/v1/users/${userId}/access-attributes`, {
    region: attributes.region, freightModes: attributes.freightModes, partnerId: attributes.partnerId,
    approvalLimit: attributes.approvalLimit,
  });
}

/** Deletes the access policy with this code, if a failed run left it behind. */
export async function deletePolicy(request: APIRequestContext, session: Session, code: string): Promise<void> {
  let cursor: string | undefined;
  do {
    const page = (await send(request, session, 'GET',
      `/api/v1/access-policies?limit=100${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`)) as {
      items: Array<{ id: string; code: string }>; nextCursor?: string; hasMore: boolean;
    };
    for (const p of page.items.filter((x) => x.code === code)) {
      await send(request, session, 'DELETE', `/api/v1/access-policies/${p.id}`);
    }
    cursor = page.hasMore ? page.nextCursor : undefined;
  } while (cursor);
}

/** A vehicle with no documents yet, for a spec to attach one to. */
export async function newVehicle(
  request: APIRequestContext, session: Session, salt: string,
): Promise<{ id: string; registrationNo: string }> {
  const registrationNo = `MH-AD-${salt.toUpperCase()}`;
  const vehicle = await send(request, session, 'POST', '/api/v1/vehicles', {
    orgUnitId: session.orgUnitId, registrationNo, category: 'TRUCK', vehicleType: 'RIGID',
    axleConfig: '2-AXLE', grossWeightKg: 26000, tareWeightKg: 6000, maxVolumeM3: 60,
    hazmatCertified: false, reeferCapable: false,
  });
  return { id: vehicle.id as string, registrationNo };
}

/** A day `days` from today, as a date input takes it. */
export function inDays(days: number): string {
  return new Date(Date.now() + days * 86_400_000).toISOString().slice(0, 10);
}
