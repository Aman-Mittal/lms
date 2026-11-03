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

// ---------------------------------------------------------------- webhooks

/** What a subscription can ask for. `PING` is what a test ping sends and cannot be subscribed to. */
export const WEBHOOK_EVENT_TYPES = [
  'ORDER_VALIDATED', 'CONSIGNMENTS_GENERATED', 'CONSIGNMENT_SPLIT', 'TRIP_DISPATCHED', 'TRIP_COMPLETED', 'TRIP_CANCELLED',
  'DELIVERY_PROOF_RECORDED', 'CLAIM_RAISED', 'CLAIM_STATUS_CHANGED',
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
}

/** What raising an order needs; it starts as a draft. */
export interface OrderRaiseDraft {
  orderNo: string;
  customerPartnerId: string;
  originTerminalId: string;
  requestedPickupAt: string;
  requestedDeliveryAt: string;
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
}

/** An order as the desk shows it, with the version every edit must send back. */
export interface OrderDesk {
  order: Row;
  version: string;
  header: OrderHeaderDraft;
  lines: OrderLineView[];
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
  abstract cancelOrder(orderId: string, reason: string): Promise<void>;
  /** Resolves to how many consignments were cut. */
  abstract generateConsignments(orderId: string): Promise<number>;
  /** Customer partners a 3PL contract may be held by. */
  abstract clientChoices(): Promise<Choice[]>;
  /** Every contract version of the client, newest first. */
  abstract clientContracts(clientId: string): Promise<Row[]>;
  /** The client's charges in the month holding `period` (a `YYYY-MM-DD` day). */
  abstract clientCharges(clientId: string, period: string): Promise<Row[]>;
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
