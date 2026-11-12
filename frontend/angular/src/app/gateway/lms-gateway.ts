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
  /** Omitted is CLEAN. Anything else needs a remark. */
  goodsCondition?: GoodsCondition;
  remark?: string;
}

/** The state the goods were received in; anything but CLEAN holds the carrier's settlement. */
export const GOODS_CONDITIONS = ['CLEAN', 'SHORTAGE', 'DAMAGE'] as const;
export type GoodsCondition = (typeof GOODS_CONDITIONS)[number];

/** A consignment's proof of delivery, every cell already formatted for display. */
export interface DeliveryProofView {
  consignmentId: string;
  status: 'OTP_ISSUED' | 'CONFIRMED' | 'REVERSED';
  recipientName: string;
  photoRef: string;
  deliveredAt: string;
  otpExpiresAt: string;
  /** CLEAN, SHORTAGE or DAMAGE. */
  goodsCondition: string;
  /** What was short or damaged; empty for a clean delivery. */
  remark: string;
}

// ---------------------------------------------------------------- consignments

/** A consignment as the consignment desk shows it, every cell formatted for display. */
export interface ConsignmentCard {
  id: string;
  trackingRef: string;
  status: string;
  orderNo: string;
  consigneeName: string;
  destinationTerminalCode: string;
  deadWeightKg: string;
  volumeM3: string;
  chargeableWeightKg: string;
  loadNo: string;
  freightMode: string;
  /** For a part of a split consignment: the consignment it is a part of; empty otherwise. */
  parentId: string;
  parentTrackingRef: string;
  /** For a SPLIT consignment: how many parts, and how many delivered or cancelled; null otherwise. */
  partCount: number | null;
  partsDelivered: number | null;
  partsCancelled: number | null;
  /** OTP_ISSUED or CONFIRMED; empty before a code has been issued. */
  proofStatus: string;
  /** CLEAN, SHORTAGE or DAMAGE once a proof exists; empty before. */
  proofCondition: string;
  /** The warehouse handling unit carried, for one handed off by a warehouse; empty otherwise. */
  handlingUnitNo: string;
  /** The version read: what an amendment sends back as If-Match. */
  version: string;
  /** The particulars below are empty until stated; the window is ISO-8601 instants. */
  contactName: string;
  contactPhone: string;
  consigneeReference: string;
  clientReference: string;
  deliveryWindowFrom: string;
  deliveryWindowTo: string;
  remarks: string;
}

/**
 * What a planner may say about a consignment after it is cut: every field is sent, and an empty
 * one is cleared. The window is two ISO-8601 instants, such as 2026-12-01T06:00:00Z.
 */
export interface ConsignmentParticularsDraft {
  contactName: string;
  contactPhone: string;
  consigneeReference: string;
  clientReference: string;
  deliveryWindowFrom: string;
  deliveryWindowTo: string;
  remarks: string;
}

/** One part of a split as the planner states it, in kilograms and cubic metres. */
export interface SplitPartDraft {
  deadWeightKg: string;
  volumeM3: string;
}

/** A part as the split made it. */
export interface SplitPart {
  id: string;
  trackingRef: string;
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
  /** For a consignment split across vehicles: parts being carried, and how many delivered; null otherwise. */
  partsTotal: number | null;
  partsDelivered: number | null;
}

