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

import type { ConsignmentCard, ConsignmentParticularsDraft } from './lms-gateway';

/** The particulars a bulk amendment changes: only the fields named; an empty string clears one. */
export type ConsignmentChanges = Partial<ConsignmentParticularsDraft>;

/** One consignment to amend: the version its caller read, and what to change. */
export interface BulkAmendItem {
  consignmentId: string;
  version: string;
  changes: ConsignmentChanges;
}

/** What became of one item of a bulk amendment or cancellation; `ok` is false for a refusal. */
export interface BulkConsignmentOutcome {
  consignmentId: string;
  ok: boolean;
  /** The refusal's code, such as `concurrent-modification`; empty when it went through. */
  code: string;
  message: string;
  /** A refused amendment's reasons, one line per field: "field: reason". */
  fieldErrors: string[];
}

/** One page of the consignment register, every state, by tracking reference. */
export interface ConsignmentListPage {
  rows: ConsignmentCard[];
  /** Pass to read the next page; empty on the last. */
  nextCursor: string;
}
