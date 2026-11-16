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

/*
 * What the e-way bill desk knows of the platform. Every value is already text (a missing one is blank, a
 * time is formatted for display); the adapter (generated-gateway-eway.ts) turns the generated client into these.
 */

export const EWAY_STATUSES = ['ACTIVE', 'EXTENDED', 'CANCELLED', 'EXPIRED'] as const;
export type EwayStatus = (typeof EWAY_STATUSES)[number];

/** What a person can do to a bill that already exists; generating belongs to the consignment. */
export type EwayAction = 'PART_B' | 'EXTEND' | 'CANCEL' | 'CHANGE_VEHICLE';

/** An e-way bill with the consignment it covers. */
export interface EwayBillRow {
  id: string;
  billNo: string;
  status: EwayStatus;
  consignmentId: string;
  trackingRef: string;
  generatedAt: string;
  validUntil: string;
  /** The vehicle on Part-B; blank until Part-B is updated. */
  vehicleNo: string;
  distanceKm: string;
  /** The adapter that issued it, or MANUAL for a bill keyed from the portal. */
  source: string;
  extensionCount: number;
  cancelReason: string;
  /** True while the expiry sweep has flagged the live bill as close to lapsing. */
  expiringSoon: boolean;
}

export interface EwayBillPage {
  rows: EwayBillRow[];
  nextCursor: string | null;
}

export interface EwayFilter {
  status: string;
  q: string;
}

export interface EwayGenerateDraft {
  consignmentId: string;
  documentRef: string;
  distanceKm: string;
  vehicleNo: string;
}

/** What became of one consignment of a trip's Part-B update. */
export interface EwayItemOutcome {
  consignmentId: string;
  trackingRef: string;
  billNo: string;
  outcome: 'UPDATED' | 'REFUSED' | 'SKIPPED' | 'ERROR';
  /** A stable problem code for anything but UPDATED. */
  code: string;
  message: string;
}

export interface EwayPartBResult {
  tripId: string;
  vehicleNo: string;
  updated: number;
  items: EwayItemOutcome[];
}
