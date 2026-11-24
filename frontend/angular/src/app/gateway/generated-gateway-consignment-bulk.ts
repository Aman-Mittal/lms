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

import type { ConsignmentItemResult } from '../api';
import type { BulkAmendItem, BulkConsignmentOutcome, ConsignmentListPage } from './consignment-bulk-types';
import { GeneratedGatewayClientBillingForms } from './generated-gateway-client-billing-forms';
import { blankAsAbsent, consignmentCard, text } from './generated-gateway-helpers';

function outcomeOf(r: ConsignmentItemResult): BulkConsignmentOutcome {
  return {
    consignmentId: text(r.consignmentId), ok: String(r.outcome) !== 'REFUSED', code: text(r.code), message: text(r.message),
    fieldErrors: Object.entries(r.errors ?? {}).map(([field, reason]) => `${field}: ${reason}`),
  };
}

/** The consignment register and the bulk amend and cancel of the consignment desk. */
export abstract class GeneratedGatewayConsignmentBulk extends GeneratedGatewayClientBillingForms {
  consignmentList(status: string, q: string, cursor?: string): Promise<ConsignmentListPage> {
    return this.call(this.loads.listConsignments({
      status: blankAsAbsent(status), q: blankAsAbsent(q), cursor: blankAsAbsent(cursor ?? ''), limit: 50,
    }), (page) => ({ rows: page.items.map(consignmentCard), nextCursor: page.hasMore ? text(page.nextCursor) : '' }));
  }

  bulkAmendConsignments(items: BulkAmendItem[]): Promise<BulkConsignmentOutcome[]> {
    return this.call(this.loads.amendConsignments({
      bulkAmendConsignmentsRequest: {
        items: items.map((i) => ({ consignmentId: i.consignmentId, version: Number(i.version), changes: i.changes })),
      },
    }), (r) => r.items.map(outcomeOf));
  }

  bulkCancelConsignments(consignmentIds: string[], reason: string): Promise<BulkConsignmentOutcome[]> {
    return this.call(this.loads.cancelConsignments({
      bulkCancelConsignmentsRequest: { consignmentIds, reason: blankAsAbsent(reason) },
    }), (r) => r.items.map(outcomeOf));
  }
}
