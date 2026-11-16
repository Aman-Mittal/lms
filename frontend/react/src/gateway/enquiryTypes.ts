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
 * What the enquiry desk and the order upload screen know of the platform. Every amount and quantity is
 * already text, a missing value is blank, and a dated value is formatted for display; the adapter
 * (gatewayEnquiries.ts) turns the generated client into these.
 */

export const ENQUIRY_STATUSES = ['OPEN', 'WON', 'PARTIAL', 'LOST', 'CONVERTED', 'CANCELLED'] as const;
export type EnquiryStatus = (typeof ENQUIRY_STATUSES)[number];

export type EnquiryLineStatus = 'OPEN' | 'WON' | 'PARTIAL' | 'LOST' | 'CANCELLED';
export type EnquiryOutcome = 'WON' | 'LOST' | 'PARTIAL';

/** One revision of a quote; ISSUED is the one the customer holds. */
export interface EnquiryQuoteView {
  id: string;
  revision: number;
  amount: string;
  currency: string;
  validUntil: string;
  note: string;
  status: 'ISSUED' | 'SUPERSEDED' | 'REVOKED';
  revokeReason: string;
}

export interface EnquiryLineView {
  id: string;
  lineNo: number;
  originCode: string;
  destinationCode: string;
  loadType: string;
  material: string;
  quantity: string;
  uom: string;
  weightKg: string;
  wantedPickupOn: string;
  status: EnquiryLineStatus;
  wonQuantity: string;
  lostReason: string;
  /** The contract lane a rate was suggested from; blank when none matched. */
  suggestedLaneCode: string;
  suggestedContractCode: string;
  suggestedRateBasis: string;
  suggestedRate: string;
  suggestedAmount: string;
  currency: string;
  quotes: EnquiryQuoteView[];
}

export interface EnquiryRow {
  id: string;
  enquiryNo: string;
  status: EnquiryStatus;
  customerName: string;
  reference: string;
  deadlineAt: string;
  lineCount: number;
  wonLineCount: number;
  orderId: string;
  orderNo: string;
}

/** An enquiry as read; `version` is what every change sends back as `If-Match`. */
export interface EnquiryDesk extends EnquiryRow {
  version: string;
  notes: string;
  cancelReason: string;
  lines: EnquiryLineView[];
}

export interface EnquiryPage {
  rows: EnquiryRow[];
  nextCursor: string | null;
}

export interface EnquiryFilter {
  status: string;
  q: string;
}

export interface EnquiryLineDraft {
  originTerminalId: string;
  destinationTerminalId: string;
  loadType: string;
  material: string;
  quantity: string;
  uom: string;
  weightKg: string;
  wantedPickupOn: string;
}

export interface EnquiryDraft {
  customerPartnerId: string;
  reference: string;
  notes: string;
  lines: EnquiryLineDraft[];
}

export interface QuoteDraft {
  amount: string;
  note: string;
}

export interface OutcomeDraft {
  outcome: EnquiryOutcome;
  wonQuantity: string;
  reason: string;
}

export interface EnquiryConverted {
  orderId: string;
  alreadyConverted: boolean;
}

export type UploadStatus = 'CREATED' | 'DUPLICATE' | 'FAILED';

/** What became of one data row of an order file; `row` is its number in the file, the header being row 1. */
export interface UploadRow {
  row: number;
  orderRef: string;
  status: UploadStatus;
  orderId: string;
  reasons: string[];
}

export interface UploadOutcome {
  created: number;
  duplicates: number;
  failed: number;
  rows: UploadRow[];
}
