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

// ---------------------------------------------------------------- allocation rules

export const RULE_ACTIONS = ['PREFER', 'EXCLUDE'] as const;
export type RuleAction = (typeof RULE_ACTIONS)[number];

/** The kinds of goods a rule can be limited to. */
export const MATERIAL_CLASSES = [
  'GENERAL', 'FOOD', 'PHARMA', 'CHEMICAL', 'TOXIC', 'FLAMMABLE', 'FRAGILE', 'TEMPERATURE_CONTROLLED',
] as const;

/** Something a picker offers: what is sent, and what is shown. */
export interface Choice {
  id: string;
  label: string;
}

/** A rule as listed. Conditions left out are empty strings; weights are formatted for display. */
export interface AllocationRuleView {
  id: string;
  name: string;
  priority: number;
  active: boolean;
  originTerminalId: string;
  destinationTerminalId: string;
  minWeightKg: string;
  maxWeightKg: string;
  materialClass: string;
  action: RuleAction;
  /** In the order the rule names them, which for PREFER is the order they are asked. */
  vendors: Choice[];
}

/** A whole rule, as written or revised. Every condition left out is not tested. */
export interface RuleDraft {
  name: string;
  priority: number;
  active: boolean;
  originTerminalId?: string;
  destinationTerminalId?: string;
  minWeightKg?: number;
  maxWeightKg?: number;
  materialClass?: string;
  action: RuleAction;
  vendorIds: string[];
}

/** A dry run: which rule would fire for a load and who would be asked, changing nothing. */
export interface RuleEvaluation {
  loadNo: string;
  /** Empty when no rule would fire. */
  ruleName: string;
  action: RuleAction | '';
  nextVendor: string;
  candidates: Choice[];
}

/** An allocation as the rules screen shows it: what decided who was asked. */
export interface AllocationSummary {
  id: string;
  loadNo: string;
  status: string;
  /** Empty when no rule fired. */
  appliedRuleName: string;
  appliedRuleAction: RuleAction | '';
}

// ---------------------------------------------------------------- maps

/** A point on the map, in degrees. */
export interface GeoPoint {
  lat: number;
  lon: number;
}

/**
 * The kinds of thing a map draws. A `site` is a point with a geofence radius, an
 * `area` a polygon geofence; legs, tracks and stops are lines and points along a
 * movement.
 */
export type MapFeatureKind =
  | 'site' | 'area' | 'vehicle' | 'track' | 'position' | 'leg-ROAD' | 'leg-RAIL' | 'stop' | 'plant';

/** One thing drawn on a map, already in map coordinates. */
export interface MapFeature {
  id: string;
  kind: MapFeatureKind;
  label: string;
  /** One point for a marker, two or more for a line or polygon. */
  points: GeoPoint[];
  /** For a site: its geofence radius in metres. */
  radiusM?: number;
}

/** A list and the map of the same rows. */
export interface Mapped {
  rows: Row[];
  features: MapFeature[];
}

/** A trip's route: its track so far and its last known position. */
export interface TripRoute {
  tripNo: string;
  status: string;
  originCode: string;
  destinationCode: string;
  /** Empty until positions have been reported. */
  lastFixAt: string;
  completionPct: string;
  features: MapFeature[];
}

/** One record with its parts and their map, as the multimodal and milk-run screens show it. */
export interface MappedDetail {
  header: Row;
  parts: Row[];
  features: MapFeature[];
}

// ---------------------------------------------------------------- LTL rating

export const RATE_BASES = ['PER_KG', 'PER_CFT'] as const;
export type RateBasis = (typeof RATE_BASES)[number];

/** A zone-to-zone rate card as written. Optional charges left out are not charged. */
export interface RateCardDraft {
  code: string;
  originZone: string;
  destinationZone: string;
  /** `yyyy-mm-dd`. */
  effectiveFrom: string;
  rateBasis: RateBasis;
  rateAmount: number;
  kgPerCft: number;
  minimumChargeableWeightKg?: number;
  docketCharge?: number;
}

// ---------------------------------------------------------------- event dead-letter

export const PARKED_STATUSES = ['PARKED', 'REPLAYED', 'DISCARDED'] as const;
export type ParkedStatus = (typeof PARKED_STATUSES)[number];

/** What narrows the parked-event list. Anything left out is not filtered on. */
export interface ParkedFilter {
  status?: ParkedStatus;
  eventType?: string;
  listenerId?: string;
}

/** A parked event in full: its row, and the event as it was published. */
export interface ParkedEventDetail {
  summary: Row;
  payload: string;
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

  /** Every rule, active and retired, in the order they are tried. */
  abstract allocationRules(): Promise<AllocationRuleView[]>;
  /** Resolves to the new rule's id. */
  abstract createAllocationRule(draft: RuleDraft): Promise<string>;
  /** Replaces the whole rule; `active: false` retires it. */
  abstract reviseAllocationRule(id: string, draft: RuleDraft): Promise<void>;
  abstract evaluateAllocation(loadId: string): Promise<RuleEvaluation>;
  abstract allocation(id: string): Promise<AllocationSummary>;
  /** The vendors a rule may name: active vendors only. */
  abstract activeVendors(): Promise<Choice[]>;
  abstract terminalChoices(): Promise<Choice[]>;

  /** Sites and their geofences, and every vehicle's last reported position. */
  abstract networkMap(): Promise<Mapped>;
  /** A trip's track and last position. Rejects with a 404 problem for a trip that does not exist. */
  abstract tripRoute(tripId: string): Promise<TripRoute>;
  abstract itineraries(): Promise<Row[]>;
  /** The itinerary, its legs in order, and the legs drawn between their terminals. */
  abstract itinerary(id: string): Promise<MappedDetail>;
  abstract milkRunRoutes(): Promise<Row[]>;
  /** The route, its stops in order from the plant, and the circuit drawn on the map. */
  abstract milkRunRoute(id: string): Promise<MappedDetail>;
  /** The runs generated from a route, newest first. */
  abstract circuitRuns(routeCode: string): Promise<Row[]>;

  /** Docket receivables, one page in docket-number order. */
  abstract ltlReceivables(): Promise<Row[]>;
  /** Puts a terminal in an LTL zone, replacing any earlier placement. */
  abstract assignLtlZone(terminalId: string, zoneCode: string, outOfDeliveryArea: boolean): Promise<void>;
  /** Resolves to the new rate card's id. */
  abstract publishLtlRateCard(draft: RateCardDraft): Promise<string>;

  abstract parkedEvents(filter: ParkedFilter): Promise<Row[]>;
  abstract parkedEvent(id: string): Promise<ParkedEventDetail>;
  /** `REPLAYED` when the listener completed, `PARKED_AGAIN` when it failed once more. */
  abstract replayParkedEvent(id: string): Promise<{ outcome: string; attempts: number }>;
  /** A reason is required; the event is kept, marked discarded. */
  abstract discardParkedEvent(id: string, reason: string): Promise<void>;
}