/** What a demo reset did. */
export interface DemoResetOutcome {
  tenantCode: string;
  rowsDeleted: number;
  /** True when the caller's own user was recreated: the session in hand should end. */
  signInAgain: boolean;
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

// ---------------------------------------------------------------- approvals

export const APPROVAL_STATUSES = ['REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'] as const;
export type ApprovalStatusName = (typeof APPROVAL_STATUSES)[number];
/** `MINE_TO_DECIDE`: open requests the caller could approve now. */
export const APPROVAL_SCOPES = ['MINE_TO_DECIDE', 'REQUESTED_BY_ME', 'ALL'] as const;
export type ApprovalScopeName = (typeof APPROVAL_SCOPES)[number];

/** What narrows the approvals inbox. Anything left out is not filtered on. */
export interface ApprovalFilter {
  status?: ApprovalStatusName;
  scope?: ApprovalScopeName;
  subjectType?: string;
  subjectId?: string;
}

/** One request in full: its row, the policy it is decided under, and its trail. */
export interface ApprovalDetail {
  summary: Row;
  /** The version a decision sends back as If-Match. */
  version: number;
  open: boolean;
  mineToDecide: boolean;
  requestedByMe: boolean;
  /** The frozen payload, as readable `key: value` lines. */
  payload: string;
  /** The policy copied onto the request, in one line. */
  policy: string;
  trail: Row[];
}

/** A request raised in place of the action asked for. */
export interface ApprovalPending {
  id: string;
  status: string;
}

/** One contract version in full, as the lifecycle panel shows, edits and acts on it. */
export interface ContractDetail {
  id: string;
  code: string;
  clientPartnerId: string;
  orgUnitId: string;
  /** Stored: DRAFT, PENDING_VERIFICATION or ACTIVE. */
  status: string;
  /** What to show: the status, or EXPIRED for a version in force past its end date. */
  state: string;
  effectiveFrom: string;
  effectiveTo: string;
  validTill: string;
  /** Why verification was last refused, while the contract is back in DRAFT. */
  rejectionReason: string;
  /** What an edit or a request sends back as `If-Match`. */
  version: string;
  currency: string;
  transportRatePerKg: string;
  transportMinimumCharge: string;
  storageRatePerPalletDay: string;
  handlingInRatePerPallet: string;
  handlingOutRatePerPallet: string;
  transportTaxRatePct: string;
  warehouseTaxRatePct: string;
  storageUnitsPerPallet: number;
  billOnDispatch: boolean;
  services: Array<{ serviceCode: string; unit: string; rate: string }>;
  /** Whether an order no lane serves is accepted; false refuses it by name. */
  directOrdersAllowed: boolean;
  /** The load or vehicle types the contract covers; empty is any. */
  allowedLoadTypes: string[];
  /** Part of the version: edited while a draft, frozen once it is not. */
  lanes: ContractLaneDraft[];
}

/** The rate bases of a lane and of its weight slabs. */
export const LANE_RATE_BASES = ['PER_KG', 'PER_TRIP', 'PER_TONNE'] as const;

/**
 * A lane as typed or restated: an end is a terminal id or a zone code (the other left blank), a
 * blank mode or load type is any, and the weight slabs are one text, `min-max:BASIS:rate`
 * separated by semicolons (a blank max is the open top band). Consignee ids are comma separated.
 */
export interface ContractLaneDraft {
  laneCode: string;
  originTerminalId: string;
  originZone: string;
  destinationTerminalId: string;
  destinationZone: string;
  freightMode: string;
  loadType: string;
  rateBasis: string;
  rate: string;
  transitDays: string;
  slabs: string;
  consigneeIds: string;
}

/** A lane of the contract in force, as the order desk offers it. */
export interface ContractLaneChoice {
  id: string;
  laneCode: string;
  originTerminalId: string;
  destinationTerminalId: string;
  freightMode: string;
  loadType: string;
  transitDays: string;
  /** The lane in one line: code, places, mode, load type and days. */
  label: string;
}

/** The terms a person types for a draft; a blank rate is left to the server's default of none. */
export interface ContractDraft {
  /** Left blank, the next number of the tenant's CONTRACT series. */
  code: string;
  effectiveFrom: string;
  validTill: string;
  transportRatePerKg: string;
  transportMinimumCharge: string;
  storageRatePerPalletDay: string;
  handlingInRatePerPallet: string;
  handlingOutRatePerPallet: string;
}

/** One completeness rule, whether the tenant requires it and whether the draft passes it. */
export interface ContractChecklistRow {
  code: string;
  required: boolean;
  passed: boolean;
  message: string;
}

/** A value-added charge to add to a contract in force from a day. */
export interface ContractChargeDraft {
  serviceCode: string;
  unit: string;
  rate: string;
  effectiveFrom: string;
  reason: string;
}

// ---------------------------------------------------------------- webhooks

/** What a subscription can ask for. `PING` is what a test ping sends and cannot be subscribed to. */
export const WEBHOOK_EVENT_TYPES = [
  'ORDER_VALIDATED',
  'ORDER_RAISED', 'ORDER_AMENDED', 'ORDER_CANCELLED', 'ORDER_FULFILLED', 'ORDER_CLOSED',
  'CONSIGNMENTS_GENERATED', 'CONSIGNMENT_SPLIT', 'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_CANCELLED',
  'DELIVERY_PROOF_RECORDED', 'CLAIM_RAISED', 'CLAIM_STATUS_CHANGED',
  'CONSIGNMENT_IN_TRANSIT', 'CONSIGNMENT_DELIVERED', 'CONSIGNMENT_CANCELLED',
  'APPROVAL_REQUESTED', 'APPROVAL_DECIDED',
] as const;
export type WebhookEventType = (typeof WEBHOOK_EVENT_TYPES)[number];

/** A subscription as a screen shows it. It never carries the secret. */
export interface WebhookView {
  id: string;
  name: string;
  url: string;
  eventTypes: string[];
  active: boolean;
  /** Why the platform paused it after deliveries kept failing; empty when it did not. */
  pausedReason: string;
  consecutiveFailures: number;
}

export interface WebhookDraft {
  name: string;
  url: string;
  eventTypes: WebhookEventType[];
}

export interface WebhookChanges extends WebhookDraft {
  active: boolean;
}

/** The signing secret, readable only in the response that issued it. */
export interface WebhookSecret {
  id: string;
  secret: string;
}

/** One page of deliveries, newest first. */
export interface WebhookDeliveryPage {
  rows: Row[];
  /** Pass to read the next page; null on the last. */
  nextCursor: string | null;
}

// ---------------------------------------------------------------- load building

/** A load as the building screen shows it, and what is aboard in drop order. */
export interface LoadBuild {
  load: Row;
  aboard: Row[];
}

/** One page of consignments waiting for a load. */
export interface ConsignmentPage {
  rows: Row[];
  /** Pass to read the next page; null on the last. */
  nextCursor: string | null;
}

/** The most consignments one request may put on a load. */
export const MAX_BULK_ASSIGNMENT = 500;

// ---------------------------------------------------------------- order desk

/** An order's header as an edit restates it, whole. Blank strings are values left unset. */
export interface OrderHeaderDraft {
  customerPartnerId: string;
  originTerminalId: string;
  /** ISO instants, or blank. */
  requestedPickupAt: string;
  requestedDeliveryAt: string;
  /** Both for a part-load order, neither for a full truckload. */
  originHubTerminalId: string;
  destinationHubTerminalId: string;
  /** A lane of the customer's contract in force; blank has the contract's lane found at validation. */
  contractLaneId: string;
  /** The load or vehicle type the order is booked for; blank for none. */
  loadType: string;
}

/** What raising an order needs; it starts as a draft. */
export interface OrderRaiseDraft {
  orderNo: string;
  customerPartnerId: string;
  originTerminalId: string;
  requestedPickupAt: string;
  requestedDeliveryAt: string;
  /** A lane of the customer's contract in force, picked at the desk; blank for none. */
  contractLaneId: string;
  loadType: string;
}

/**
 * A line as entered or restated. Numbers stay as typed, and blank optional fields are left
 * out of the request. The measurements travel with it, or an edit would clear them.
 */
export interface OrderLineDraft {
  lineNo: number;
  materialCode: string;
  materialDescription: string;
  materialClass: string;
  hazmatUnCode: string;
  quantity: string;
  uom: string;
  deadWeightKg: string;
  lengthM: string;
  widthM: string;
  heightM: string;
  volumetricDivisor: string;
  consigneePartnerId: string;
  destinationTerminalId: string;
}

/** A stored line: the draft it restates as, and what is shown beside it. */
export interface OrderLineView extends OrderLineDraft {
  id: string;
  consigneeName: string;
  destinationTerminalCode: string;
  planned: boolean;
  /** How the line was closed (NORMAL, SHORT, FORCE or AUTO); empty while it is open. */
  closeKind: string;
  /** Cancelled on its own: no longer demand, and neither closed nor closable. */
  cancelled: boolean;
}

/** How a person may close an order or a line; AUTO is the platform's own. */
export type CloseKindChoice = 'NORMAL' | 'SHORT' | 'FORCE';

/** An order as the desk shows it, with the version every edit must send back. */
export interface OrderDesk {
  order: Row;
  version: string;
  header: OrderHeaderDraft;
  lines: OrderLineView[];
}

/** One step of a record's history, as the desk lists it. */
export interface HistoryEntryRow {
  id: string;
  action: string;
  /** Who did it: a name, `you`, `another user` for a portal reader, or empty when the platform did. */
  actor: string;
  occurredAt: string;
  /** `FROM → TO` when the step moved the status, otherwise empty. */
  change: string;
  /** A reason recorded with the step, when there is one. */
  reason: string;
}

/** A page of history, newest first; `nextCursor` reads the older steps. */
export interface HistoryPage {
  entries: HistoryEntryRow[];
  nextCursor: string | null;
}

// ---------------------------------------------------------------- client billing

/** An invoice and its lines, in line order. */
export interface ClientInvoiceDetail {
  invoice: Row;
  lines: Row[];
}

/** What a client owes as of today, in age buckets, and the invoices it is made of. */
export interface ClientAgeingView {
  currency: string;
  outstanding: string;
  unappliedCredit: string;
  /** Keyed `0-30`, `31-60`, `61-90` and `90+`. */
  buckets: Record<string, string>;
  invoices: Row[];
}

/** Money received from a client; `receivedOn` is a `YYYY-MM-DD` day. */
export interface PaymentDraft {
  amount: number;
  receivedOn: string;
  reference: string;
}

/** A credit against a whole invoice, before tax, spread over its lines. */
export interface CreditNoteDraft {
  reason: string;
  amount: number;
}

/**
 * The one error screens handle: a message fit to show, and the HTTP status if there was one.
 * `errors` names each item a refusal of many was about -- a field, or a consignment's
 * tracking reference -- with its reasons; it is empty otherwise. `code` is the problem's
 * stable name, such as `order-has-consignments`, or empty when there was none.
 */
export class ApiProblem extends Error {
  constructor(message: string, readonly status?: number, readonly errors: Record<string, string> = {},
    readonly code = '') {
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

  /** One page of claims, only those against the consignment when one is given. */
  abstract claims(consignmentId?: string): Promise<Row[]>;
  /** Resolves to the new claim's id. */
  abstract raiseClaim(draft: ClaimDraft): Promise<string>;
  abstract transitionClaim(id: string, move: ClaimMove): Promise<void>;
  abstract issueDeliveryOtp(consignmentId: string): Promise<OtpIssued>;
  abstract recordDeliveryProof(consignmentId: string, draft: ProofDraft): Promise<void>;
  /** Null when no code has been issued for the consignment yet. */
  abstract deliveryProof(consignmentId: string): Promise<DeliveryProofView | null>;
  /** A consignment by the reference a customer quotes. */
  abstract consignmentByRef(trackingRef: string): Promise<ConsignmentCard>;
  abstract consignment(id: string): Promise<ConsignmentCard>;
  /** Every part of a split consignment, in reference order (at most 500). */
  abstract consignmentParts(id: string): Promise<ConsignmentCard[]>;
  abstract splitConsignment(id: string, parts: SplitPartDraft[]): Promise<SplitPart[]>;
  /**
   * Amends the particulars of a PLANNED consignment, sending `version` as If-Match. Refused with a
   * problem whose code is `consignment-not-amendable`, `consignment-amendment-invalid` (with each
   * field named in `errors`), `client-reference-taken` or `concurrent-modification`.
   */
  abstract amendConsignment(id: string, version: string, particulars: ConsignmentParticularsDraft): Promise<void>;
  /**
   * Cancels one consignment, taking it off its load first. Refused with a problem whose code says
   * why, such as `consignment-from-warehouse` or `consignment-dispatched`.
   */
  abstract cancelConsignment(id: string, reason: string): Promise<void>;

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

  /** The approvals inbox, newest first. */
  abstract approvals(filter: ApprovalFilter): Promise<Row[]>;
  abstract approval(id: string): Promise<ApprovalDetail>;
  abstract approveApproval(id: string, version: number, note: string): Promise<void>;
  /** A reason is required. */
  abstract rejectApproval(id: string, version: number, reason: string): Promise<void>;
  /** The requester only. */
  abstract cancelApproval(id: string, version: number, reason: string): Promise<void>;
  /** Always asks for approval: resolves to the request raised. */
  abstract requestProofReversal(consignmentId: string, reason: string): Promise<ApprovalPending>;

  abstract webhooks(): Promise<WebhookView[]>;
  /** Shown once: the secret in the result cannot be read again. */
  abstract subscribeWebhook(draft: WebhookDraft): Promise<WebhookSecret>;
  /** Replaces name, address, types and active; reactivating clears a pause. */
  abstract reviseWebhook(id: string, changes: WebhookChanges): Promise<void>;
  /** A new secret, shown once; the old one stops signing at once. */
  abstract rotateWebhookSecret(id: string): Promise<WebhookSecret>;
  /** Queues a test delivery; resolves to its id. */
  abstract pingWebhook(id: string): Promise<string>;
  /** Newest first; pass the previous page's cursor for the next one. */
  abstract webhookDeliveries(id: string, cursor?: string): Promise<WebhookDeliveryPage>;
  abstract redeliverWebhook(deliveryId: string): Promise<void>;

  /** Rejects with a 404 problem for a load that does not exist. */
  abstract loadBuild(loadId: string): Promise<LoadBuild>;
  /** Consignments not yet on a load, narrowed to one order when one is given. */
  abstract unassignedConsignments(orderId?: string, cursor?: string): Promise<ConsignmentPage>;
  /**
   * Puts the consignments on the load in the order given, all or none. A refusal is an
   * ApiProblem whose `errors` name each consignment that cannot board, by tracking reference.
   */
  abstract assignConsignments(loadId: string, consignmentIds: string[]): Promise<void>;
  /**
   * The load changes below send `version` as If-Match: the load's version as last read (its
   * `version` field). A stale one is refused with `concurrent-modification`; every other
   * refusal is an ApiProblem whose `code` names it, such as `load-awarded`.
   */
  abstract removeFromLoad(loadId: string, version: string, consignmentId: string): Promise<void>;
  abstract unplanLoad(loadId: string, version: string): Promise<void>;
  abstract cancelLoad(loadId: string, version: string, reason: string): Promise<void>;

  /** Rejects with a 404 problem for an order that does not exist. */
  abstract orderDesk(orderId: string): Promise<OrderDesk>;
  /** Resolves to the new order's id. */
  abstract raiseOrder(orgUnitId: string, draft: OrderRaiseDraft): Promise<string>;
  /**
   * The edits below send `version` as If-Match: the version the desk was read at. A stale one
   * is refused with the problem code `concurrent-modification`, and nothing changes.
   */
  abstract reviseOrderHeader(orderId: string, version: string, header: OrderHeaderDraft): Promise<void>;
  abstract replaceOrderLines(orderId: string, version: string, lines: OrderLineDraft[]): Promise<void>;
  abstract removeOrderLine(orderId: string, version: string, lineId: string): Promise<void>;
  /** A validated order with no consignments yet; a null part is kept as it is. */
  abstract amendOrder(orderId: string, version: string, header: OrderHeaderDraft | null,
    lines: OrderLineDraft[] | null): Promise<void>;
  abstract validateOrder(orderId: string): Promise<void>;
  /** Null when the order is cancelled; the approval request when the tenant asks for one first. */
  abstract cancelOrder(orderId: string, reason: string): Promise<ApprovalPending | null>;
  /**
   * Closes the order: NORMAL needs everything delivered, SHORT and FORCE need a reason, FORCE
   * needs ORDER_FORCE_CLOSE. A refusal is an ApiProblem whose code says why (`order-not-complete`,
   * `order-freight-pending`, `close-reason-required`, `order-already-closed`, ...).
   */
  abstract closeOrder(orderId: string, kind: CloseKindChoice, reason: string): Promise<void>;
  /** Closes one line by the same rules; the order follows once nothing else is open. */
  abstract closeOrderLine(orderId: string, lineId: string, kind: CloseKindChoice, reason: string): Promise<void>;
  /** The order's history, newest first; a 404 problem for an order the caller cannot read. */
  abstract orderHistory(orderId: string, cursor?: string): Promise<HistoryPage>;
  /** Resolves to how many consignments were cut. */
  abstract generateConsignments(orderId: string): Promise<number>;
  /** Customer partners a 3PL contract may be held by. */
  abstract clientChoices(): Promise<Choice[]>;
  /** Every contract version of the client, newest first. */
  abstract clientContracts(clientId: string): Promise<Row[]>;
  /** One version of a contract in full; its `version` is what the edits below send back. */
  abstract contractDetail(contractId: string): Promise<ContractDetail>;
  /** Starts a draft under the client; resolves to its id. It applies to nothing until verified. */
  abstract createContractDraft(orgUnitId: string, clientId: string, draft: ContractDraft): Promise<string>;
  /** Replaces a draft's terms, keeping what the form does not show (services, tax, currency). */
  abstract reviseContractDraft(current: ContractDetail, draft: ContractDraft): Promise<void>;
  /**
   * Replaces a draft's lanes and allowances whole, keeping every other term. Refusals carry their
   * problem codes, such as `client-contract-lane-duplicated`.
   */
  abstract reviseContractLanes(current: ContractDetail, lanes: ContractLaneDraft[], directOrdersAllowed: boolean,
    allowedLoadTypes: string[]): Promise<void>;
  /** The lanes of the contract in force for the client on a day (today when blank), for the order desk. */
  abstract contractLanes(clientId: string, onDate?: string): Promise<ContractLaneChoice[]>;
  abstract deleteContractDraft(contractId: string, version: string): Promise<void>;
  /** Every completeness rule, with whether the draft passes it. */
  abstract contractChecklist(contractId: string): Promise<ContractChecklistRow[]>;
  /**
   * Asks for the draft to be verified: the approval request when one is needed, null when the
   * tenant asks for none and the contract is now in force. A draft that fails the checklist is an
   * ApiProblem `contract-checklist-incomplete` whose `errors` name every failing rule.
   */
  abstract requestContractVerification(contractId: string, version: string): Promise<ApprovalPending | null>;
  /** Asks to move a contract's end date later; the approval request, or null once done. */
  abstract requestContractExtension(contractId: string, validTill: string, reason: string): Promise<ApprovalPending | null>;
  /** Asks to add a charge from a day, as a successor version; the approval request, or null once done. */
  abstract requestContractChargeAddition(contractId: string, draft: ContractChargeDraft): Promise<ApprovalPending | null>;
  /** The client's charges in the month holding `period` (a `YYYY-MM-DD` day). */
  abstract clientCharges(clientId: string, period: string): Promise<Row[]>;
  /** Marks a consignment billable to its client without waiting for its proof; audited. */
  abstract markConsignmentBillable(consignmentId: string, reason: string): Promise<void>;
  /**
   * Bills the month holding `period`; resolves to the invoice's id. Running a month
   * again answers the same invoice.
   */
  abstract runClientBilling(clientId: string, period: string): Promise<string>;
  /** Newest period first. */
  abstract clientInvoices(clientId: string): Promise<Row[]>;
  abstract clientInvoice(invoiceId: string): Promise<ClientInvoiceDetail>;
  abstract recordClientPayment(clientId: string, draft: PaymentDraft): Promise<void>;
  /** Resolves to the credit note's number. */
  abstract issueClientCreditNote(invoiceId: string, draft: CreditNoteDraft): Promise<string>;
  /** Invoices, credit notes and payments in the order they happened, with the running balance. */
  abstract clientStatement(clientId: string): Promise<Row[]>;
  abstract clientAgeing(clientId: string): Promise<ClientAgeingView>;

  // Warehouse operations
  /** Terminals whose category is WAREHOUSE. */
  abstract warehouseChoices(): Promise<Choice[]>;
  abstract warehouseBins(warehouseId: string): Promise<Row[]>;
  /** Resolves to the new bin's id. */
  abstract createBin(warehouseId: string, code: string, capacityUnits: number): Promise<string>;
  /** The tenant's SKUs in code order (one page of 200). */
  abstract skus(): Promise<Row[]>;
  /** Resolves to the new SKU's id. */
  abstract createSku(draft: SkuDraft): Promise<string>;
  /** Newest first. */
  abstract asns(warehouseId: string): Promise<Row[]>;
  /** The notice and its lines; rejects with a 404 problem for one that does not exist. */
  abstract asn(id: string): Promise<WarehouseDocument>;
  /** Resolves to the new ASN's id. */
  abstract createAsn(draft: AsnDraft): Promise<string>;
  /** The count at the dock: undamaged units go onto the dock, damaged ones into quarantine. */
  abstract receiveAsn(id: string, counts: ReceiptCountDraft[], crossDock: boolean): Promise<void>;
  /** Moves units from the dock into a bin. */
  abstract putaway(draft: StockMoveDraft): Promise<void>;
  /** What is on hand, by location, sku and lot; empty locations are left out. */
  abstract stock(warehouseId: string): Promise<Row[]>;
  /** A SKU's ledger, newest first. */
  abstract stockMovements(skuId: string): Promise<Row[]>;
  /** A signed correction to a bin; resolves to the movement's id. */
  abstract adjustStock(draft: StockMoveDraft): Promise<string>;
  /** Moves quarantined units into a bin; resolves to the movement's id. */
  abstract releaseQuarantine(draft: StockMoveDraft): Promise<string>;
  /** Writes quarantined units off (the bin is not used); resolves to the movement's id. */
  abstract writeOffQuarantine(warehouseId: string, draft: StockMoveDraft): Promise<string>;
  /** Newest first. */
  abstract outboundOrders(warehouseId: string): Promise<Row[]>;
  abstract outboundOrder(id: string): Promise<WarehouseDocument>;
  /** Resolves to the new order's id. */
  abstract createOutboundOrder(draft: OutboundDraft): Promise<string>;
  abstract pickOutboundOrder(id: string): Promise<void>;
  /** Resolves to the handling unit the order was packed into. */
  abstract packOutboundOrder(id: string): Promise<string>;
  abstract cancelBackOrder(id: string): Promise<void>;
  /** Newest first. */
  abstract handlingUnits(warehouseId: string): Promise<Row[]>;
  abstract handlingUnit(id: string): Promise<WarehouseDocument>;
  /** Hands the unit to transport; a destination, consignee and weight make it a consignment too. */
  abstract dispatchHandlingUnit(id: string, draft: DispatchDraft): Promise<void>;
  /** Newest first. */
  abstract pickWaves(warehouseId: string): Promise<Row[]>;
  abstract pickWave(id: string): Promise<WarehouseDocument>;
  /** Resolves to the new wave's id. */
  abstract releasePickWave(warehouseId: string, orderIds: string[]): Promise<string>;
  abstract pickPickWave(id: string): Promise<void>;

  // Administration
  /** Everyone in the caller's organisational unit and below, by name. */
  abstract adminUsers(): Promise<AdminUser[]>;
  /** The permission codes the user's roles grant, sorted. */
  abstract userPermissions(userId: string): Promise<string[]>;
  /** What the user's next access token will carry; a blank field is unrestricted. */
  abstract accessAttributes(userId: string): Promise<AccessAttributesDraft>;
  /** Replaces the whole set: a field left blank is cleared, not kept. */
  abstract setAccessAttributes(userId: string, draft: AccessAttributesDraft): Promise<void>;
  /** By code; pass the previous page's cursor for the next one. */
  abstract accessPolicies(cursor?: string): Promise<AccessPolicyPage>;
  /**
   * Resolves to the new policy's id. A condition that is not JSON is refused before anything
   * is sent, as an ApiProblem with the code `condition-not-json`.
   */
  abstract createAccessPolicy(draft: AccessPolicyDraft): Promise<string>;
  /** The code and the action stay; everything else is replaced. */
  abstract reviseAccessPolicy(id: string, changes: AccessPolicyChanges): Promise<void>;
  abstract deleteAccessPolicy(id: string): Promise<void>;
  /** Documents of every partner, vehicle and driver lapsing within `withinDays`, soonest first. */
  abstract expiringDocuments(withinDays: number): Promise<Row[]>;
  abstract ownerDocuments(ownerType: DocumentOwnerType, ownerId: string): Promise<Row[]>;
  /** Resolves to the new document's id. */
  abstract attachDocument(ownerType: DocumentOwnerType, ownerId: string, draft: DocumentDraft): Promise<string>;

  // Commercial
  /** Publishes a rate card version; resolves to its id. A card is never edited, only succeeded. */
  abstract publishTariff(orgUnitId: string, draft: TariffDraft): Promise<string>;
  /** Adds a weight band to an open card; resolves to the band's id. */
  abstract addTariffSlab(tariffId: string, slab: SlabDraft): Promise<string>;
  /** Records the surcharge for the month holding `month` (a `YYYY-MM-DD` day); resolves to its id. */
  abstract recordFuelSurcharge(month: string, surchargePct: number): Promise<string>;
  /** Resolves to the new guide's id. */
  abstract defineRoutingGuide(orgUnitId: string, draft: GuideDraft): Promise<string>;
  /** Rejects with a 404 problem for a guide that does not exist. */
  abstract routingGuide(id: string): Promise<RoutingGuideDetail>;
  abstract addGuideVendor(guideId: string, draft: GuideVendorDraft): Promise<void>;
  abstract retireRoutingGuide(id: string): Promise<void>;
  /** One page of allocations, only those in `status` when one is given. */
  abstract allocations(status?: string): Promise<Row[]>;
  /** The allocation and every offer made for it, in rank order. */
  abstract allocationDetail(id: string): Promise<AllocationDetail>;
  /** Starts sourcing a planned load; resolves to the allocation's id. */
  abstract allocateLoad(loadId: string): Promise<string>;
  abstract acceptOffer(allocationId: string, vendorPartnerId: string): Promise<void>;
  abstract rejectOffer(allocationId: string, vendorPartnerId: string): Promise<void>;
  abstract cancelAllocation(id: string): Promise<void>;
  /** One page of freight bills, only those in `status` when one is given. */
  abstract freightBillList(status?: string): Promise<Row[]>;
  /** The bill and the arithmetic behind its total, in line order. */
  abstract freightBill(id: string): Promise<FreightBillDetail>;
  /** Compares the vendor's claim with the bill; resolves to `APPROVED_FOR_PAYMENT` or `DISPUTED`. */
  abstract matchFreightBill(id: string, claimedAmount: number): Promise<string>;
  /** Settles a dispute in the vendor's favour, with the reason on the record. */
  abstract resolveFreightBill(id: string, reason: string): Promise<void>;
  /** Releases a bill's trip from waiting on proofs of delivery; the reason is audited. */
  abstract releaseProofHold(billId: string, reason: string): Promise<void>;
  // Control tower
  /** Newest first, one page; an empty filter value means any. */
  abstract exceptionTickets(filter: ExceptionFilter): Promise<Row[]>;
  /** Rejects with a 404 problem for a ticket that does not exist. */
  abstract exceptionTicket(id: string): Promise<Row>;
  /** Every step the ticket has taken, oldest first. */
  abstract exceptionHistory(id: string): Promise<Row[]>;
  /** Resolves to the new ticket's id. */
  abstract raiseException(draft: ExceptionDraft): Promise<string>;
  /**
   * The steps below are refused with the problem code `exception-illegal-transition` when the
   * ticket has moved on since it was read, and closing a ticket that holds up settlement with
   * `exception-close-needs-second-person` when the closer also resolved it.
   */
  abstract assignException(id: string, assigneeUserId: string, note: string): Promise<void>;
  abstract investigateException(id: string, note: string): Promise<void>;
  abstract resolveException(id: string, resolution: string): Promise<void>;
  abstract reopenException(id: string, reason: string): Promise<void>;
  abstract closeException(id: string, note: string): Promise<void>;
  /** The users a ticket can be assigned to: those the caller may see. */
  abstract userChoices(): Promise<Choice[]>;
  abstract detectorSettings(): Promise<DetectorSettingsView>;
  abstract saveDetectorSettings(etaSlipMinutes: number, temperatureExcursionMinutes: number): Promise<void>;
  /** Null when the trip is not watched. */
  abstract tripWatch(tripId: string): Promise<TripWatchView | null>;
  /** Replaces the trip's watch; blank fields are left out. */
  abstract setTripWatch(tripId: string, draft: TripWatchDraft): Promise<void>;
  abstract clearTripWatch(tripId: string): Promise<void>;
  /** How far a trip has got, and the geofences it has crossed. */
  abstract tripActivity(tripId: string): Promise<TripActivity>;
  /** Every vehicle's last reported position, as rows and on a map. */
  abstract vehiclePositions(): Promise<Mapped>;
  /** Trips currently off their planned corridor. */
  abstract openDeviations(): Promise<Row[]>;

  // Rate cards and rail tariffs
  /** One page of rate card versions, newest first, narrowed by the filter's non-blank fields; pass `cursor` for the next. */
  abstract tariffs(filter: TariffFilter, cursor?: string): Promise<RateCardPage>;
  /** A rate card version and its weight bands, lightest first. Rejects with a 404 problem when not found. */
  abstract tariffCard(id: string): Promise<TariffCardDetail>;
  /** One page of rail rate card versions, newest first; pass `cursor` for the next. */
  abstract railTariffs(cursor?: string): Promise<RateCardPage>;
  /** One rail rate card version. Rejects with a 404 problem when not found. */
  abstract railTariff(id: string): Promise<Row>;
  /** Publishes a rail rate card version for a railhead pair; resolves to its id. */
  abstract publishRailTariff(draft: RailTariffDraft): Promise<string>;
  // Pickers
  /** One page of what a picker offers for the typed text; a blank `q` lists from the start. */
  abstract pick(query: PickQuery): Promise<PickPage>;
  // Credit
  /** A customer's limits, exposure and headroom as of now. Rejects with a 404 problem for an unknown partner. */
  abstract creditPosition(partnerId: string): Promise<CreditPositionView>;
  /** Grants a one-time override of the hard limit; resolves to its id. Needs CREDIT_OVERRIDE. */
  abstract grantCreditOverride(partnerId: string, draft: CreditOverrideDraft): Promise<string>;
  /** Asks for a new limit, which applies only once someone else approves it; resolves to the request's id. */
  abstract requestCreditLimitChange(partnerId: string, draft: CreditLimitChangeDraft): Promise<string>;
  /** Change requests across partners, newest first, only those in `status` when one is given. */
  abstract creditLimitChanges(status?: CreditChangeStatus): Promise<Row[]>;
  /** One partner's change log, newest first. */
  abstract partnerCreditLimitChanges(partnerId: string): Promise<Row[]>;
  abstract approveCreditLimitChange(id: string, note: string): Promise<void>;
  abstract rejectCreditLimitChange(id: string, note: string): Promise<void>;
  // Number series
  /** The tenant's series, in code order, only one document type's when one is given. Needs NUMBER_SERIES_MANAGE. */
  abstract numberSeries(documentType?: NumberedDocumentType): Promise<NumberSeriesView[]>;
  /** The units a series can be scoped to: the caller's own and those beneath it. */
  abstract numberSeriesScopes(): Promise<NumberSeriesScope[]>;
  /** Resolves to the new series' id. */
  abstract createNumberSeries(draft: NumberSeriesDraft): Promise<string>;
  abstract setNumberSeriesActive(id: string, active: boolean): Promise<void>;
  /** Only a series that never issued a number can be deleted (`number-series-in-use`). */
  abstract deleteNumberSeries(id: string): Promise<void>;
  /** What numbers in a proposed format look like; rejects a format that could not be saved. */
  abstract previewNumberFormat(format: NumberFormatDraft): Promise<NumberFormatSample>;
  /** The number the series would issue next, without issuing it. */
  abstract nextNumber(id: string): Promise<string>;
  /** Issues the next number for a document made outside the platform. */
  abstract issueNumber(id: string): Promise<string>;
  /** Gives a number back to its series, with a reason. */
  abstract releaseNumber(id: string, number: string, reason: string): Promise<void>;
  /** The numbers given back to a series, newest first. */
  abstract numberReleases(id: string): Promise<Row[]>;
  abstract verifyNumber(documentType: NumberedDocumentType, number: string): Promise<NumberVerdict>;
  // Profile and settings
  /** Who the signed-in caller is. */
  abstract me(): Promise<MeView>;
  /** Every registered setting, in key order. */
  abstract settingDefinitions(): Promise<SettingDefinitionView[]>;
  /** The caller's own preferences and those in force for them. */
  abstract myPreferences(): Promise<MyPreferencesView>;
  /** Saves the caller's preferences at the version last read; a null value clears one. */
  abstract saveMyPreferences(version: string, values: Record<string, SettingValue | null>): Promise<MyPreferencesView>;
  /** The tenant's profiles, only one scope's when one is given. Needs SETTINGS_READ. */
  abstract settingProfiles(scope?: SettingScope): Promise<SettingProfileView[]>;
  abstract settingProfile(id: string): Promise<SettingProfileView>;
  /** Resolves to the new profile's id. Needs SETTINGS_MANAGE. */
  abstract createSettingProfile(draft: SettingProfileDraft): Promise<string>;
  /** Applies changes at the version last read; resolves to the profile as saved. */
  abstract updateSettingProfile(id: string, version: string, changes: SettingChange[]): Promise<SettingProfileView>;
  abstract deleteSettingProfile(id: string, version: string): Promise<void>;
  /** Every setting's value in force for a context, with where it came from. */
  abstract effectiveSettings(context: SettingContextDraft): Promise<EffectiveSettingView[]>;
  /** What changed on a profile, newest first, one row per changed version. */
  abstract settingProfileHistory(id: string): Promise<SettingHistoryRow[]>;
  /** Replaces a combination profile's conditions at the version last read. */
  abstract updateSettingConditions(id: string, version: string, conditions: SettingConditions): Promise<SettingProfileView>;
  // Organisation units
  /** The caller's unit and every unit beneath it, parents first. */
  abstract orgUnits(): Promise<OrgUnitView[]>;
  abstract orgUnit(id: string): Promise<OrgUnitView>;
  /** Resolves to the new unit's id. Needs ORG_UNIT_MANAGE. */
  abstract createOrgUnit(draft: OrgUnitDraft): Promise<string>;
  /** Renames, retypes or recodes a unit at the version last read; resolves to the unit as saved. */
  abstract updateOrgUnit(id: string, version: string, edit: OrgUnitEdit): Promise<OrgUnitView>;
}

// Warehouse operations

/** A warehouse record and its lines (ASN lines, order lines, unit contents or wave orders). */
export interface WarehouseDocument {
  header: Row;
  lines: Row[];
}

export interface SkuDraft {
  code: string;
  description: string;
  /** The 3PL client that owns the goods, or blank. */
  ownerPartnerId: string;
  uom: string;
}

/** Quantities stay as typed; blank lots are left out. */
export interface AsnLineDraft {
  skuId: string;
  lotNo: string;
  quantity: string;
}

export interface AsnDraft {
  warehouseId: string;
  asnNo: string;
  /** Blank when the supplier is not on file. */
  supplierPartnerId: string;
  lines: AsnLineDraft[];
}

export interface ReceiptCountDraft {
  lineNo: number;
  receivedQty: string;
  damagedQty: string;
}

/** A putaway, an adjustment (signed quantity) or a quarantine release or write-off. */
export interface StockMoveDraft {
  binId: string;
  skuId: string;
  lotNo: string;
  quantity: string;
  /** Required by adjustments and quarantine moves; a putaway ignores it. */
  reason: string;
}

export interface OutboundLineDraft {
  skuId: string;
  quantity: string;
}

export interface OutboundDraft {
  warehouseId: string;
  orderNo: string;
  shipTo: string;
  lines: OutboundLineDraft[];
}

/** All blank but the reference is a plain dispatch; all four hand the unit to transport. */
export interface DispatchDraft {
  reference: string;
  destinationTerminalId: string;
  consigneePartnerId: string;
  grossWeightKg: string;
}

// Administration

/** A person who can sign in, as the administration desk lists them. */
export interface AdminUser {
  id: string;
  email: string;
  fullName: string;
  status: string;
  orgName: string;
  lastLoginAt: string;
}

export const ACCESS_FREIGHT_MODES = ['FTL', 'LTL', 'RAIL', 'WAREHOUSE'] as const;
export type AccessFreightMode = (typeof ACCESS_FREIGHT_MODES)[number];

/**
 * A user's access attributes as the desk edits them. Blank means unrestricted along that
 * dimension; `approvalLimit` is a decimal string so a typed value is never rounded.
 */
export interface AccessAttributesDraft {
  region: string;
  freightModes: AccessFreightMode[];
  partnerId: string;
  approvalLimit: string;
}

export type PolicyEffect = 'PERMIT' | 'DENY';

/** An access policy; `condition` is its JSON, laid out for reading and editing. */
export interface AccessPolicyView {
  id: string;
  code: string;
  action: string;
  effect: PolicyEffect;
  description: string;
  condition: string;
  enabled: boolean;
  updatedAt: string;
}

export interface AccessPolicyChanges {
  effect: PolicyEffect;
  description: string;
  /** JSON text, parsed by the gateway. */
  condition: string;
  enabled: boolean;
}

export interface AccessPolicyDraft extends AccessPolicyChanges {
  code: string;
  /** A permission code, such as `INVOICE_APPROVE`. */
  action: string;
}

export interface AccessPolicyPage {
  policies: AccessPolicyView[];
  nextCursor: string | null;
}

export const DOCUMENT_OWNER_TYPES = ['PARTNER', 'VEHICLE', 'DRIVER'] as const;
export type DocumentOwnerType = (typeof DOCUMENT_OWNER_TYPES)[number];

/** A certificate to attach; dates are `YYYY-MM-DD`, blank when not known. */
export interface DocumentDraft {
  documentType: string;
  documentNo: string;
  issuingAuthority: string;
  issuedOn: string;
  expiresOn: string;
}

// Commercial

/** A rate card to publish; blank optional figures are left out. */
export interface TariffDraft {
  code: string;
  vendorPartnerId: string;
  originTerminalId: string;
  destinationTerminalId: string;
  vehicleType: string;
  /** A `YYYY-MM-DD` day. */
  effectiveFrom: string;
  currency: string;
  minimumCharge: string;
  detentionFreeHours: string;
  detentionHourlyRate: string;
  additionalDropFee: string;
}

export const SLAB_RATE_BASES = ['PER_KG', 'FLAT'] as const;
export type SlabRateBasis = (typeof SLAB_RATE_BASES)[number];

/** A weight band; a blank upper bound means no upper bound. */
export interface SlabDraft {
  minWeightKg: string;
  maxWeightKg: string;
  rateBasis: SlabRateBasis;
  rateAmount: string;
}

export const GUIDE_STRATEGIES = ['CONTRACTUAL', 'ROUND_ROBIN'] as const;
export type GuideStrategy = (typeof GUIDE_STRATEGIES)[number];

/** A lane and vehicle type to source, and how vendors are tried. */
export interface GuideDraft {
  originTerminalId: string;
  destinationTerminalId: string;
  vehicleType: string;
  strategy: GuideStrategy;
}

/** A vendor on a guide: its rank, how long it has to answer, and a blank or agreed rate. */
export interface GuideVendorDraft {
  vendorPartnerId: string;
  rankNo: number;
  responseSlaMinutes: number;
  agreedRate: string;
}

/** A guide, its vendors in rank order. `guide.active` reads Yes or nothing. */
export interface RoutingGuideDetail {
  guide: Row;
  entries: Row[];
}

/** An allocation and its offers; each offer row carries `vendorPartnerId` for a response. */
export interface AllocationDetail {
  allocation: Row;
  offers: Row[];
}

/** A freight bill and the lines its total is made of. */
export interface FreightBillDetail {
  bill: Row;
  lines: Row[];
}

// Control tower

export const EXCEPTION_STATUSES = ['RAISED', 'ASSIGNED', 'UNDER_INVESTIGATION', 'RESOLVED', 'CLOSED'] as const;
export const EXCEPTION_SEVERITIES = ['P1', 'P2', 'P3'] as const;
/** The categories a person raises; the others are raised by the detectors and other modules. */
export const MANUAL_EXCEPTION_CATEGORIES = [
  'ACCIDENT', 'THEFT', 'BREAKDOWN', 'ROUTE_DEVIATION', 'EXTENDED_STOPPAGE', 'TEMPERATURE_EXCURSION', 'ETA_DELAY',
  'DOCUMENT_MISMATCH',
] as const;

export interface ExceptionFilter {
  status: string;
  severity: string;
}

export interface ExceptionDraft {
  tripId: string;
  category: string;
  description: string;
}

export interface DetectorSettingsView {
  etaSlipMinutes: number;
  temperatureExcursionMinutes: number;
  /** False while the platform defaults apply. */
  customised: boolean;
}

/** A trip's watch as typed; `committedArrivalAt` is a `datetime-local` value. */
export interface TripWatchDraft {
  committedArrivalAt: string;
  tempMinC: string;
  tempMaxC: string;
}

export interface TripWatchView {
  committedArrivalAt: string;
  tempMinC: string;
  tempMaxC: string;
  /** Empty while the readings are inside the band. */
  outsideRangeSince: string;
  lastReadingAt: string;
}

export interface TripActivity {
  /** Null when no position has been reported for the trip yet. */
  progress: Row | null;
  crossings: Row[];
}

// Rate cards and rail tariffs

/** Which rate card versions to list; blank fields do not narrow. */
export interface TariffFilter {
  vendorPartnerId: string;
  /** `open` for versions still in force, `superseded` for closed ones, blank for both. */
  state: '' | 'open' | 'superseded';
}

/** One page of rate cards, road or rail. */
export interface RateCardPage {
  rows: Row[];
  /** Pass to read the next page; null on the last. */
  nextCursor: string | null;
}

/** A rate card version and its weight bands. `card.superseded` reads Yes or nothing. */
export interface TariffCardDetail {
  card: Row;
  slabs: Row[];
}

/** A rail rate card to publish; blank optional figures are left out. */
export interface RailTariffDraft {
  code: string;
  carrierPartnerId: string;
  originTerminalId: string;
  destinationTerminalId: string;
  /** A `YYYY-MM-DD` day. */
  effectiveFrom: string;
  currency: string;
  perWagonRate: string;
  perContainerRate: string;
  minimumCharge: string;
}
// Pickers
/**
 * What a picker looks up. `customer` and `vendor` are partners of that type,
 * `warehouse` is a terminal of that category, and `bin` needs the warehouse
 * it belongs to. `consignment` is any consignment in any state; `unassigned`
 * only those still waiting for a load.
 */
export type PickKind =
  | 'partner' | 'customer' | 'vendor' | 'terminal' | 'warehouse' | 'vehicle' | 'driver' | 'sku' | 'bin'
  | 'order' | 'load' | 'trip' | 'ticket' | 'allocation' | 'bill' | 'consignment' | 'unassigned'
  | 'routing-guide' | 'org-unit';

export interface PickQuery {
  kind: PickKind;
  /** The typed text; blank lists from the start. */
  q: string;
  /** The `nextCursor` of the page before; absent for the first page. */
  cursor?: string | null;
  /** Required for `bin`. */
  warehouseId?: string;
}

export interface PickPage {
  items: Choice[];
  /** Null on the last page. */
  nextCursor: string | null;
}

// Credit

/** How a customer's exposure stands against its limits. */
export type CreditLimitState = 'NO_LIMIT' | 'WITHIN' | 'SOFT_EXCEEDED' | 'HARD_EXCEEDED';

/**
 * A customer's credit position, every amount formatted for display. A blank limit is no
 * limit; a blank headroom is what having no limit leaves.
 */
export interface CreditPositionView {
  partnerId: string;
  partnerCode: string;
  partnerStatus: string;
  /** False for a suspended or blacklisted customer: its orders are refused whatever the limit. */
  trading: boolean;
  currency: string;
  creditLimit: string;
  softLimit: string;
  openOrders: string;
  openOrderCount: string;
  unpricedOpenOrderCount: string;
  unbilledCharges: string;
  unbilledChargeCount: string;
  outstandingInvoices: string;
  /** The total of the three parts above. */
  exposure: string;
  headroom: string;
  limitState: CreditLimitState;
  /** The open limit change request, or blank. */
  pendingChangeId: string;
  asOf: string;
}

/** What a credit override is for: one order, or one client billing month. */
export type CreditSubjectType = 'ORDER' | 'CLIENT_INVOICE';

/** A one-time pass through the hard limit for one refused action. */
export interface CreditOverrideDraft {
  subjectType: CreditSubjectType;
  /** For an order. */
  orderId?: string;
  /** For a billing run: any `YYYY-MM-DD` day of the month. */
  period?: string;
  /** The highest exposure the override lets through. */
  upTo: number;
  reason: string;
}

/** A requested credit limit; a second person approves it before it applies. */
export interface CreditLimitChangeDraft {
  creditLimit: number;
  /** Optional, and at most the credit limit. */
  softLimit?: number;
  reason: string;
}

export const CREDIT_CHANGE_STATUSES = ['PENDING', 'APPROVED', 'REJECTED'] as const;
export type CreditChangeStatus = (typeof CREDIT_CHANGE_STATUSES)[number];

// Number series

export const NUMBERED_DOCUMENT_TYPES = ['ORDER', 'CONSIGNMENT_NOTE', 'DOCKET', 'TRIP', 'GATE_PASS', 'LOAD', 'INVOICE'] as const;
/** The kinds of document a number series can number. */
export type NumberedDocumentType = (typeof NUMBERED_DOCUMENT_TYPES)[number];

export const NUMBER_YEAR_CODES = ['NONE', 'START_YEAR', 'SPAN'] as const;
/** How the financial year appears in a number: not at all, `2026`, or `2627`. */
export type NumberYearCode = (typeof NUMBER_YEAR_CODES)[number];

/** How a series spells its numbers. */
export interface NumberFormatDraft {
  prefix: string;
  yearCode: NumberYearCode;
  /** One of '-', '/', '_', '.', or empty for none. */
  separator: string;
  counterLength: number;
  /** 1-12; the financial year starts on the first of this month. */
  yearStartMonth: number;
  /** An IANA zone id; blank for UTC. */
  timeZone: string;
}

/** A new series. A blank `orgUnitId` numbers the whole tenant. */
export interface NumberSeriesDraft extends NumberFormatDraft {
  code: string;
  documentType: NumberedDocumentType;
  orgUnitId: string;
  reuseReleased: boolean;
  active: boolean;
  description: string;
}

/** A series as the administration desk lists it; counts are for the current financial year. */
export interface NumberSeriesView {
  id: string;
  code: string;
  documentType: NumberedDocumentType;
  orgUnitId: string;
  /** The unit's name, or "Whole tenant". */
  scope: string;
  prefix: string;
  yearCode: NumberYearCode;
  separator: string;
  counterLength: string;
  yearStartMonth: string;
  timeZone: string;
  reuseReleased: boolean;
  active: boolean;
  description: string;
  financialYear: string;
  issuedThisYear: string;
  lastIssued: string;
  releasedAvailable: string;
  sample: string;
}

/** An organisational unit a series can be scoped to. */
export interface NumberSeriesScope {
  id: string;
  name: string;
  path: string;
  unitType: string;
}

/** The first number of this financial year and of the next, in a proposed format. */
export interface NumberFormatSample {
  sample: string;
  nextYearSample: string;
}

/** Whether a number belongs to a series, and where it stands; blank fields when it belongs to none. */
export interface NumberVerdict {
  belongs: boolean;
  seriesCode: string;
  financialYear: string;
  counter: string;
  /** ISSUED, RELEASED or NOT_ISSUED. */
  status: string;
}

// ---------------------------------------------------------------- profile and settings

/** Who the signed-in caller is, for their profile page. */
export interface MeView {
  userId: string;
  email: string;
  fullName: string;
  tenantCode: string;
  tenantName: string;
  orgUnitName: string;
  orgPath: string;
  roles: string[];
  permissions: string[];
}

/** The scopes an administrator keeps profiles for; PLATFORM is the operator's, USER a person's own. */
export const SETTING_SCOPES = ['ORG_DEFAULT', 'ORG_UNIT', 'CUSTOMER', 'VENDOR', 'VEHICLE', 'COMBINATION'] as const;
export type SettingScope = (typeof SETTING_SCOPES)[number] | 'PLATFORM' | 'USER';

/** A setting's value as the API holds it: text, number, true/false, or a small object. */
export type SettingValue = string | number | boolean | Record<string, unknown>;

/** One registered setting, with what a value must satisfy. */
export interface SettingDefinitionView {
  key: string;
  /** BOOLEAN, INTEGER, DECIMAL, STRING, ENUM, DURATION or JSON. */
  type: string;
  category: string;
  label: string;
  description: string;
  defaultValue: SettingValue | null;
  scopes: string[];
  allowedValues: string[];
  min: number | null;
  max: number | null;
  userPreference: boolean;
  effectiveDated: boolean;
  tenantOverridable: boolean;
}

/** A setting's value in force for a context, and where it came from in words. */
export interface EffectiveSettingView {
  key: string;
  type: string;
  category: string;
  label: string;
  value: SettingValue | null;
  /** `System default`, or the scope and profile name that supplied it. */
  source: string;
  /** The scope type that supplied it; empty for the system default. */
  sourceScope: string;
  /** The first day of the version in force; empty for an undated one. */
  effectiveFrom: string;
}

/** The caller's own preferences: what they set, and what is in force for them. */
export interface MyPreferencesView {
  version: string;
  values: Record<string, SettingValue>;
  effective: EffectiveSettingView[];
}

/** One version of one key in a profile. */
export interface SettingVersionView {
  key: string;
  effectiveFrom: string;
  value: SettingValue | null;
  /** IN_FORCE, SUPERSEDED or SCHEDULED; only a scheduled version may change. */
  status: string;
}

export interface SettingProfileView {
  id: string;
  scopeType: string;
  scopeId: string;
  scopeLabel: string;
  name: string;
  version: string;
  /** Key to the value in effect today, from this profile alone. */
  values: Record<string, SettingValue>;
  versions: SettingVersionView[];
  /** A combination's conditions; null for every other scope. */
  conditions: SettingConditions | null;
  updatedAt: string;
}

/** How freight moves; a combination may name one. */
export const FREIGHT_MODES = ['FTL', 'LTL', 'RAIL', 'MILK_RUN'] as const;

/**
 * What a combination profile applies to: every id or mode named must hold for
 * the context (the unit by its subtree); an empty one is left open. Two at least.
 */
export interface SettingConditions {
  orgUnitId: string;
  customerId: string;
  vendorId: string;
  vehicleId: string;
  freightMode: string;
  /** Settles combinations with as many conditions and an equally deep unit; higher wins. */
  priority: number;
}

export interface SettingProfileDraft {
  scopeType: SettingScope;
  /** The unit, customer or vehicle; empty for the organisation default and a combination. */
  scopeId: string;
  name: string;
  /** A combination's conditions. */
  conditions?: SettingConditions;
}

/** A change to a profile: `effectiveFrom` empty for an undated value; a null value removes it. */
export interface SettingChange {
  key: string;
  effectiveFrom: string;
  value: SettingValue | null;
}

/** What to resolve effective settings for; every field optional (empty). */
export interface SettingContextDraft {
  orgUnitId: string;
  customerId: string;
  vehicleId: string;
  vendorId: string;
  asOf: string;
  /** A freight mode, or empty for none. */
  freightMode: string;
}

/** One version that changed on a profile: before and after, empty when absent. */
export interface SettingHistoryRow {
  id: string;
  action: string;
  actor: string;
  occurredAt: string;
  /** `key` or `key@date`; `name` for a rename. */
  setting: string;
  before: string;
  after: string;
}

// ---------------------------------------------------------------- organisation units

/** The levels, highest first; REGION is the older name for a zone and ranks as one. */
export const ORG_UNIT_TYPES = ['HQ', 'LOB', 'COUNTRY', 'ZONE', 'REGION', 'BRANCH', 'HUB'] as const;

/** Each level in words, for labels such as "Branch: Gurgaon". */
export const ORG_UNIT_WORDS: Record<string, string> = {
  HQ: 'Head office', LOB: 'Line of business', COUNTRY: 'Country', ZONE: 'Zone', REGION: 'Region',
  BRANCH: 'Branch', HUB: 'Hub',
};

/** Each level's rank: a unit ranks strictly below its parent. */
export const ORG_UNIT_RANK: Record<string, number> = {
  HQ: 0, LOB: 1, COUNTRY: 2, ZONE: 3, REGION: 3, BRANCH: 4, HUB: 5,
};

export interface OrgUnitView {
  id: string;
  /** Empty for the tenant's root. */
  parentId: string;
  slug: string;
  path: string;
  name: string;
  unitType: string;
  level: number;
  /** A country's own code; empty otherwise. */
  countryCode: string;
  stateCode: string;
  /** The nearest country and state at or above the unit; what platform settings follow. */
  effectiveCountry: string;
  effectiveState: string;
  version: string;
}

export interface OrgUnitDraft {
  parentId: string;
  slug: string;
  name: string;
  unitType: string;
  countryCode: string;
  stateCode: string;
}

/** A unit's new name, type and codes; the slug and parent never change. */
export interface OrgUnitEdit {
  name: string;
  unitType: string;
  countryCode: string;
  stateCode: string;
}
