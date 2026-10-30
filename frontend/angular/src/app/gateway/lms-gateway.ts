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
 * The console's view of the platform: the only API the screens know.
 *
 * ADAPTER PATTERN. The generated client is shaped by the OpenAPI contract --
 * optional fields, enums, one class per tag. Screens should not be: they want
 * display-ready values and a handful of verbs. This interface is that shape,
 * and one adapter (the generated gateway) translates the generated client into
 * it. Regenerate the client after a contract change and only the adapter can
 * break; swap in a fake gateway and every screen can be tested without a server.
 */

export interface Credentials {
  tenantCode: string;
  email: string;
  password: string;
}

export interface SignedIn {
  token: string;
  orgUnitId: string;
  /** What the caller may do; screens hide controls the caller could not use. */
  permissions: string[];
}

/** Every count is a real number; the adapter has already defaulted the contract's optionals. */
export interface ControlTowerSummary {
  openP1: number;
  openP2: number;
  openP3: number;
  unassigned: number;
  settlementHolds: number;
  openWithoutTrip: number;
}

export const RESOURCE_KEYS = [
  'partners', 'vehicles', 'drivers', 'terminals', 'orders', 'loads', 'trips', 'freight-bills',
  'exceptions', 'claims',
] as const;
export type ResourceKey = (typeof RESOURCE_KEYS)[number];

/** One table row, every cell already formatted for display. `id` is always present. */
export type Row = Record<string, string>;

export const PARTNER_TYPES = ['CUSTOMER', 'VENDOR', 'BROKER', 'CONSIGNEE'] as const;
export type PartnerType = (typeof PARTNER_TYPES)[number];

export interface PartnerDraft {
  code: string;
  legalName: string;
  partnerType: PartnerType;
}

// ---------------------------------------------------------------- custom fields

/** The kinds of record a tenant may add its own fields to. */
export const CUSTOM_FIELD_KINDS = [
  'PARTNER', 'VEHICLE', 'DRIVER', 'TERMINAL', 'ORDER', 'LOAD', 'TRIP',
] as const;
export type CustomFieldKind = (typeof CUSTOM_FIELD_KINDS)[number];

export const FIELD_TYPES = ['TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'SELECT'] as const;
export type FieldType = (typeof FIELD_TYPES)[number];

export interface FieldDefinition {
  id: string;
  kind: CustomFieldKind;
  key: string;
  label: string;
  type: FieldType;
  required: boolean;
  options: string[];
  active: boolean;
}

export interface FieldDraft {
  kind: CustomFieldKind;
  key: string;
  label: string;
  type: FieldType;
  required: boolean;
  options: string[];
}

export interface FieldChanges {
  label: string;
  required: boolean;
  options: string[];
  active: boolean;
}

export type CustomValue = string | number | boolean;
/** Field key to value. A field with no value is simply absent. */
export type CustomValues = Record<string, CustomValue>;

// ---------------------------------------------------------------- delivery and claims

export const CLAIM_TYPES = ['DAMAGE', 'SHORTAGE', 'LOSS', 'DELAY'] as const;
export type ClaimType = (typeof CLAIM_TYPES)[number];

/** Where a claim may be moved to; OPEN is only ever where it starts. */
export type ClaimTarget = 'UNDER_REVIEW' | 'APPROVED' | 'REJECTED' | 'SETTLED';

export interface ClaimDraft {
  consignmentId: string;
  claimType: ClaimType;
  amount: number;
  description: string;
}

export interface ClaimMove {
  target: ClaimTarget;
  /** Required to approve. */
  approvedAmount?: number;
  /** Required to reject. */
  resolutionNote?: string;
}

export interface OtpIssued {
  expiresAt: string;
  /** Present only where the deployment hands the code back (a demo). */
  otp?: string;
}

export interface ProofDraft {
  otp: string;
  recipientName: string;
  photoRef?: string;
}

/** A consignment's proof of delivery, every cell already formatted for display. */
export interface DeliveryProofView {
  consignmentId: string;
  status: 'OTP_ISSUED' | 'CONFIRMED';
  recipientName: string;
  photoRef: string;
  deliveredAt: string;
  otpExpiresAt: string;
}

// ---------------------------------------------------------------- tracking links

/** A link as it is issued: the token is shown here and nowhere else. */
export interface IssuedTrackingLink {
  id: string;
  token: string;
  expiresAt: string;
}

/** A link as it is listed. There is no token: only its hash was ever kept. */
export interface TrackingLinkView {
  id: string;
  createdAt: string;
  expiresAt: string;
  revokedAt: string;
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED';
}

/** What anyone holding a tracking link may see. */
export interface PublicTrackingView {
  reference: string;
  status: string;
  originName: string;
  destinationName: string;
  requestedDeliveryAt: string;
  deliveredAt: string;
  proofRecorded: boolean;
  /** The last reported position while in transit, to two decimals; null otherwise. */
  position: { lat: string; lon: string; recordedAt: string } | null;
}

/** What a demo reset did. */
export interface DemoResetOutcome {
  tenantCode: string;
  rowsDeleted: number;
  /** True when the caller's own user was recreated: the session in hand should end. */
  signInAgain: boolean;
}

/** The one error screens handle: a message fit to show, and the HTTP status if there was one. */
export class ApiProblem extends Error {
  constructor(message: string, readonly status?: number) {
    super(message);
    this.name = 'ApiProblem';
  }
}

export abstract class LmsGateway {
  abstract signIn(credentials: Credentials): Promise<SignedIn>;
  abstract summary(): Promise<ControlTowerSummary>;
  /**
   * Puts the demo tenant back to its seeded state (holders of DEMO_RESET, demo deployments only).
   * `confirm` is the demo tenant's code, typed by the person resetting it.
   */
  abstract resetDemoData(confirm: string): Promise<DemoResetOutcome>;
  abstract list(resource: ResourceKey): Promise<Row[]>;
  abstract registerPartner(orgUnitId: string, draft: PartnerDraft): Promise<void>;

  /** Every definition of a kind, retired ones included, or of every kind. */
  abstract listFields(kind?: CustomFieldKind): Promise<FieldDefinition[]>;
  abstract defineField(draft: FieldDraft): Promise<void>;
  abstract reviseField(id: string, changes: FieldChanges): Promise<void>;
  abstract customValues(kind: CustomFieldKind, recordId: string): Promise<CustomValues>;
  /** Replaces the record's values; resolves to what was stored. */
  abstract saveCustomValues(kind: CustomFieldKind, recordId: string, values: CustomValues): Promise<CustomValues>;

  /** Resolves to the new claim's id. */
  abstract raiseClaim(draft: ClaimDraft): Promise<string>;
  abstract transitionClaim(id: string, move: ClaimMove): Promise<void>;
  abstract issueDeliveryOtp(consignmentId: string): Promise<OtpIssued>;
  abstract recordDeliveryProof(consignmentId: string, draft: ProofDraft): Promise<void>;
  /** Null when no code has been issued for the consignment yet. */
  abstract deliveryProof(consignmentId: string): Promise<DeliveryProofView | null>;

  /** Shown once: the token in the result cannot be read again. */
  abstract issueTrackingLink(consignmentId: string, expiresInDays?: number): Promise<IssuedTrackingLink>;
  abstract trackingLinks(consignmentId: string): Promise<TrackingLinkView[]>;
  abstract revokeTrackingLink(id: string): Promise<void>;
  /** Needs no sign-in. Null when the token is unknown, expired or revoked (they look alike). */
  abstract publicTracking(token: string): Promise<PublicTrackingView | null>;
}
