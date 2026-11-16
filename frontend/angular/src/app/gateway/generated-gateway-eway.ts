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

import { inject } from '@angular/core';
import { EWayBillsService } from '../api';
import type { EwayBill, EwayBillItemOutcome } from '../api';
import type {
  EwayAction, EwayBillPage, EwayBillRow, EwayFilter, EwayGenerateDraft, EwayItemOutcome, EwayPartBResult, EwayStatus,
} from './eway-types';
import { GeneratedGatewayEnquiries } from './generated-gateway-enquiries';
import { blankAsAbsent, instant, text } from './generated-gateway-helpers';

function rowOf(b: EwayBill): EwayBillRow {
  return {
    id: b.id, billNo: b.billNo, status: String(b.status) as EwayStatus, consignmentId: b.consignmentId,
    trackingRef: b.trackingRef, generatedAt: instant(b.generatedAt), validUntil: instant(b.validUntil),
    vehicleNo: text(b.vehicleNo), distanceKm: text(b.distanceKm), source: b.source, extensionCount: b.extensionCount,
    cancelReason: text(b.cancelReason), expiringSoon: b.expiringSoon,
  };
}

function itemOf(i: EwayBillItemOutcome): EwayItemOutcome {
  return {
    consignmentId: i.consignmentId, trackingRef: i.trackingRef, billNo: text(i.billNo),
    outcome: String(i.outcome) as EwayItemOutcome['outcome'], code: text(i.code), message: text(i.message),
  };
}

/** The e-way bill desk. */
export abstract class GeneratedGatewayEway extends GeneratedGatewayEnquiries {
  protected readonly ewayApi = inject(EWayBillsService);

  ewayBills(filter: EwayFilter, cursor?: string): Promise<EwayBillPage> {
    return this.call(this.ewayApi.listEwayBills({
      status: blankAsAbsent(filter.status), q: blankAsAbsent(filter.q), cursor, limit: 50,
    }), (s) => ({ rows: s.items.map(rowOf), nextCursor: s.nextCursor ?? null }));
  }

  expiringEwayBills(): Promise<EwayBillPage> {
    return this.call(this.ewayApi.listExpiringEwayBills({ limit: 50 }),
      (s) => ({ rows: s.items.map(rowOf), nextCursor: s.nextCursor ?? null }));
  }

  ewayBill(id: string): Promise<EwayBillRow> {
    return this.call(this.ewayApi.getEwayBill({ id }), rowOf);
  }

  ewayBillByNumber(billNo: string): Promise<EwayBillRow> {
    return this.call(this.ewayApi.getEwayBillByNumber({ billNo: billNo.trim() }), rowOf);
  }

  consignmentEwayBills(consignmentId: string): Promise<EwayBillRow[]> {
    return this.call(this.ewayApi.listConsignmentEwayBills({ consignmentId: consignmentId.trim() }),
      (bills) => bills.map(rowOf));
  }

  generateEwayBill(draft: EwayGenerateDraft): Promise<string> {
    return this.call(this.ewayApi.generateEwayBill({
      consignmentId: draft.consignmentId.trim(),
      generateEwayBillRequest: {
        documentRef: blankAsAbsent(draft.documentRef), vehicleNo: blankAsAbsent(draft.vehicleNo),
        distanceKm: draft.distanceKm.trim() ? Number(draft.distanceKm) : undefined,
      },
    }), (r) => text(r.id));
  }

  actOnEwayBill(id: string, action: EwayAction, reason: string, vehicleNo: string): Promise<EwayBillRow> {
    return this.call(this.ewayApi.actOnEwayBill({
      id, ewayBillActionRequest: { action, reason: blankAsAbsent(reason), vehicleNo: blankAsAbsent(vehicleNo) },
    }), rowOf);
  }

  tripEwayPartB(tripId: string, vehicleNo: string): Promise<EwayPartBResult> {
    return this.call(this.ewayApi.updateTripEwayBillPartB({
      id: tripId.trim(), tripPartBRequest: { vehicleNo: blankAsAbsent(vehicleNo) },
    }), (r) => ({ tripId: r.tripId, vehicleNo: r.vehicleNo, updated: r.updated, items: r.items.map(itemOf) }));
  }
}
