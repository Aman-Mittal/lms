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

import {
  Configuration, EnquiriesApi, OrderIntakeApi, type Enquiry, type EnquiryLine, type EnquiryLineOutcome, type EnquiryQuote,
} from '../api';
import type { LmsGateway } from './lmsGateway';
import type {
  EnquiryDesk, EnquiryLineView, EnquiryQuoteView, EnquiryRow, EnquiryStatus,
} from './enquiryTypes';
import { blankAsAbsent, guard, text } from './gatewayShared';

type EnquiryVerbs = Pick<LmsGateway, 'enquiries' | 'enquiry' | 'createEnquiry' | 'discoverEnquiryRates'
  | 'issueEnquiryQuote' | 'reviseEnquiryQuote' | 'revokeEnquiryQuote' | 'recordEnquiryOutcome' | 'cancelEnquiry'
  | 'convertEnquiry' | 'uploadOrderFile'>;

function quoteOf(q: EnquiryQuote): EnquiryQuoteView {
  return {
    id: q.id, revision: q.revision, amount: text(q.amount), currency: text(q.currency), validUntil: text(q.validUntil),
    note: text(q.note), status: q.status, revokeReason: text(q.revokeReason),
  };
}

function lineOf(l: EnquiryLine): EnquiryLineView {
  return {
    id: l.id, lineNo: l.lineNo, originCode: text(l.originCode), destinationCode: text(l.destinationCode),
    loadType: text(l.loadType), material: text(l.material), quantity: text(l.quantity), uom: l.uom,
    weightKg: text(l.weightKg), wantedPickupOn: text(l.wantedPickupOn), status: l.status,
    wonQuantity: text(l.wonQuantity), lostReason: text(l.lostReason),
    suggestedLaneCode: text(l.suggestedLaneCode), suggestedContractCode: text(l.suggestedContractCode),
    suggestedRateBasis: text(l.suggestedRateBasis), suggestedRate: text(l.suggestedRate),
    suggestedAmount: text(l.suggestedAmount), currency: l.currency, quotes: l.quotes.map(quoteOf),
  };
}

function rowOf(e: Enquiry): EnquiryRow {
  return {
    id: e.id, enquiryNo: e.enquiryNo, status: e.status as EnquiryStatus, customerName: text(e.customerName),
    reference: text(e.reference), deadlineAt: text(e.deadlineAt), lineCount: e.lineCount,
    wonLineCount: e.wonLineCount, orderId: text(e.orderId), orderNo: text(e.orderNo),
  };
}

/** A calendar day typed into a date field, as the date the API means: midnight UTC of that day. */
function dayOrAbsent(value: string): Date | undefined {
  const trimmed = value.trim();
  return trimmed ? new Date(`${trimmed}T00:00:00Z`) : undefined;
}

/** The enquiry desk and the order file upload. */
export function enquiryVerbs(config: Configuration): EnquiryVerbs {
  const enquiries = new EnquiriesApi(config);
  const intake = new OrderIntakeApi(config);

  return {
    enquiries: (filter, cursor) =>
      guard(async () => {
        const page = await enquiries.listEnquiries({
          status: blankAsAbsent(filter.status), q: blankAsAbsent(filter.q), cursor, limit: 50,
        });
        return { rows: page.items.map(rowOf), nextCursor: page.nextCursor ?? null };
      }),

    enquiry: (id): Promise<EnquiryDesk> =>
      guard(async () => {
        const e = await enquiries.getEnquiry({ id });
        return {
          ...rowOf(e), version: text(e.version), notes: text(e.notes), cancelReason: text(e.cancelReason),
          lines: e.lines.map(lineOf),
        };
      }),

    createEnquiry: (orgUnitId, draft) =>
      guard(async () => text((await enquiries.createEnquiry({
        createEnquiryRequest: {
          orgUnitId, customerPartnerId: draft.customerPartnerId.trim(), reference: blankAsAbsent(draft.reference),
          notes: blankAsAbsent(draft.notes),
          lines: draft.lines.map((l) => ({
            originTerminalId: l.originTerminalId.trim(), destinationTerminalId: l.destinationTerminalId.trim(),
            loadType: blankAsAbsent(l.loadType), material: blankAsAbsent(l.material), quantity: Number(l.quantity),
            uom: l.uom.trim(), weightKg: l.weightKg.trim() ? Number(l.weightKg) : undefined,
            wantedPickupOn: dayOrAbsent(l.wantedPickupOn),
          })),
        },
      })).id)),

    discoverEnquiryRates: (id, version) =>
      guard(async () => {
        await enquiries.discoverEnquiryRates({ id, ifMatch: version });
      }),

    issueEnquiryQuote: (id, version, lineId, draft) =>
      guard(async () => {
        await enquiries.issueEnquiryQuote({
          id, lineId, ifMatch: version, enquiryQuoteRequest: { amount: Number(draft.amount), note: blankAsAbsent(draft.note) },
        });
      }),

    reviseEnquiryQuote: (id, version, lineId, draft) =>
      guard(async () => {
        await enquiries.reviseEnquiryQuote({
          id, lineId, ifMatch: version, enquiryQuoteRequest: { amount: Number(draft.amount), note: blankAsAbsent(draft.note) },
        });
      }),

    revokeEnquiryQuote: (id, version, lineId, reason) =>
      guard(async () => {
        await enquiries.revokeEnquiryQuote({
          id, lineId, ifMatch: version, enquiryReasonRequest: { reason: blankAsAbsent(reason) },
        });
      }),

    recordEnquiryOutcome: (id, version, lineId, draft) =>
      guard(async () => {
        await enquiries.recordEnquiryOutcome({
          id, lineId, ifMatch: version,
          enquiryOutcomeRequest: {
            outcome: draft.outcome as EnquiryLineOutcome, wonQuantity: draft.wonQuantity.trim() ? Number(draft.wonQuantity) : undefined,
            reason: blankAsAbsent(draft.reason),
          },
        });
      }),

    cancelEnquiry: (id, version, reason) =>
      guard(async () => {
        await enquiries.cancelEnquiry({ id, ifMatch: version, cancelEnquiryRequest: { reason: reason.trim() } });
      }),

    convertEnquiry: (id) =>
      guard(async () => {
        const r = await enquiries.convertEnquiry({ id });
        return { orderId: r.orderId, alreadyConverted: r.alreadyConverted };
      }),

    uploadOrderFile: (orgUnitId, customerPartnerId, file) =>
      guard(async () => {
        const r = await intake.uploadOrderFile({ file, orgUnitId, customerPartnerId });
        return {
          created: r.created, duplicates: r.duplicates, failed: r.failed,
          rows: r.rows.map((row) => ({
            row: row.row, orderRef: text(row.orderRef), status: row.status, orderId: text(row.orderId),
            reasons: row.reasons,
          })),
        };
      }),
  };
}
