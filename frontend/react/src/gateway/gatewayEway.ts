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

import { Configuration, EWayBillsApi, type EwayBill, type EwayBillItemOutcome } from '../api';
import type { LmsGateway } from './lmsGateway';
import type { EwayBillRow, EwayItemOutcome, EwayStatus } from './ewayTypes';
import { blankAsAbsent, guard, text } from './gatewayShared';

type EwayVerbs = Pick<LmsGateway, 'ewayBills' | 'expiringEwayBills' | 'ewayBill' | 'ewayBillByNumber'
  | 'consignmentEwayBills' | 'generateEwayBill' | 'actOnEwayBill' | 'tripEwayPartB'>;

function rowOf(b: EwayBill): EwayBillRow {
  return {
    id: b.id, billNo: b.billNo, status: b.status as EwayStatus, consignmentId: b.consignmentId,
    trackingRef: b.trackingRef, generatedAt: text(b.generatedAt), validUntil: text(b.validUntil),
    vehicleNo: text(b.vehicleNo), distanceKm: text(b.distanceKm), source: b.source, extensionCount: b.extensionCount,
    cancelReason: text(b.cancelReason), expiringSoon: b.expiringSoon,
  };
}

function itemOf(i: EwayBillItemOutcome): EwayItemOutcome {
  return {
    consignmentId: i.consignmentId, trackingRef: i.trackingRef, billNo: text(i.billNo),
    outcome: i.outcome as EwayItemOutcome['outcome'], code: text(i.code), message: text(i.message),
  };
}

/** The e-way bill desk. */
export function ewayVerbs(config: Configuration): EwayVerbs {
  const api = new EWayBillsApi(config);

  return {
    ewayBills: (filter, cursor) =>
      guard(async () => {
        const page = await api.listEwayBills({
          status: blankAsAbsent(filter.status), q: blankAsAbsent(filter.q), cursor, limit: 50,
        });
        return { rows: page.items.map(rowOf), nextCursor: page.nextCursor ?? null };
      }),

    expiringEwayBills: () =>
      guard(async () => {
        const page = await api.listExpiringEwayBills({ limit: 50 });
        return { rows: page.items.map(rowOf), nextCursor: page.nextCursor ?? null };
      }),

    ewayBill: (id) => guard(async () => rowOf(await api.getEwayBill({ id }))),

    ewayBillByNumber: (billNo) => guard(async () => rowOf(await api.getEwayBillByNumber({ billNo: billNo.trim() }))),

    consignmentEwayBills: (consignmentId) =>
      guard(async () => (await api.listConsignmentEwayBills({ consignmentId: consignmentId.trim() })).map(rowOf)),

    generateEwayBill: (draft) =>
      guard(async () => text((await api.generateEwayBill({
        consignmentId: draft.consignmentId.trim(),
        generateEwayBillRequest: {
          documentRef: blankAsAbsent(draft.documentRef), vehicleNo: blankAsAbsent(draft.vehicleNo),
          distanceKm: draft.distanceKm.trim() ? Number(draft.distanceKm) : undefined,
        },
      })).id)),

    actOnEwayBill: (id, action, reason, vehicleNo) =>
      guard(async () => rowOf(await api.actOnEwayBill({
        id, ewayBillActionRequest: { action, reason: blankAsAbsent(reason), vehicleNo: blankAsAbsent(vehicleNo) },
      }))),

    tripEwayPartB: (tripId, vehicleNo) =>
      guard(async () => {
        const r = await api.updateTripEwayBillPartB({
          id: tripId.trim(), tripPartBRequest: { vehicleNo: blankAsAbsent(vehicleNo) },
        });
        return { tripId: r.tripId, vehicleNo: r.vehicleNo, updated: r.updated, items: r.items.map(itemOf) };
      }),
  };
}
