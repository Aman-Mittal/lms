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

import { PlanningApi, type ConsignmentItemResult, type ConsignmentAmendmentRequest, type Configuration } from '../api';
import type { LmsGateway } from './lmsGateway';
import type { BulkConsignmentOutcome } from './consignmentBulkTypes';
import { blankAsAbsent, consignmentCard, guard, text } from './gatewayShared';

type BulkVerbs = Pick<LmsGateway, 'consignmentList' | 'bulkAmendConsignments' | 'bulkCancelConsignments'>;

function outcomeOf(r: ConsignmentItemResult): BulkConsignmentOutcome {
  return {
    consignmentId: text(r.consignmentId), ok: String(r.outcome) !== 'REFUSED', code: text(r.code), message: text(r.message),
    fieldErrors: Object.entries(r.errors ?? {}).map(([field, reason]) => `${field}: ${reason}`),
  };
}

/** The consignment register and the bulk amend and cancel of the consignment desk. */
export function consignmentBulkVerbs(config: Configuration): BulkVerbs {
  const planning = new PlanningApi(config);

  return {
    consignmentList: (status, q, cursor) =>
      guard(async () => {
        const page = await planning.listConsignments({
          status: blankAsAbsent(status), q: blankAsAbsent(q), cursor: blankAsAbsent(cursor ?? ''), limit: 50,
        });
        return { rows: page.items.map(consignmentCard), nextCursor: page.hasMore ? text(page.nextCursor) : '' };
      }),

    bulkAmendConsignments: (items) =>
      guard(async () => (await planning.amendConsignments({
        bulkAmendConsignmentsRequest: {
          items: items.map((i) => ({
            consignmentId: i.consignmentId, version: Number(i.version), changes: i.changes as ConsignmentAmendmentRequest,
          })),
        },
      })).items.map(outcomeOf)),

    bulkCancelConsignments: (consignmentIds, reason) =>
      guard(async () => (await planning.cancelConsignments({
        bulkCancelConsignmentsRequest: { consignmentIds, reason: blankAsAbsent(reason) },
      })).items.map(outcomeOf)),
  };
}
