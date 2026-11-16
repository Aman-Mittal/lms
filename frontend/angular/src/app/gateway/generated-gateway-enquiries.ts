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
import { EnquiriesService, OrderIntakeService } from '../api';
import type {
  Enquiry, EnquiryLine, EnquiryLineOutcome, EnquiryQuote, OrderFileResponse,
} from '../api';
import type {
  EnquiryConverted, EnquiryDesk, EnquiryDraft, EnquiryFilter, EnquiryLineView, EnquiryPage, EnquiryQuoteView,
  EnquiryLineStatus, EnquiryRow, EnquiryStatus, OutcomeDraft, QuoteDraft, UploadOutcome, UploadStatus,
} from './enquiry-types';
import { blankAsAbsent, instant, text } from './generated-gateway-helpers';
import { GeneratedGatewayMoney } from './generated-gateway-money';

function quoteOf(q: EnquiryQuote): EnquiryQuoteView {
  return {
    id: q.id, revision: q.revision, amount: text(q.amount), currency: text(q.currency), validUntil: instant(q.validUntil),
    note: text(q.note), status: String(q.status) as EnquiryQuoteView['status'], revokeReason: text(q.revokeReason),
  };
}

function lineOf(l: EnquiryLine): EnquiryLineView {
  return {
    id: l.id, lineNo: l.lineNo, originCode: text(l.originCode), destinationCode: text(l.destinationCode),
    loadType: text(l.loadType), material: text(l.material), quantity: text(l.quantity), uom: l.uom,
    weightKg: text(l.weightKg), wantedPickupOn: text(l.wantedPickupOn), status: String(l.status) as EnquiryLineStatus,
    wonQuantity: text(l.wonQuantity), lostReason: text(l.lostReason),
    suggestedLaneCode: text(l.suggestedLaneCode), suggestedContractCode: text(l.suggestedContractCode),
    suggestedRateBasis: text(l.suggestedRateBasis), suggestedRate: text(l.suggestedRate),
    suggestedAmount: text(l.suggestedAmount), currency: l.currency, quotes: l.quotes.map(quoteOf),
  };
}

function rowOf(e: Enquiry): EnquiryRow {
  return {
    id: e.id, enquiryNo: e.enquiryNo, status: String(e.status) as EnquiryStatus, customerName: text(e.customerName),
    reference: text(e.reference), deadlineAt: instant(e.deadlineAt), lineCount: e.lineCount,
    wonLineCount: e.wonLineCount, orderId: text(e.orderId), orderNo: text(e.orderNo),
  };
}

/** The enquiry desk and the order file upload. */
export abstract class GeneratedGatewayEnquiries extends GeneratedGatewayMoney {
  protected readonly enquiriesApi = inject(EnquiriesService);
  protected readonly intakeApi = inject(OrderIntakeService);

  enquiries(filter: EnquiryFilter, cursor?: string): Promise<EnquiryPage> {
    return this.call(this.enquiriesApi.listEnquiries({
      status: blankAsAbsent(filter.status), q: blankAsAbsent(filter.q), cursor, limit: 50,
    }), (s) => ({ rows: s.items.map(rowOf), nextCursor: s.nextCursor ?? null }));
  }

  enquiry(id: string): Promise<EnquiryDesk> {
    return this.call(this.enquiriesApi.getEnquiry({ id }), (e) => ({
      ...rowOf(e), version: text(e.version), notes: text(e.notes), cancelReason: text(e.cancelReason),
      lines: e.lines.map(lineOf),
    }));
  }

  createEnquiry(orgUnitId: string, draft: EnquiryDraft): Promise<string> {
    return this.call(this.enquiriesApi.createEnquiry({
      createEnquiryRequest: {
        orgUnitId, customerPartnerId: draft.customerPartnerId.trim(), reference: blankAsAbsent(draft.reference),
        notes: blankAsAbsent(draft.notes),
        lines: draft.lines.map((l) => ({
          originTerminalId: l.originTerminalId.trim(), destinationTerminalId: l.destinationTerminalId.trim(),
          loadType: blankAsAbsent(l.loadType), material: blankAsAbsent(l.material), quantity: Number(l.quantity),
          uom: l.uom.trim(), weightKg: l.weightKg.trim() ? Number(l.weightKg) : undefined,
          wantedPickupOn: blankAsAbsent(l.wantedPickupOn),
        })),
      },
    }), (r) => text(r.id));
  }

  discoverEnquiryRates(id: string, version: string): Promise<void> {
    return this.call(this.enquiriesApi.discoverEnquiryRates({ id, ifMatch: version }), () => undefined);
  }

  issueEnquiryQuote(id: string, version: string, lineId: string, draft: QuoteDraft): Promise<void> {
    return this.call(this.enquiriesApi.issueEnquiryQuote({
      id, lineId, ifMatch: version, enquiryQuoteRequest: { amount: Number(draft.amount), note: blankAsAbsent(draft.note) },
    }), () => undefined);
  }

  reviseEnquiryQuote(id: string, version: string, lineId: string, draft: QuoteDraft): Promise<void> {
    return this.call(this.enquiriesApi.reviseEnquiryQuote({
      id, lineId, ifMatch: version, enquiryQuoteRequest: { amount: Number(draft.amount), note: blankAsAbsent(draft.note) },
    }), () => undefined);
  }

  revokeEnquiryQuote(id: string, version: string, lineId: string, reason: string): Promise<void> {
    return this.call(this.enquiriesApi.revokeEnquiryQuote({
      id, lineId, ifMatch: version, enquiryReasonRequest: { reason: blankAsAbsent(reason) },
    }), () => undefined);
  }

  recordEnquiryOutcome(id: string, version: string, lineId: string, draft: OutcomeDraft): Promise<void> {
    return this.call(this.enquiriesApi.recordEnquiryOutcome({
      id, lineId, ifMatch: version,
      enquiryOutcomeRequest: {
        outcome: draft.outcome as unknown as EnquiryLineOutcome, wonQuantity: draft.wonQuantity.trim() ? Number(draft.wonQuantity) : undefined,
        reason: blankAsAbsent(draft.reason),
      },
    }), () => undefined);
  }

  cancelEnquiry(id: string, version: string, reason: string): Promise<void> {
    return this.call(this.enquiriesApi.cancelEnquiry({
      id, ifMatch: version, cancelEnquiryRequest: { reason: reason.trim() },
    }), () => undefined);
  }

  convertEnquiry(id: string): Promise<EnquiryConverted> {
    return this.call(this.enquiriesApi.convertEnquiry({ id }), (r) => ({
      orderId: r.orderId, alreadyConverted: r.alreadyConverted,
    }));
  }

  uploadOrderFile(orgUnitId: string, customerPartnerId: string, file: File): Promise<UploadOutcome> {
    return this.call(this.intakeApi.uploadOrderFile({ file, orgUnitId, customerPartnerId }), (r: OrderFileResponse) => ({
      created: r.created, duplicates: r.duplicates, failed: r.failed,
      rows: r.rows.map((row) => ({
        row: row.row, orderRef: text(row.orderRef), status: String(row.status) as UploadStatus, orderId: text(row.orderId), reasons: row.reasons,
      })),
    }));
  }
}
