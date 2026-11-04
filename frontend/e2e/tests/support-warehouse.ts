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

import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { openScreen, type Session } from './support';

const API = process.env.E2E_API_URL ?? 'http://localhost:8080';

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

/** A fresh warehouse with one roomy bin and one SKU of its own, so a spec sees only what it made. */
export interface Floor {
  id: string;
  code: string;
  binId: string;
  binCode: string;
  skuId: string;
  skuCode: string;
}

export async function newFloor(request: APIRequestContext, session: Session): Promise<Floor> {
  const code = `E2E-WH-${uuid()}`;
  const warehouse = await post(request, session, '/api/v1/terminals/point-radius', {
    orgUnitId: session.orgUnitId, code, name: 'E2E warehouse', category: 'WAREHOUSE',
    centre: { lat: Number((24 + Math.random() * 30).toFixed(6)), lon: Number((10 + Math.random() * 150).toFixed(6)) },
    radiusMetres: 500, dockCount: 2, avgDwellMinutes: 45,
  });
  const binCode = `E2E-BIN-${uuid().slice(0, 8)}`;
  const bin = await post(request, session, `/api/v1/warehouses/${warehouse.id}/bins`, { code: binCode, capacityUnits: 500 });
  const skuCode = `E2E-SKU-${uuid()}`;
  const sku = await post(request, session, '/api/v1/skus', { code: skuCode, description: 'E2E widget', uom: 'EA' });
  return { id: warehouse.id as string, code, binId: bin.id as string, binCode, skuId: sku.id as string, skuCode };
}

/** Receives units of the floor's SKU at the dock, `damaged` of them into quarantine; resolves to the ASN's id. */
export async function received(
  request: APIRequestContext, session: Session, floor: Floor, quantity: number, damaged = 0,
): Promise<string> {
  const asn = await post(request, session, '/api/v1/asns', {
    warehouseId: floor.id, asnNo: `E2E-ASN-${uuid()}`, lines: [{ skuId: floor.skuId, quantity }],
  });
  await post(request, session, `/api/v1/asns/${asn.id}/receipt`, {
    lines: [{ lineNo: 1, receivedQty: quantity, damagedQty: damaged }], crossDock: false,
  });
  return asn.id as string;
}

/** Receives units and puts them all away in the floor's bin. */
export async function stocked(request: APIRequestContext, session: Session, floor: Floor, quantity: number): Promise<void> {
  await received(request, session, floor, quantity);
  await post(request, session, '/api/v1/putaways', { binId: floor.binId, skuId: floor.skuId, quantity });
}

/** An open outbound order for units of the floor's SKU; resolves to its number. */
export async function outboundOrder(
  request: APIRequestContext, session: Session, floor: Floor, quantity: number,
): Promise<string> {
  const orderNo = `E2E-OUT-${uuid().slice(0, 8)}`;
  await post(request, session, '/api/v1/outbound-orders', {
    warehouseId: floor.id, orderNo, lines: [{ skuId: floor.skuId, quantity }],
  });
  return orderNo;
}

/** Opens the warehouse desk on one warehouse. */
export async function openFloor(page: Page, warehouseId: string): Promise<void> {
  await openScreen(page, 'nav-warehouse', 'Warehouse operations');
  await page.getByTestId('warehouse-id').fill(warehouseId);
  await page.getByRole('button', { name: 'Open' }).click();
  await expect(page.getByTestId('warehouse-desk')).toBeVisible();
}
