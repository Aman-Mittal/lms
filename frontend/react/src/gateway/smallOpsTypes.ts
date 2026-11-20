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
 * What the small API-only operations (approval policies, number series revision, order lines and close settings,
 * recording an e-way bill, route corridors) know of the platform. Every value is already text; the adapters turn the
 * generated client into these.
 */

export const APPROVAL_EXPIRIES = ['EXPIRE', 'AUTO_APPROVE'] as const;
export type ApprovalExpiryChoice = (typeof APPROVAL_EXPIRIES)[number];

/** An approval policy as the tenant has it now: its defaults and whatever the tenant set over them. */
export interface ApprovalPolicyRow {
  subjectType: string;
  required: boolean;
  /** A mandatory policy cannot be switched off: the subject happens only through an approval. */
  mandatory: boolean;
  decidePermissions: string[];
  ordered: boolean;
  allowSelfApproval: boolean;
  /** Minutes a request may stay open, blank when it never lapses. */
  dueMinutes: string;
  onExpiry: ApprovalExpiryChoice;
}

/** What a policy is set to; every value is stated, so a set always replaces the policy whole. */
export interface ApprovalPolicyDraft {
  required: boolean;
  /** Permission codes, comma or space separated, in the order they decide. */
  decidePermissions: string;
  ordered: boolean;
  allowSelfApproval: boolean;
  dueMinutes: string;
  onExpiry: ApprovalExpiryChoice;
}

/** The series as a revision starts from it: the format and the version an edit must send back. */
export interface SeriesRevisionState {
  version: string;
  issuedThisYear: string;
  draft: SeriesRevisionDraft;
}

/** A series' format and settings, restated whole. */
export interface SeriesRevisionDraft {
  prefix: string;
  yearCode: string;
  separator: string;
  counterLength: string;
  yearStartMonth: string;
  timeZone: string;
  reuseReleased: boolean;
  description: string;
}

/** The tenant's order close policy. */
export interface OrderCloseSettingsView {
  tolerancePercent: string;
  autoFulfil: boolean;
  /** False while the system defaults apply. */
  customised: boolean;
}

/** What cancelling one line of an order freed. */
export interface LineCancellationView {
  cancelledConsignments: number;
  releasedLines: number;
}

/** A bill generated outside the platform, written down against its consignment. */
export interface EwayRecordDraft {
  consignmentId: string;
  billNo: string;
  /** A local date and time, blank when unknown. */
  generatedAt: string;
  validUntil: string;
  vehicleNo: string;
  distanceKm: string;
}

/** A lane's corridor as listed. */
export interface CorridorRow {
  id: string;
  name: string;
  origin: string;
  destination: string;
  pointCount: string;
  widthMetres: string;
  updatedAt: string;
}

/** A corridor to import; points are text, one `longitude, latitude` pair per line, in the order travelled. */
export interface CorridorDraft {
  originTerminalId: string;
  destinationTerminalId: string;
  name: string;
  points: string;
  widthMetres: string;
}
