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
  AllocationRulesApi, AuthenticationApi, ControlTowerApi, Configuration, CustomFieldsApi, DeliveryAndClaimsApi, DriversApi,
  EventDeadLetterApi, LTLRatingApi, OrdersApi,
  MilkRunsApi, MultimodalApi, PartnersApi, PlanningApi, PublicTrackingApi, ResponseError, SettlementApi, TerminalsApi, SourcingApi, TelematicsApi, TrackingLinksApi, TripsApi, VehiclesApi, WebhooksApi,
  type ClaimStatus as ApiClaimStatus, type ClaimType as ApiClaimType, type Consignment, type CustomFieldDefinition,
  type CustomFieldEntityType, type CustomFieldType,
  type RegisterPartnerRequestPartnerTypeEnum,
  type AllocationRule, type AllocationRuleAction, type Load, type AllocationRuleRequest,
  type AddOrderLineRequest, type Order, type OrderLine, type UpdateOrderRequest,
  type AllocationRuleRequestMaterialClassEnum, type ListPartnersPartnerTypeEnum, type Terminal,
  type ParkedEvent, type ParkedEventStatus, type PublishLtlRateCardRequestRateBasisEnum,
  type WebhookDelivery, type WebhookEventType as ApiWebhookEventType, type WebhookSubscription,
} from '../api';
import { API_URL } from '../config';
import { DemoDataApi } from '../api';
import {
  ClientBillingApi, type ClientCharge, type ClientContract, type ClientInvoice, type ClientInvoiceLine,
  type ClientStatementEntry,
} from '../api';
import type { ClientAgeingView, ClientInvoiceDetail, CreditNoteDraft, PaymentDraft } from './lmsGateway';
// Commercial
import {
  type AddSlabRequestRateBasisEnum, type Allocation as CommercialAllocation, type DefineGuideRequestStrategyEnum,
  type FreightBill as CommercialFreightBill, type FreightBillLine as CommercialFreightBillLine,
  type Offer as CommercialOffer, type RoutingGuide as CommercialRoutingGuide,
} from '../api';
import type {
  AllocationDetail, FreightBillDetail, GuideDraft, GuideVendorDraft, RoutingGuideDetail, SlabDraft, TariffDraft,
} from './lmsGateway';
import type { DemoResetOutcome } from './lmsGateway';
import {
  ApiProblem, type AllocationRuleView, type ConsignmentPage, type LoadBuild, type AllocationSummary, type Choice, type ClaimDraft, type ClaimMove, type ConsignmentCard, type ControlTowerSummary, type CustomFieldKind, type CustomValues, type Credentials,
  type DeliveryProofView, type FieldChanges, type FieldDefinition, type FieldDraft, type FieldType, type LmsGateway,
  type IssuedTrackingLink, type OtpIssued, type PartnerDraft, type ProofDraft, type PublicTrackingView,
  type ResourceKey, type Row, type RuleAction, type RuleDraft, type RuleEvaluation, type SignedIn, type SplitPart, type SplitPartDraft,
  type GeoPoint, type MapFeature, type Mapped, type MappedDetail, type TrackingLinkView, type TripRoute,
  type ParkedEventDetail, type ParkedFilter, type RateCardDraft,
  type WebhookChanges, type WebhookDeliveryPage, type WebhookDraft, type WebhookSecret, type WebhookView,
  type OrderDesk, type OrderHeaderDraft, type OrderLineDraft, type OrderRaiseDraft,
} from './lmsGateway';
// Warehouse operations
import { ListTerminalsCategoryEnum, WarehouseApi } from '../api';
import type {
  AsnDraft, DispatchDraft, OutboundDraft, ReceiptCountDraft, SkuDraft, StockMoveDraft, WarehouseDocument,
} from './lmsGateway';
// Administration
import {
  AccessPoliciesApi, ComplianceApi, UsersApi, type AccessPolicy, type AttachDocumentRequest, type ComplianceDocument,
  type CreateAccessPolicyRequestEffectEnum, type ReviseAccessPolicyRequestEffectEnum, type SetUserAccessAttributesRequest,
  type SetUserAccessAttributesRequestFreightModesEnum, type User, type UserAccessAttributes,
} from '../api';
import type {
  AccessAttributesDraft, AccessFreightMode, AccessPolicyChanges, AccessPolicyDraft, AccessPolicyPage,
  AccessPolicyView, AdminUser, DocumentDraft, DocumentOwnerType, PolicyEffect,
} from './lmsGateway';
// Control tower
import {
  type Exception as ApiException, type ExceptionCategory, type ListExceptionsSeverityEnum,
} from '../api';
import type {
  DetectorSettingsView, ExceptionDraft, ExceptionFilter, TripActivity, TripWatchDraft, TripWatchView,
} from './lmsGateway';

/** A ticket as the exception desk lists and opens it. */
function ticketRow(e: ApiException): Row {
  return {
    id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
    category: text(e.category), description: text(e.description), source: text(e.source),
    tripId: text(e.tripId), tripNo: text(e.tripNo), registrationNo: text(e.registrationNo),
    subjectLabel: text(e.subjectLabel), assigneeName: text(e.assigneeName), raisedBy: text(e.raisedBy),
    raisedAt: text(e.raisedAt), resolution: text(e.resolution), resolvedBy: text(e.resolvedBy),
    closedBy: text(e.closedBy), suspendsSettlement: e.suspendsSettlement ? 'Yes' : '',
    positionSource: text(e.positionSource), lat: text(e.lat), lon: text(e.lon),
  };
}

/** A typed number, or nothing when the field was left blank. */
function decimalOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

/** A blank note is no note. */
function noteOf(note: string): { note: string } | undefined {
  return note.trim() ? { note: note.trim() } : undefined;
}

/** The call's result, or null when the API answers 404; any other failure as an ApiProblem. */
async function absentOn404<T>(call: () => Promise<T>): Promise<T | null> {
  try {
    return await guard(call);
  } catch (e) {
    if (e instanceof ApiProblem && e.status === 404) {
      return null;
    }
    throw e;
  }
}

/** A load as the building screen shows it. */
function loadRow(l: Load): Row {
  return {
    id: text(l.id), loadNo: text(l.loadNo), status: text(l.status), freightMode: text(l.freightMode),
    vehicleRegistrationNo: text(l.vehicleRegistrationNo), plannedWeightKg: text(l.plannedWeightKg),
    capacityWeightKg: text(l.capacityWeightKg), weightUtilisationPct: text(l.weightUtilisationPct),
    consignmentCount: text(l.consignmentCount),
  };
}

/** A consignment as a load list or a work queue shows it. */
function consignmentRow(c: Consignment): Row {
  return {
    id: text(c.id), trackingRef: text(c.trackingRef), status: text(c.status),
    consigneeName: text(c.consigneeName), destinationTerminalCode: text(c.destinationTerminalCode),
    totalDeadWeightKg: text(c.totalDeadWeightKg), materialClasses: text(c.materialClasses),
    containsHazmat: c.containsHazmat ? 'Yes' : '',
  };
}

/** A client contract version as the billing desk lists it. */
function contractRow(c: ClientContract): Row {
  return {
    id: text(c.id), code: text(c.code), currency: text(c.currency), effectiveFrom: text(c.effectiveFrom),
    effectiveTo: text(c.effectiveTo), transportRatePerKg: text(c.transportRatePerKg),
    handlingInRatePerPallet: text(c.handlingInRatePerPallet), handlingOutRatePerPallet: text(c.handlingOutRatePerPallet),
    storageRatePerPalletDay: text(c.storageRatePerPalletDay),
  };
}

/** A charge as the billing desk lists it; `invoiced` and `late` read Yes or nothing. */
function chargeRow(c: ClientCharge): Row {
  return {
    id: text(c.id), occurredOn: text(c.occurredOn), serviceType: text(c.serviceType), serviceCode: text(c.serviceCode),
    quantity: text(c.quantity), unit: text(c.unit), reference: text(c.reference),
    invoiced: c.invoiceId ? 'Yes' : '', late: c.late ? 'Yes' : '',
  };
}

function invoiceRow(i: ClientInvoice): Row {
  return {
    id: text(i.id), invoiceNo: text(i.invoiceNo), periodStart: text(i.periodStart), periodEnd: text(i.periodEnd),
    status: text(i.status), currency: text(i.currency), taxableAmount: text(i.taxableAmount),
    taxAmount: text(i.taxAmount), totalAmount: text(i.totalAmount), creditedAmount: text(i.creditedAmount),
    lineCount: text(i.lineCount), issuedAt: text(i.issuedAt), clientName: text(i.clientName),
  };
}

function invoiceLineRow(l: ClientInvoiceLine): Row {
  return {
    lineNo: text(l.lineNo), serviceType: text(l.serviceType), narrative: text(l.narrative),
    quantity: text(l.quantity), unit: text(l.unit), rate: text(l.rate), amount: text(l.amount),
    taxAmount: text(l.taxAmount), lineTotal: text(l.lineTotal), creditedAmount: text(l.creditedAmount),
  };
}

function statementRow(e: ClientStatementEntry): Row {
  return {
    id: text(e.documentId), entryType: text(e.entryType), documentNo: text(e.documentNo), issuedAt: text(e.issuedAt),
    invoiceNo: text(e.invoiceNo), reason: text(e.reason), debit: text(e.debit), credit: text(e.credit),
    balance: text(e.balance),
  };
}

/** A `YYYY-MM-DD` day as the generated client sends a date. */
function day(value: string): Date {
  return new Date(`${value}T00:00:00Z`);
}

/** A display cell: the contract's optional fields become an empty string. */
function text(v: unknown): string {
  if (v === null || v === undefined) {
    return '';
  }
  if (v instanceof Date) {
    const iso = v.toISOString();
    return iso.endsWith('T00:00:00.000Z') ? iso.slice(0, 10) : iso.slice(0, 16).replace('T', ' ');
  }
  return String(v);
}

function definitionOf(d: CustomFieldDefinition): FieldDefinition {
  return {
    id: d.id ?? '',
    kind: d.entityType as CustomFieldKind,
    key: d.key ?? '',
    label: d.label ?? '',
    type: d.fieldType as FieldType,
    required: d.required ?? false,
    options: d.options ?? [],
    active: d.active ?? true,
  };
}

function ruleOf(r: AllocationRule): AllocationRuleView {
  return {
    id: text(r.id), name: text(r.name), priority: r.priority ?? 0, active: r.active ?? true,
    originTerminalId: text(r.originTerminalId), destinationTerminalId: text(r.destinationTerminalId),
    minWeightKg: text(r.minWeightKg), maxWeightKg: text(r.maxWeightKg),
    materialClass: text(r.materialClass), action: (r.action ?? 'PREFER') as RuleAction,
    vendors: (r.vendors ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
  };
}

function consignmentCard(c: Consignment): ConsignmentCard {
  return {
    id: text(c.id), trackingRef: text(c.trackingRef), status: text(c.status), orderNo: text(c.orderNo),
    consigneeName: text(c.consigneeName), destinationTerminalCode: text(c.destinationTerminalCode),
    deadWeightKg: text(c.totalDeadWeightKg), volumeM3: text(c.totalVolumeM3),
    chargeableWeightKg: text(c.chargeableWeightKg), loadNo: text(c.loadNo), freightMode: text(c.freightMode),
    parentId: text(c.parentConsignmentId), parentTrackingRef: text(c.parentTrackingRef),
    partCount: c.partCount ?? null, partsDelivered: c.partsDelivered ?? null, partsCancelled: c.partsCancelled ?? null,
  };
}

/** The contract's request: a condition left out is sent as absent, not as an empty string. */
function ruleRequest(draft: RuleDraft): AllocationRuleRequest {
  return {
    name: draft.name, priority: draft.priority, active: draft.active,
    originTerminalId: draft.originTerminalId || undefined,
    destinationTerminalId: draft.destinationTerminalId || undefined,
    minWeightKg: draft.minWeightKg, maxWeightKg: draft.maxWeightKg,
    materialClass: (draft.materialClass || undefined) as AllocationRuleRequestMaterialClassEnum | undefined,
    action: draft.action as unknown as AllocationRuleAction,
    vendorPartnerIds: draft.vendorIds,
  };
}

/** A terminal as the map draws it: its polygon when it has one, else its centre and radius. */
function terminalFeature(t: Terminal, kind: 'site' | 'stop' | 'plant' = 'site'): MapFeature | null {
  const label = `${text(t.code)} · ${text(t.name)}`;
  const ring = polygonRing(t.polygon);
  if (ring && kind === 'site') {
    return { id: text(t.id), kind: 'area', label, points: ring };
  }
  if (t.centreLat === undefined || t.centreLat === null || t.centreLon === undefined || t.centreLon === null) {
    const centre = ring ? centroid(ring) : null;
    return centre ? { id: text(t.id), kind, label, points: [centre] } : null;
  }
  return {
    id: text(t.id), kind, label, points: [{ lat: t.centreLat, lon: t.centreLon }],
    radiusM: kind === 'site' && t.radiusM ? t.radiusM : undefined,
  };
}

/** The outer ring of a GeoJSON polygon, as map points; null when there is none. */
function polygonRing(geoJson: string | null | undefined): GeoPoint[] | null {
  if (!geoJson) {
    return null;
  }
  try {
    const shape = JSON.parse(geoJson) as { coordinates?: number[][][] };
    const ring = shape.coordinates?.[0];
    return ring && ring.length >= 3 ? ring.map(([lon, lat]) => ({ lat, lon })) : null;
  } catch {
    return null;
  }
}

function centroid(points: GeoPoint[]): GeoPoint {
  const lat = points.reduce((sum, p) => sum + p.lat, 0) / points.length;
  const lon = points.reduce((sum, p) => sum + p.lon, 0) / points.length;
  return { lat, lon };
}

/** A point on a terminal for drawing a line to it: its centre, or the middle of its polygon. */
function anchorOf(t: Terminal | undefined): GeoPoint | null {
  if (!t) {
    return null;
  }
  if (t.centreLat !== undefined && t.centreLat !== null && t.centreLon !== undefined && t.centreLon !== null) {
    return { lat: t.centreLat, lon: t.centreLon };
  }
  const ring = polygonRing(t.polygon);
  return ring ? centroid(ring) : null;
}

/** A parked event as a list row. */
function parkedRow(e: ParkedEvent): Row {
  return {
    id: text(e.id), parkedNo: text(e.parkedNo), eventType: text(e.eventType), listenerId: text(e.listenerId),
    status: text(e.status), attempts: text(e.attempts), lastFailedAt: text(e.lastFailedAt),
    lastError: text(e.lastError), parkedAt: text(e.parkedAt), resolvedAt: text(e.resolvedAt),
    resolutionNote: text(e.resolutionNote),
  };
}

function webhookOf(w: WebhookSubscription): WebhookView {
  return {
    id: text(w.id), name: text(w.name), url: text(w.url), eventTypes: w.eventTypes ?? [],
    active: w.active ?? false, pausedReason: text(w.pausedReason), consecutiveFailures: w.consecutiveFailures ?? 0,
  };
}

/** A delivery as a list row. */
function deliveryRow(d: WebhookDelivery): Row {
  return {
    id: text(d.id), eventType: text(d.eventType), status: text(d.status), attempts: text(d.attempts),
    lastStatusCode: text(d.lastStatusCode), lastError: text(d.lastError), nextAttemptAt: text(d.nextAttemptAt),
    deliveredAt: text(d.deliveredAt), createdAt: text(d.createdAt),
  };
}

/** A draft's optional value, left out of the request when blank. */
function blankAsAbsent(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

function numberOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

function instantOrAbsent(value: string): Date | undefined {
  const trimmed = value.trim();
  return trimmed ? new Date(trimmed) : undefined;
}

/** An instant as a draft holds it, to the second, or blank. */
function isoOf(d: Date | null | undefined): string {
  return d ? d.toISOString().replace(/\.\d{3}Z$/, 'Z') : '';
}

function headerRequest(h: OrderHeaderDraft): UpdateOrderRequest {
  return {
    customerPartnerId: h.customerPartnerId.trim(), originTerminalId: h.originTerminalId.trim(),
    requestedPickupAt: instantOrAbsent(h.requestedPickupAt),
    requestedDeliveryAt: instantOrAbsent(h.requestedDeliveryAt),
    originHubTerminalId: blankAsAbsent(h.originHubTerminalId),
    destinationHubTerminalId: blankAsAbsent(h.destinationHubTerminalId),
  };
}

function lineRequest(l: OrderLineDraft): AddOrderLineRequest {
  return {
    lineNo: l.lineNo, materialCode: l.materialCode.trim(), materialDescription: l.materialDescription.trim(),
    materialClass: l.materialClass, hazmatUnCode: blankAsAbsent(l.hazmatUnCode), quantity: Number(l.quantity),
    uom: l.uom.trim(), deadWeightKg: Number(l.deadWeightKg), lengthM: numberOrAbsent(l.lengthM),
    widthM: numberOrAbsent(l.widthM), heightM: numberOrAbsent(l.heightM),
    volumetricDivisor: numberOrAbsent(l.volumetricDivisor), consigneePartnerId: blankAsAbsent(l.consigneePartnerId),
    destinationTerminalId: l.destinationTerminalId.trim(),
  };
}

/** An order and its lines as the desk shows them, each line ready to be restated by an edit. */
function orderDeskOf(o: Order, lines: OrderLine[]): OrderDesk {
  return {
    order: {
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status), customerName: text(o.customerName),
      originTerminalCode: text(o.originTerminalCode), freightMode: text(o.freightMode),
      lineCount: text(o.lineCount), plannedLineCount: text(o.plannedLineCount),
      totalDeadWeightKg: text(o.totalDeadWeightKg), containsHazmat: o.containsHazmat ? 'Yes' : '',
      requestedPickupAt: text(o.requestedPickupAt), requestedDeliveryAt: text(o.requestedDeliveryAt),
    },
    version: text(o.version),
    header: {
      customerPartnerId: text(o.customerPartnerId), originTerminalId: text(o.originTerminalId),
      requestedPickupAt: isoOf(o.requestedPickupAt), requestedDeliveryAt: isoOf(o.requestedDeliveryAt),
      originHubTerminalId: text(o.originHubTerminalId), destinationHubTerminalId: text(o.destinationHubTerminalId),
    },
    lines: lines.map((l) => ({
      id: text(l.id), lineNo: l.lineNo ?? 0, materialCode: text(l.materialCode),
      materialDescription: text(l.materialDescription), materialClass: text(l.materialClass),
      hazmatUnCode: text(l.hazmatUnCode), quantity: text(l.quantity), uom: text(l.uom),
      deadWeightKg: text(l.deadWeightKg), lengthM: text(l.lengthM), widthM: text(l.widthM),
      heightM: text(l.heightM), volumetricDivisor: text(l.volumetricDivisor),
      consigneePartnerId: text(l.consigneePartnerId), destinationTerminalId: text(l.destinationTerminalId),
      consigneeName: text(l.consigneeName), destinationTerminalCode: text(l.destinationTerminalCode),
      planned: l.planned ?? false,
    })),
  };
}

/** The adapter: the only file in the React app that imports the generated client. */
export function createGeneratedGateway(getToken: () => string | null): LmsGateway {
  // The token is read per request (a function), so one gateway outlives sign-ins.
  const config = new Configuration({ basePath: API_URL, accessToken: () => getToken() ?? '' });
  const auth = new AuthenticationApi(config);
  const controlTower = new ControlTowerApi(config);
  const demo = new DemoDataApi(config);
  const partners = new PartnersApi(config);
  const vehicles = new VehiclesApi(config);
  const drivers = new DriversApi(config);
  const terminals = new TerminalsApi(config);
  const orders = new OrdersApi(config);
  const loads = new PlanningApi(config);
  const trips = new TripsApi(config);
  const freightBills = new SettlementApi(config);
  const customFields = new CustomFieldsApi(config);
  const telematics = new TelematicsApi(config);
  const multimodal = new MultimodalApi(config);
  const milkRuns = new MilkRunsApi(config);
  const ltl = new LTLRatingApi(config);
  const deadLetters = new EventDeadLetterApi(config);
  const webhooks = new WebhooksApi(config);
  const delivery = new DeliveryAndClaimsApi(config);
  const links = new TrackingLinksApi(config);
  const rules = new AllocationRulesApi(config);
  const sourcing = new SourcingApi(config);
  const clientBilling = new ClientBillingApi(config);
  // The public read is made with no credentials at all: a customer has none, and
  // a console left signed in must not send its token to a route that ignores it.
  const publicTracking = new PublicTrackingApi(new Configuration({ basePath: API_URL }));

  const listClaims = async (consignmentId?: string) =>
    (await delivery.listClaims({ consignmentId: consignmentId || undefined, limit: 50 })).items.map((c) => ({
      id: text(c.id), claimNo: text(c.claimNo), claimType: text(c.claimType), amount: text(c.amount),
      status: text(c.status), consignmentId: text(c.consignmentId),
      approvedAmount: text(c.approvedAmount), description: text(c.description),
      resolutionNote: text(c.resolutionNote) }));

  const listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: async () => (await partners.listPartners({ limit: 50 })).items.map((p) => ({
      id: text(p.id), code: text(p.code), legalName: text(p.legalName),
      partnerType: text(p.partnerType), status: text(p.status) })),
    vehicles: async () => (await vehicles.listVehicles({ limit: 50 })).items.map((v) => ({
      id: text(v.id), registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType),
      status: text(v.status), payloadCapacityKg: text(v.payloadCapacityKg) })),
    drivers: async () => (await drivers.listDrivers({ limit: 50 })).items.map((d) => ({
      id: text(d.id), fullName: text(d.fullName), licenceNo: text(d.licenceNo),
      licenceExpiresOn: text(d.licenceExpiresOn), status: text(d.status) })),
    terminals: async () => (await terminals.listTerminals({ limit: 50 })).items.map((t) => ({
      id: text(t.id), code: text(t.code), name: text(t.name),
      functionalCategory: text(t.functionalCategory), dockCount: text(t.dockCount) })),
    orders: async () => (await orders.listOrders({ limit: 50 })).items.map((o) => ({
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status),
      customerName: text(o.customerName), originTerminalCode: text(o.originTerminalCode) })),
    loads: async () => (await loads.listLoads({ limit: 50 })).items.map((l) => ({
      id: text(l.id), loadNo: text(l.loadNo), status: text(l.status),
      vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) })),
    trips: async () => (await trips.listTrips({ limit: 50 })).items.map((t) => ({
      id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) })),
    'freight-bills': async () => (await freightBills.listFreightBills({ limit: 50 })).items.map((b) => ({
      id: text(b.id), billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) })),
    exceptions: async () => (await controlTower.listExceptions({ limit: 50 })).items.map((e) => ({
      id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
      category: text(e.category), description: text(e.description) })),
    claims: () => listClaims(),
  };

  return {
    signIn: (credentials: Credentials): Promise<SignedIn> =>
      guard(async () => {
        const r = await auth.login({ loginRequest: credentials });
        return { token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissions: r.permissions ?? [] };
      }),

    summary: (): Promise<ControlTowerSummary> =>
      guard(async () => {
        const s = await controlTower.summariseExceptions();
        return {
          openP1: s.openP1 ?? 0, openP2: s.openP2 ?? 0, openP3: s.openP3 ?? 0,
          unassigned: s.unassigned ?? 0, settlementHolds: s.settlementHolds ?? 0,
          openWithoutTrip: s.openWithoutTrip ?? 0,
        };
      }),

    resetDemoData: (confirm: string): Promise<DemoResetOutcome> =>
      guard(async () => {
        const r = await demo.resetDemoData({ demoResetRequest: { confirm } });
        return { tenantCode: r.tenantCode, rowsDeleted: r.rowsDeleted, signInAgain: r.signInAgain };
      }),

    list: (resource) => guard(listers[resource]),
    claims: (consignmentId?: string) => guard(() => listClaims(consignmentId)),

    registerPartner: (orgUnitId: string, draft: PartnerDraft) =>
      guard(async () => {
        await partners.registerPartner({
          registerPartnerRequest: {
            orgUnitId, code: draft.code, legalName: draft.legalName,
            partnerType: draft.partnerType as RegisterPartnerRequestPartnerTypeEnum,
          },
        });
      }),

    listFields: (kind?: CustomFieldKind) =>
      guard(async () => {
        const slice = await customFields.listCustomFieldDefinitions({
          entityType: kind as CustomFieldEntityType | undefined,
        });
        return slice.items.map(definitionOf);
      }),

    defineField: (draft: FieldDraft) =>
      guard(async () => {
        await customFields.defineCustomField({
          defineCustomFieldRequest: {
            entityType: draft.kind as CustomFieldEntityType, key: draft.key, label: draft.label,
            fieldType: draft.type as CustomFieldType, required: draft.required,
            options: draft.options,
          },
        });
      }),

    reviseField: (id: string, changes: FieldChanges) =>
      guard(async () => {
        await customFields.reviseCustomField({
          id,
          reviseCustomFieldRequest: {
            label: changes.label, required: changes.required, options: changes.options,
            active: changes.active,
          },
        });
      }),

    customValues: (kind: CustomFieldKind, recordId: string) =>
      guard(async () => {
        const got = await customFields.getCustomFieldValues({
          entityType: kind as CustomFieldEntityType, entityId: recordId,
        });
        return got.values as CustomValues;
      }),

    saveCustomValues: (kind: CustomFieldKind, recordId: string, values: CustomValues) =>
      guard(async () => {
        const saved = await customFields.setCustomFieldValues({
          entityType: kind as CustomFieldEntityType, entityId: recordId, customFieldValues: { values },
        });
        return saved.values as CustomValues;
      }),

    raiseClaim: (draft: ClaimDraft) =>
      guard(async () => {
        const made = await delivery.raiseClaim({
          raiseClaimRequest: {
            consignmentId: draft.consignmentId, claimType: draft.claimType as ApiClaimType,
            amount: draft.amount, description: draft.description,
          },
        });
        return made.id ?? '';
      }),

    transitionClaim: (id: string, move: ClaimMove) =>
      guard(async () => {
        await delivery.transitionClaim({
          id,
          transitionClaimRequest: {
            target: move.target as ApiClaimStatus, approvedAmount: move.approvedAmount,
            resolutionNote: move.resolutionNote,
          },
        });
      }),

    issueDeliveryOtp: (consignmentId: string): Promise<OtpIssued> =>
      guard(async () => {
        const issued = await delivery.issueDeliveryOtp({ consignmentId });
        return { expiresAt: text(issued.expiresAt), otp: issued.otp };
      }),

    recordDeliveryProof: (consignmentId: string, draft: ProofDraft) =>
      guard(async () => {
        await delivery.recordDeliveryProof({
          consignmentId,
          recordDeliveryProofRequest: {
            otp: draft.otp, recipientName: draft.recipientName, photoRef: draft.photoRef || undefined,
          },
        });
      }),

    deliveryProof: (consignmentId: string): Promise<DeliveryProofView | null> =>
      guard(async () => {
        try {
          const p = await delivery.getDeliveryProof({ consignmentId });
          return {
            consignmentId: p.consignmentId ?? consignmentId,
            status: p.status === 'CONFIRMED' ? 'CONFIRMED' : 'OTP_ISSUED',
            recipientName: text(p.recipientName), photoRef: text(p.photoRef),
            deliveredAt: text(p.deliveredAt), otpExpiresAt: text(p.otpExpiresAt),
          };
        } catch (e) {
          if (e instanceof ResponseError && e.response.status === 404) {
            return null;
          }
          throw e;
        }
      }),

    consignmentByRef: (trackingRef: string): Promise<ConsignmentCard> =>
      guard(async () => consignmentCard(await loads.trackConsignment({ trackingRef }))),

    consignment: (id: string): Promise<ConsignmentCard> =>
      guard(async () => consignmentCard(await loads.getConsignment({ id }))),

    consignmentParts: (id: string): Promise<ConsignmentCard[]> =>
      guard(async () => {
        // At most 500 parts, so at most three pages: read them all.
        const parts: ConsignmentCard[] = [];
        let cursor: string | undefined;
        do {
          const page = await loads.listConsignmentParts({ id, cursor, limit: 200 });
          parts.push(...page.items.map(consignmentCard));
          cursor = page.hasMore ? page.nextCursor ?? undefined : undefined;
        } while (cursor);
        return parts;
      }),

    splitConsignment: (id: string, parts: SplitPartDraft[]): Promise<SplitPart[]> =>
      guard(async () => (await loads.splitConsignment({
        id,
        splitConsignmentRequest: {
          parts: parts.map((p) => ({ deadWeightKg: Number(p.deadWeightKg), volumeM3: Number(p.volumeM3) })),
        },
      })).map((p) => ({ id: p.id, trackingRef: p.trackingRef }))),

    issueTrackingLink: (consignmentId: string, expiresInDays?: number): Promise<IssuedTrackingLink> =>
      guard(async () => {
        const issued = await links.issueTrackingLink({
          consignmentId, issueTrackingLinkRequest: expiresInDays ? { expiresInDays } : undefined,
        });
        return { id: issued.id, token: issued.token, expiresAt: text(issued.expiresAt) };
      }),

    trackingLinks: (consignmentId: string): Promise<TrackingLinkView[]> =>
      guard(async () => (await links.listTrackingLinks({ consignmentId })).items.map((l) => ({
        id: text(l.id), createdAt: text(l.createdAt), expiresAt: text(l.expiresAt),
        revokedAt: text(l.revokedAt), status: (l.status ?? 'ACTIVE') as TrackingLinkView['status'],
      }))),

    revokeTrackingLink: (id: string) =>
      guard(async () => {
        await links.revokeTrackingLink({ id });
      }),

    publicTracking: (token: string): Promise<PublicTrackingView | null> =>
      guard(async () => {
        try {
          const t = await publicTracking.getPublicTracking({ token });
          return {
            reference: text(t.reference), status: text(t.status), originName: text(t.originName),
            destinationName: text(t.destinationName), requestedDeliveryAt: text(t.requestedDeliveryAt),
            deliveredAt: text(t.deliveredAt), proofRecorded: t.proofRecorded ?? false,
            position: t.position
              ? { lat: text(t.position.lat), lon: text(t.position.lon), recordedAt: text(t.position.recordedAt) }
              : null,
            partsTotal: t.partsTotal ?? null, partsDelivered: t.partsDelivered ?? null,
          };
        } catch (e) {
          if (e instanceof ResponseError && e.response.status === 404) {
            return null;
          }
          throw e;
        }
      }),

    allocationRules: (): Promise<AllocationRuleView[]> =>
      guard(async () => (await rules.listAllocationRules({ limit: 200 })).items.map(ruleOf)),

    createAllocationRule: (draft: RuleDraft) =>
      guard(async () => (await rules.createAllocationRule({ allocationRuleRequest: ruleRequest(draft) })).id ?? ''),

    reviseAllocationRule: (id: string, draft: RuleDraft) =>
      guard(async () => {
        await rules.reviseAllocationRule({ id, allocationRuleRequest: ruleRequest(draft) });
      }),

    evaluateAllocation: (loadId: string): Promise<RuleEvaluation> =>
      guard(async () => {
        const e = await rules.evaluateAllocationRules({ allocationEvaluationRequest: { loadId } });
        return {
          loadNo: text(e.loadNo), ruleName: text(e.matchedRuleName),
          action: (e.matchedRuleId ? text(e.action) : '') as RuleAction | '',
          nextVendor: text(e.nextVendor?.vendorName),
          candidates: (e.candidateOrder ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
        };
      }),

    allocation: (id: string): Promise<AllocationSummary> =>
      guard(async () => {
        const a = await sourcing.getAllocation({ id });
        return {
          id: text(a.id), loadNo: text(a.loadNo), status: text(a.status),
          appliedRuleName: text(a.appliedRuleName),
          appliedRuleAction: (a.appliedRuleId ? text(a.appliedRuleAction) : '') as RuleAction | '',
        };
      }),

    activeVendors: (): Promise<Choice[]> =>
      guard(async () => (await partners.listPartners({
        partnerType: 'VENDOR' as unknown as ListPartnersPartnerTypeEnum, status: 'ACTIVE', limit: 200,
      })).items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` }))),

    terminalChoices: (): Promise<Choice[]> =>
      guard(async () => (await terminals.listTerminals({ limit: 200 })).items.map((t) => ({
        id: text(t.id), label: `${text(t.code)} · ${text(t.name)}`,
      }))),

    networkMap: (): Promise<Mapped> =>
      guard(async () => {
        const [sites, positions] = await Promise.all([
          terminals.listTerminals({ limit: 200 }), telematics.listPositions(),
        ]);
        const rows = sites.items.map((t) => ({
          id: text(t.id), code: text(t.code), name: text(t.name), functionalCategory: text(t.functionalCategory),
          geofenceType: text(t.geofenceType),
        }));
        const features: MapFeature[] = sites.items.map((t) => terminalFeature(t)).filter((f): f is MapFeature => f !== null);
        for (const v of positions) {
          if (v.lat !== undefined && v.lon !== undefined) {
            features.push({
              id: text(v.vehicleId), kind: 'vehicle', label: `${text(v.registrationNo)}${v.tripNo ? ` · ${v.tripNo}` : ''}`,
              points: [{ lat: v.lat, lon: v.lon }],
            });
          }
        }
        return { rows, features };
      }),

    tripRoute: (tripId: string): Promise<TripRoute> =>
      guard(async () => {
        const trip = await trips.getTrip({ id: tripId });
        const [track, progress] = await Promise.all([
          telematics.getTrack({ tripId }),
          telematics.getProgress({ tripId }).catch(() => null),
        ]);
        const features: MapFeature[] = [];
        if (track.length >= 2) {
          features.push({
            id: `${tripId}-track`, kind: 'track', label: `Track of ${text(trip.tripNo)}`,
            points: track.map(([lon, lat]) => ({ lat, lon })),
          });
        }
        if (progress && progress.lat !== undefined && progress.lon !== undefined) {
          features.push({
            id: `${tripId}-position`, kind: 'position', label: `${text(trip.tripNo)} now`,
            points: [{ lat: progress.lat, lon: progress.lon }],
          });
        }
        return {
          tripNo: text(trip.tripNo), status: text(trip.status), originCode: text(trip.originTerminalCode),
          destinationCode: text(trip.destinationTerminalCode), lastFixAt: text(progress?.lastFixAt),
          completionPct: text(progress?.completionPct), features,
        };
      }),

    itineraries: () =>
      guard(async () => (await multimodal.listItineraries({ limit: 50 })).items.map((i) => ({
        id: text(i.id), itineraryNo: text(i.itineraryNo), shipmentRef: text(i.shipmentRef), status: text(i.status),
        legCount: text(i.legCount), totalCost: text(i.totalCost),
        connectionMissed: i.connectionMissed ? 'Missed' : '',
      }))),

    itinerary: (id: string): Promise<MappedDetail> =>
      guard(async () => {
        const i = await multimodal.getItinerary({ id });
        const legs = [...(i.legs ?? [])].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
        const ids = [...new Set(legs.flatMap((l) => [l.fromTerminalId, l.toTerminalId]).filter(Boolean) as string[])];
        const sites = new Map((await Promise.all(ids.map((t) => terminals.getTerminal({ id: t })))).map((t) => [t.id, t]));
        const features: MapFeature[] = [];
        for (const l of legs) {
          const from = anchorOf(sites.get(l.fromTerminalId ?? ''));
          const to = anchorOf(sites.get(l.toTerminalId ?? ''));
          if (from && to) {
            features.push({
              id: text(l.id), kind: l.mode === 'RAIL' ? 'leg-RAIL' : 'leg-ROAD',
              label: `Leg ${text(l.seq)}: ${text(l.fromTerminalCode)} → ${text(l.toTerminalCode)} (${text(l.mode)})`,
              points: [from, to],
            });
          }
        }
        for (const t of sites.values()) {
          const f = terminalFeature(t, 'stop');
          if (f) {
            features.push(f);
          }
        }
        return {
          header: {
            id: text(i.id), itineraryNo: text(i.itineraryNo), shipmentRef: text(i.shipmentRef), status: text(i.status),
            currency: text(i.currency), totalCost: text(i.totalCost), connectionMissed: i.connectionMissed ? 'Missed' : '',
          },
          parts: legs.map((l) => ({
            id: text(l.id), seq: text(l.seq), mode: text(l.mode), status: text(l.status),
            from: text(l.fromTerminalCode), to: text(l.toTerminalCode),
            plannedDepartureAt: text(l.plannedDepartureAt), plannedArrivalAt: text(l.plannedArrivalAt),
            carrier: text(l.carrierCode), reference: text(l.trainNo || l.bookingRef || l.tripNo),
            departedAt: text(l.departedAt), arrivedAt: text(l.arrivedAt),
          })),
          features,
        };
      }),

    milkRunRoutes: () =>
      guard(async () => (await milkRuns.listMilkRunRoutes({ limit: 50 })).items.map((r) => ({
        id: text(r.id), code: text(r.code), name: text(r.name), plantCode: text(r.plantCode),
        daysOfWeek: text(r.daysOfWeek), departureTime: text(r.departureTime), effectiveFrom: text(r.effectiveFrom),
      }))),

    milkRunRoute: (id: string): Promise<MappedDetail> =>
      guard(async () => {
        const [route, stops] = await Promise.all([
          milkRuns.getMilkRunRoute({ id }), milkRuns.getMilkRunRouteStops({ id }),
        ]);
        const ordered = [...stops].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
        const ids = [...new Set([route.plantTerminalId, ...ordered.map((s) => s.terminalId)].filter(Boolean) as string[])];
        const sites = new Map((await Promise.all(ids.map((t) => terminals.getTerminal({ id: t })))).map((t) => [t.id, t]));
        const features: MapFeature[] = [];
        const plant = sites.get(route.plantTerminalId ?? '');
        const plantFeature = plant ? terminalFeature(plant, 'plant') : null;
        if (plantFeature) {
          features.push(plantFeature);
        }
        const circuit: GeoPoint[] = [];
        const plantAnchor = anchorOf(plant);
        if (plantAnchor) {
          circuit.push(plantAnchor);
        }
        for (const s of ordered) {
          const t = sites.get(s.terminalId ?? '');
          const at = anchorOf(t);
          if (at) {
            circuit.push(at);
            features.push({
              id: `${text(s.terminalId)}-${text(s.sequence)}`, kind: 'stop',
              label: `Stop ${text(s.sequence)}: ${text(s.terminalCode)}`, points: [at],
            });
          }
        }
        if (plantAnchor && circuit.length > 1) {
          circuit.push(plantAnchor);
        }
        if (circuit.length >= 2) {
          features.push({ id: `${id}-circuit`, kind: 'leg-ROAD', label: `Circuit ${text(route.code)}`, points: circuit });
        }
        return {
          header: {
            id: text(route.id), code: text(route.code), name: text(route.name), plantCode: text(route.plantCode),
            daysOfWeek: text(route.daysOfWeek), departureTime: text(route.departureTime), timeZone: text(route.timeZone),
            runRate: text(route.runRate), perStopFee: text(route.perStopFee), currency: text(route.currency),
          },
          parts: ordered.map((s) => ({
            id: `${text(s.terminalId)}-${text(s.sequence)}`, sequence: text(s.sequence), terminalCode: text(s.terminalCode),
            terminalName: text(s.terminalName), windowOpenMin: text(s.windowOpenMin), windowCloseMin: text(s.windowCloseMin),
            plannedDwellMin: text(s.plannedDwellMin),
          })),
          features,
        };
      }),

    circuitRuns: (routeCode: string) =>
      guard(async () => (await milkRuns.listCircuitRuns({ routeCode, limit: 50 })).items.map((r) => ({
        id: text(r.id), runDate: text(r.runDate), status: text(r.status), tripNo: text(r.tripNo),
        stopsVisited: text(r.stopsVisited), stopsSkipped: text(r.stopsSkipped), chargeAmount: text(r.chargeAmount),
      }))),

    ltlReceivables: () =>
      guard(async () => (await ltl.listDocketReceivables({ limit: 50 })).items.map((r) => ({
        id: text(r.id), docketNo: text(r.docketNo), status: text(r.status), customerName: text(r.customerName),
        lane: `${text(r.originZone)} → ${text(r.destinationZone)}`, rateCardCode: text(r.rateCardCode),
        chargeableWeightKg: text(r.chargeableWeightKg), totalAmount: text(r.totalAmount), currency: text(r.currency),
      }))),

    assignLtlZone: (terminalId: string, zoneCode: string, outOfDeliveryArea: boolean) =>
      guard(async () => {
        await ltl.assignLtlZone({ terminalId, assignLtlZoneRequest: { zoneCode, outOfDeliveryArea } });
      }),

    publishLtlRateCard: (draft: RateCardDraft) =>
      guard(async () => (await ltl.publishLtlRateCard({
        publishLtlRateCardRequest: {
          code: draft.code, originZone: draft.originZone, destinationZone: draft.destinationZone,
          effectiveFrom: new Date(`${draft.effectiveFrom}T00:00:00Z`),
          rateBasis: draft.rateBasis as PublishLtlRateCardRequestRateBasisEnum, rateAmount: draft.rateAmount,
          kgPerCft: draft.kgPerCft, minimumChargeableWeightKg: draft.minimumChargeableWeightKg,
          docketCharge: draft.docketCharge,
        },
      })).id ?? ''),

    parkedEvents: (filter: ParkedFilter) =>
      guard(async () => (await deadLetters.listParkedEvents({
        status: filter.status as ParkedEventStatus | undefined, eventType: filter.eventType || undefined,
        listenerId: filter.listenerId || undefined, limit: 50,
      })).items.map(parkedRow)),

    parkedEvent: (id: string): Promise<ParkedEventDetail> =>
      guard(async () => {
        const d = await deadLetters.getParkedEvent({ id });
        return { summary: parkedRow(d.summary), payload: text(d.payload) };
      }),

    replayParkedEvent: (id: string) =>
      guard(async () => {
        const r = await deadLetters.replayParkedEvent({ id });
        return { outcome: text(r.outcome), attempts: r.attempts ?? 0 };
      }),

    discardParkedEvent: (id: string, reason: string) =>
      guard(async () => {
        await deadLetters.discardParkedEvent({ id, discardParkedEventRequest: { reason } });
      }),

    webhooks: () =>
      guard(async () => (await webhooks.listWebhookSubscriptions({ limit: 200 })).items.map(webhookOf)),

    subscribeWebhook: (draft: WebhookDraft): Promise<WebhookSecret> =>
      guard(async () => {
        const r = await webhooks.subscribeWebhook({
          webhookSubscriptionRequest: {
            name: draft.name, url: draft.url, eventTypes: draft.eventTypes as ApiWebhookEventType[],
          },
        });
        return { id: r.id, secret: r.secret };
      }),

    reviseWebhook: (id: string, changes: WebhookChanges) =>
      guard(async () => {
        await webhooks.reviseWebhookSubscription({
          id,
          webhookRevisionRequest: {
            name: changes.name, url: changes.url, eventTypes: changes.eventTypes as ApiWebhookEventType[],
            active: changes.active,
          },
        });
      }),

    rotateWebhookSecret: (id: string): Promise<WebhookSecret> =>
      guard(async () => {
        const r = await webhooks.rotateWebhookSecret({ id });
        return { id: r.id, secret: r.secret };
      }),

    pingWebhook: (id: string) =>
      guard(async () => (await webhooks.pingWebhook({ id })).deliveryId),

    webhookDeliveries: (id: string, cursor?: string): Promise<WebhookDeliveryPage> =>
      guard(async () => {
        const page = await webhooks.listWebhookDeliveries({ id, cursor, limit: 20 });
        return { rows: page.items.map(deliveryRow), nextCursor: page.nextCursor ?? null };
      }),

    redeliverWebhook: (deliveryId: string) =>
      guard(async () => {
        await webhooks.redeliverWebhook({ id: deliveryId });
      }),

    loadBuild: (loadId: string): Promise<LoadBuild> =>
      guard(async () => {
        const [load, aboard] = await Promise.all([
          loads.getLoad({ id: loadId }), loads.listLoadConsignments({ id: loadId })]);
        return { load: loadRow(load), aboard: aboard.map(consignmentRow) };
      }),
    unassignedConsignments: (orderId?: string, cursor?: string): Promise<ConsignmentPage> =>
      guard(async () => {
        const page = await loads.listUnassignedConsignments({ orderId: orderId || undefined, cursor, limit: 200 });
        return { rows: page.items.map(consignmentRow), nextCursor: page.nextCursor ?? null };
      }),
    assignConsignments: (loadId: string, consignmentIds: string[]) =>
      guard(async () => {
        await loads.assignConsignmentsInBulk({ id: loadId, bulkAssignConsignmentsRequest: { consignmentIds } });
      }),
    orderDesk: (orderId: string): Promise<OrderDesk> =>
      guard(async () => {
        const [order, lines] = await Promise.all([
          orders.getOrder({ id: orderId }), orders.listOrderLines({ id: orderId })]);
        return orderDeskOf(order, lines);
      }),
    raiseOrder: (orgUnitId: string, draft: OrderRaiseDraft) =>
      guard(async () => text((await orders.raiseOrder({
        raiseOrderRequest: {
          orgUnitId, orderNo: draft.orderNo.trim(), customerPartnerId: draft.customerPartnerId.trim(),
          originTerminalId: draft.originTerminalId.trim(), requestedPickupAt: instantOrAbsent(draft.requestedPickupAt),
          requestedDeliveryAt: instantOrAbsent(draft.requestedDeliveryAt),
        },
      })).id)),
    reviseOrderHeader: (orderId: string, version: string, header: OrderHeaderDraft) =>
      guard(async () => {
        await orders.updateOrder({ id: orderId, ifMatch: version, updateOrderRequest: headerRequest(header) });
      }),
    replaceOrderLines: (orderId: string, version: string, lines: OrderLineDraft[]) =>
      guard(async () => {
        await orders.replaceOrderLines({
          id: orderId, ifMatch: version, replaceOrderLinesRequest: { lines: lines.map(lineRequest) },
        });
      }),
    removeOrderLine: (orderId: string, version: string, lineId: string) =>
      guard(async () => {
        await orders.removeOrderLine({ id: orderId, ifMatch: version, lineId });
      }),
    amendOrder: (orderId: string, version: string, header: OrderHeaderDraft | null, lines: OrderLineDraft[] | null) =>
      guard(async () => {
        await orders.amendOrder({
          id: orderId, ifMatch: version,
          amendOrderRequest: {
            header: header ? headerRequest(header) : undefined, lines: lines ? lines.map(lineRequest) : undefined,
          },
        });
      }),
    validateOrder: (orderId: string) =>
      guard(async () => {
        await orders.validateOrder({ id: orderId });
      }),
    cancelOrder: (orderId: string, reason: string) =>
      guard(async () => {
        await orders.cancelOrder({ id: orderId, cancelOrderRequest: { reason: blankAsAbsent(reason) } });
      }),
    generateConsignments: (orderId: string) =>
      guard(async () => (await loads.generateConsignments({ id: orderId })).length),

    clientChoices: (): Promise<Choice[]> =>
      guard(async () => (await partners.listPartners({
        partnerType: 'CUSTOMER' as unknown as ListPartnersPartnerTypeEnum, limit: 200,
      })).items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` }))),
    clientContracts: (clientId: string) =>
      guard(async () => (await clientBilling.listClientContracts({ clientId })).map(contractRow)),
    clientCharges: (clientId: string, period: string) =>
      guard(async () => (await clientBilling.listClientCharges({ clientId, period: day(period), limit: 200 }))
        .items.map(chargeRow)),
    runClientBilling: (clientId: string, period: string) =>
      guard(async () => (await clientBilling.runClientBilling({
        clientId, clientBillingRunRequest: { period: day(period) },
      })).id),
    clientInvoices: (clientId: string) =>
      guard(async () => (await clientBilling.listClientInvoices({ clientId, limit: 100 })).items.map(invoiceRow)),
    clientInvoice: (invoiceId: string): Promise<ClientInvoiceDetail> =>
      guard(async () => {
        const [invoice, lines] = await Promise.all([
          clientBilling.getClientInvoice({ id: invoiceId }), clientBilling.getClientInvoiceLines({ id: invoiceId })]);
        return { invoice: invoiceRow(invoice), lines: lines.map(invoiceLineRow) };
      }),
    recordClientPayment: (clientId: string, draft: PaymentDraft) =>
      guard(async () => {
        await clientBilling.recordClientPayment({
          clientId,
          clientPaymentRequest: { amount: draft.amount, receivedOn: day(draft.receivedOn), reference: draft.reference },
        });
      }),
    issueClientCreditNote: (invoiceId: string, draft: CreditNoteDraft) =>
      guard(async () => {
        const issued = await clientBilling.issueClientCreditNote({
          id: invoiceId, clientCreditNoteRequest: { reason: draft.reason, amount: draft.amount },
        });
        return text((await clientBilling.getClientCreditNote({ id: issued.id })).creditNoteNo);
      }),
    clientStatement: (clientId: string) =>
      guard(async () => (await clientBilling.getClientStatement({ clientId, limit: 200 })).items.map(statementRow)),
    clientAgeing: (clientId: string): Promise<ClientAgeingView> =>
      guard(async () => {
        const a = await clientBilling.getClientAgeing({ clientId });
        return {
          currency: text(a.currency), outstanding: text(a.outstanding), unappliedCredit: text(a.unappliedCredit),
          buckets: {
            '0-30': text(a.buckets?.days0to30), '31-60': text(a.buckets?.days31to60),
            '61-90': text(a.buckets?.days61to90), '90+': text(a.buckets?.over90),
          },
          invoices: (a.invoices ?? []).map((i) => ({
            id: text(i.invoiceId), invoiceNo: text(i.invoiceNo), issuedOn: text(i.issuedOn), total: text(i.total),
            credited: text(i.credited), paid: text(i.paid), outstanding: text(i.outstanding),
            ageDays: text(i.ageDays), bucket: text(i.bucket),
          })),
        };
      }),

    // Warehouse operations
    ...warehouseVerbs(new WarehouseApi(config), terminals),
    // Administration
    ...adminGateway(config),

    // Commercial
    publishTariff: (orgUnitId: string, draft: TariffDraft) =>
      guard(async () => text((await freightBills.publishTariff({
        publishTariffRequest: {
          orgUnitId, code: draft.code.trim(), vendorPartnerId: draft.vendorPartnerId.trim(),
          originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
          vehicleType: draft.vehicleType.trim(), effectiveFrom: day(draft.effectiveFrom),
          currency: commercialBlank(draft.currency), minimumCharge: commercialNumber(draft.minimumCharge),
          detentionFreeHours: commercialNumber(draft.detentionFreeHours),
          detentionHourlyRate: commercialNumber(draft.detentionHourlyRate),
          additionalDropFee: commercialNumber(draft.additionalDropFee),
        },
      })).id)),
    addTariffSlab: (tariffId: string, slab: SlabDraft) =>
      guard(async () => text((await freightBills.addTariffSlab({
        id: tariffId,
        addSlabRequest: {
          minWeightKg: Number(slab.minWeightKg), maxWeightKg: commercialNumber(slab.maxWeightKg),
          rateBasis: slab.rateBasis as unknown as AddSlabRequestRateBasisEnum, rateAmount: Number(slab.rateAmount),
        },
      })).id)),
    recordFuelSurcharge: (month: string, surchargePct: number) =>
      guard(async () => text((await freightBills.recordFuelSurcharge({
        fuelSurchargeRequest: { month: day(month), surchargePct },
      })).id)),
    defineRoutingGuide: (orgUnitId: string, draft: GuideDraft) =>
      guard(async () => text((await sourcing.defineRoutingGuide({
        defineGuideRequest: {
          orgUnitId, originTerminalId: draft.originTerminalId.trim(),
          destinationTerminalId: draft.destinationTerminalId.trim(), vehicleType: draft.vehicleType.trim(),
          strategy: draft.strategy as unknown as DefineGuideRequestStrategyEnum,
        },
      })).id)),
    routingGuide: (id: string): Promise<RoutingGuideDetail> =>
      guard(async () => commercialGuideOf(await sourcing.getRoutingGuide({ id }))),
    addGuideVendor: (guideId: string, draft: GuideVendorDraft) =>
      guard(async () => {
        await sourcing.addRoutingGuideVendor({
          id: guideId,
          addVendorRequest: {
            vendorPartnerId: draft.vendorPartnerId.trim(), rankNo: draft.rankNo,
            responseSlaMinutes: draft.responseSlaMinutes, agreedRate: commercialNumber(draft.agreedRate),
          },
        });
      }),
    retireRoutingGuide: (id: string) =>
      guard(async () => {
        await sourcing.retireRoutingGuide({ id });
      }),
    allocations: (status?: string) =>
      guard(async () => (await sourcing.listAllocations({ status: status || undefined, limit: 100 }))
        .items.map(commercialAllocationRow)),
    allocationDetail: (id: string): Promise<AllocationDetail> =>
      guard(async () => {
        const [allocation, offers] = await Promise.all([sourcing.getAllocation({ id }), sourcing.listOffers({ id })]);
        return { allocation: commercialAllocationRow(allocation), offers: offers.map(commercialOfferRow) };
      }),
    allocateLoad: (loadId: string) =>
      guard(async () => text((await sourcing.allocateLoad({ allocateRequest: { loadId: loadId.trim() } })).id)),
    acceptOffer: (allocationId: string, vendorPartnerId: string) =>
      guard(async () => {
        await sourcing.acceptOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } });
      }),
    rejectOffer: (allocationId: string, vendorPartnerId: string) =>
      guard(async () => {
        await sourcing.rejectOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } });
      }),
    cancelAllocation: (id: string) =>
      guard(async () => {
        await sourcing.cancelAllocation({ id });
      }),
    freightBillList: (status?: string) =>
      guard(async () => (await freightBills.listFreightBills({ status: status || undefined, limit: 100 }))
        .items.map(commercialBillRow)),
    freightBill: (id: string): Promise<FreightBillDetail> =>
      guard(async () => {
        const [bill, lines] = await Promise.all([
          freightBills.getFreightBill({ id }), freightBills.getFreightBillLines({ id })]);
        return { bill: commercialBillRow(bill), lines: lines.map(commercialBillLineRow) };
      }),
    matchFreightBill: (id: string, claimedAmount: number) =>
      guard(async () => text((await freightBills.matchFreightBill({ id, matchRequest: { claimedAmount } })).status)),
    resolveFreightBill: (id: string, reason: string) =>
      guard(async () => {
        await freightBills.resolveDispute({ id, resolveDisputeRequest: { reason } });
      }),

    // Control tower
    exceptionTickets: (filter: ExceptionFilter) =>
      guard(async () => (await controlTower.listExceptions({
        status: filter.status || undefined, severity: (filter.severity || undefined) as ListExceptionsSeverityEnum | undefined,
        limit: 50,
      })).items.map(ticketRow)),
    exceptionTicket: (id: string) => guard(async () => ticketRow(await controlTower.getException({ id }))),
    exceptionHistory: (id: string) =>
      guard(async () => (await controlTower.getExceptionHistory({ id })).map((a) => ({
        action: text(a.action), fromStatus: text(a.fromStatus), toStatus: text(a.toStatus), note: text(a.note),
        actor: text(a.actor), occurredAt: text(a.occurredAt),
      }))),
    raiseException: (draft: ExceptionDraft) =>
      guard(async () => text((await controlTower.raiseException({
        raiseExceptionRequest: {
          tripId: draft.tripId.trim(), category: draft.category as ExceptionCategory,
          description: draft.description.trim(),
        },
      })).id)),
    assignException: (id: string, assigneeUserId: string, note: string) =>
      guard(() => controlTower.assignException({
        id, assignExceptionRequest: { assigneeUserId: assigneeUserId.trim(), note: note.trim() || undefined },
      })),
    investigateException: (id: string, note: string) =>
      guard(() => controlTower.investigateException({ id, exceptionNoteRequest: noteOf(note) })),
    resolveException: (id: string, resolution: string) =>
      guard(() => controlTower.resolveException({ id, resolveExceptionRequest: { resolution: resolution.trim() } })),
    reopenException: (id: string, reason: string) =>
      guard(() => controlTower.reopenException({ id, reopenExceptionRequest: { reason: reason.trim() } })),
    closeException: (id: string, note: string) =>
      guard(() => controlTower.closeException({ id, exceptionNoteRequest: noteOf(note) })),
    userChoices: (): Promise<Choice[]> =>
      guard(async () => (await new UsersApi(config).listUsers()).map((u) => ({
        id: text(u.id), label: `${text(u.fullName)} · ${text(u.email)}`,
      }))),
    detectorSettings: (): Promise<DetectorSettingsView> =>
      guard(async () => {
        const d = await controlTower.getDetectorSettings();
        return {
          etaSlipMinutes: d.etaSlipMinutes ?? 0, temperatureExcursionMinutes: d.temperatureExcursionMinutes ?? 0,
          customised: d.customised ?? false,
        };
      }),
    saveDetectorSettings: (etaSlipMinutes: number, temperatureExcursionMinutes: number) =>
      guard(() => controlTower.setDetectorSettings({
        setDetectorSettingsRequest: { etaSlipMinutes, temperatureExcursionMinutes },
      })),
    tripWatch: (tripId: string): Promise<TripWatchView | null> =>
      absentOn404(async () => {
        const w = await controlTower.getTripWatch({ tripId });
        return {
          committedArrivalAt: text(w.committedArrivalAt), tempMinC: text(w.tempMinC), tempMaxC: text(w.tempMaxC),
          outsideRangeSince: text(w.outsideRangeSince), lastReadingAt: text(w.lastReadingAt),
        };
      }),
    setTripWatch: (tripId: string, draft: TripWatchDraft) =>
      guard(() => controlTower.setTripWatch({
        tripId,
        setTripWatchRequest: {
          committedArrivalAt: draft.committedArrivalAt ? new Date(draft.committedArrivalAt) : undefined,
          tempMinC: decimalOrAbsent(draft.tempMinC), tempMaxC: decimalOrAbsent(draft.tempMaxC),
        },
      })),
    clearTripWatch: (tripId: string) => guard(() => controlTower.clearTripWatch({ tripId })),
    tripActivity: (tripId: string): Promise<TripActivity> =>
      guard(async () => {
        const [progress, crossings] = await Promise.all([
          absentOn404(() => telematics.getProgress({ tripId })),
          telematics.listCrossings({ tripId }),
        ]);
        return {
          progress: progress && {
            tripNo: text(progress.tripNo), status: text(progress.status), lastFixAt: text(progress.lastFixAt),
            completionPct: text(progress.completionPct), remainingDistanceM: text(progress.remainingDistanceM),
            estimatedMinutesRemaining: text(progress.estimatedMinutesRemaining),
            offRoute: progress.offRoute ? 'Yes' : '', deviationM: text(progress.deviationM),
          },
          crossings: crossings.map((c) => ({
            id: text(c.id), eventType: text(c.eventType), terminalCode: text(c.terminalCode),
            occurredAt: text(c.occurredAt),
          })),
        };
      }),
    vehiclePositions: (): Promise<Mapped> =>
      guard(async () => {
        const positions = await telematics.listPositions();
        const rows = positions.map((v) => ({
          id: text(v.vehicleId), registrationNo: text(v.registrationNo), tripId: text(v.tripId), tripNo: text(v.tripNo),
          tripStatus: text(v.tripStatus), speedKph: text(v.speedKph), recordedAt: text(v.recordedAt),
          ageSeconds: text(v.ageSeconds),
        }));
        const features: MapFeature[] = positions
          .filter((v) => v.lat !== undefined && v.lon !== undefined)
          .map((v) => ({
            id: text(v.vehicleId), kind: 'vehicle',
            label: `${text(v.registrationNo)}${v.tripNo ? ` · ${v.tripNo}` : ''}`,
            points: [{ lat: v.lat as number, lon: v.lon as number }],
          }));
        return { rows, features };
      }),
    openDeviations: () =>
      guard(async () => (await telematics.listOpenDeviations()).map((d) => ({
        id: text(d.id), tripId: text(d.tripId), tripNo: text(d.tripNo), detectedAt: text(d.detectedAt),
        distanceM: text(d.distanceM), corridorM: text(d.corridorM),
      }))),
  };
}

/** Runs a call and turns whatever it throws into an ApiProblem. */
async function guard<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (e) {
    if (e instanceof ResponseError) {
      const status = e.response.status;
      try {
        const body = (await e.response.clone().json()) as {
          detail?: string; code?: string; errors?: Record<string, string>;
        };
        if (body.errors && status !== 409) {
          throw new ApiProblem(Object.entries(body.errors).map(([k, v]) => `${k}: ${v}`).join('; '), status, body.errors,
            body.code ?? '');
        }
        throw new ApiProblem(body.detail ?? `${status} ${e.response.statusText}`, status, body.errors ?? {},
          body.code ?? '');
      } catch (inner) {
        if (inner instanceof ApiProblem) {
          throw inner;
        }
        throw new ApiProblem(`${status} ${e.response.statusText}`, status);
      }
    }
    throw new ApiProblem('Could not reach the API. Try again.');
  }
}

// Warehouse operations

/** A record's scalar fields as display cells; nested lists are mapped by the caller. */
function flatRow(record: object): Row {
  return Object.fromEntries(Object.entries(record)
    .filter(([, v]) => v === null || v === undefined || v instanceof Date || typeof v !== 'object')
    .map(([k, v]) => [k, text(v)]));
}

function stockMove(d: StockMoveDraft) {
  return { binId: d.binId.trim(), skuId: d.skuId.trim(), lotNo: blankAsAbsent(d.lotNo), quantity: Number(d.quantity) };
}

/** The warehouse verbs of the gateway, over the generated Warehouse client. */
function warehouseVerbs(warehouse: WarehouseApi, terminals: TerminalsApi) {
  const document = <T extends object>(header: T, lines: object[] | undefined): WarehouseDocument =>
    ({ header: flatRow(header), lines: (lines ?? []).map(flatRow) });
  return {
    warehouseChoices: (): Promise<Choice[]> =>
      guard(async () => (await terminals.listTerminals({ category: ListTerminalsCategoryEnum.Warehouse, limit: 200 }))
        .items.map((t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` }))),
    warehouseBins: (warehouseId: string) =>
      guard(async () => (await warehouse.listBins({ warehouseId, limit: 200 })).items.map(flatRow)),
    createBin: (warehouseId: string, code: string, capacityUnits: number) =>
      guard(async () => (await warehouse.createBin({
        warehouseId, createBinRequest: { code: code.trim(), capacityUnits },
      })).id),
    skus: () => guard(async () => (await warehouse.listSkus({ limit: 200 })).items.map(flatRow)),
    createSku: (draft: SkuDraft) =>
      guard(async () => (await warehouse.createSku({
        createSkuRequest: {
          code: draft.code.trim(), description: draft.description.trim(),
          ownerPartnerId: blankAsAbsent(draft.ownerPartnerId), uom: blankAsAbsent(draft.uom),
        },
      })).id),
    asns: (warehouseId: string) =>
      guard(async () => (await warehouse.listAsns({ warehouseId, limit: 100 })).items.map(flatRow)),
    asn: (id: string) => guard(async () => {
      const a = await warehouse.getAsn({ id });
      return document(a, a.lines);
    }),
    createAsn: (draft: AsnDraft) =>
      guard(async () => (await warehouse.createAsn({
        createAsnRequest: {
          warehouseId: draft.warehouseId, asnNo: draft.asnNo.trim(),
          supplierPartnerId: blankAsAbsent(draft.supplierPartnerId),
          lines: draft.lines.map((l) => ({
            skuId: l.skuId.trim(), lotNo: blankAsAbsent(l.lotNo), quantity: Number(l.quantity),
          })),
        },
      })).id),
    receiveAsn: (id: string, counts: ReceiptCountDraft[], crossDock: boolean) =>
      guard(async () => {
        await warehouse.receiveAsn({
          id, receiveAsnRequest: {
            crossDock,
            lines: counts.map((c) => ({
              lineNo: c.lineNo, receivedQty: Number(c.receivedQty), damagedQty: Number(c.damagedQty || 0),
            })),
          },
        });
      }),
    putaway: (draft: StockMoveDraft) =>
      guard(async () => { await warehouse.putaway({ putawayRequest: stockMove(draft) }); }),
    stock: (warehouseId: string) =>
      guard(async () => (await warehouse.listStock({ warehouseId, limit: 200 })).items.map(flatRow)),
    stockMovements: (skuId: string) =>
      guard(async () => (await warehouse.listStockMovements({ skuId, limit: 100 })).items.map(flatRow)),
    adjustStock: (draft: StockMoveDraft) =>
      guard(async () => (await warehouse.adjustStock({
        adjustStockRequest: { ...stockMove(draft), reason: draft.reason.trim() },
      })).id),
    releaseQuarantine: (draft: StockMoveDraft) =>
      guard(async () => (await warehouse.releaseQuarantinedStock({
        releaseQuarantineRequest: { ...stockMove(draft), reason: draft.reason.trim() },
      })).id),
    writeOffQuarantine: (warehouseId: string, draft: StockMoveDraft) =>
      guard(async () => (await warehouse.writeOffQuarantinedStock({
        writeOffQuarantineRequest: {
          warehouseId, skuId: draft.skuId.trim(), lotNo: blankAsAbsent(draft.lotNo), quantity: Number(draft.quantity),
          reason: draft.reason.trim(),
        },
      })).id),
    outboundOrders: (warehouseId: string) =>
      guard(async () => (await warehouse.listOutboundOrders({ warehouseId, limit: 100 })).items.map(flatRow)),
    outboundOrder: (id: string) => guard(async () => {
      const o = await warehouse.getOutboundOrder({ id });
      return document(o, o.lines);
    }),
    createOutboundOrder: (draft: OutboundDraft) =>
      guard(async () => (await warehouse.createOutboundOrder({
        createOutboundOrderRequest: {
          warehouseId: draft.warehouseId, orderNo: draft.orderNo.trim(), shipTo: blankAsAbsent(draft.shipTo),
          lines: draft.lines.map((l) => ({ skuId: l.skuId.trim(), quantity: Number(l.quantity) })),
        },
      })).id),
    pickOutboundOrder: (id: string) => guard(async () => { await warehouse.pickOutboundOrder({ id }); }),
    packOutboundOrder: (id: string) => guard(async () => (await warehouse.packOutboundOrder({ id })).id),
    cancelBackOrder: (id: string) => guard(async () => { await warehouse.cancelOutboundOrderBackOrder({ id }); }),
    handlingUnits: (warehouseId: string) =>
      guard(async () => (await warehouse.listHandlingUnits({ warehouseId, limit: 100 })).items.map(flatRow)),
    handlingUnit: (id: string) => guard(async () => {
      const u = await warehouse.getHandlingUnit({ id });
      return document(u, u.contents);
    }),
    dispatchHandlingUnit: (id: string, draft: DispatchDraft) =>
      guard(async () => {
        await warehouse.dispatchHandlingUnit({
          id, dispatchRequest: {
            reference: blankAsAbsent(draft.reference), destinationTerminalId: blankAsAbsent(draft.destinationTerminalId),
            consigneePartnerId: blankAsAbsent(draft.consigneePartnerId), grossWeightKg: numberOrAbsent(draft.grossWeightKg),
          },
        });
      }),
    pickWaves: (warehouseId: string) =>
      guard(async () => (await warehouse.listPickWaves({ warehouseId, limit: 100 })).items.map(flatRow)),
    pickWave: (id: string) => guard(async () => {
      const w = await warehouse.getPickWave({ id });
      return document(w, w.orders);
    }),
    releasePickWave: (warehouseId: string, orderIds: string[]) =>
      guard(async () => (await warehouse.releasePickWave({ releasePickWaveRequest: { warehouseId, orderIds } })).id),
    pickPickWave: (id: string) => guard(async () => { await warehouse.pickPickWave({ id }); }),
  };
}

// Administration

type AdminVerbs = Pick<LmsGateway, 'adminUsers' | 'userPermissions' | 'accessAttributes' | 'setAccessAttributes'
  | 'accessPolicies' | 'createAccessPolicy' | 'reviseAccessPolicy' | 'deleteAccessPolicy' | 'expiringDocuments'
  | 'ownerDocuments' | 'attachDocument'>;

/** The administration desk's verbs, over the same configuration as the rest of the gateway. */
function adminGateway(config: Configuration): AdminVerbs {
  const users = new UsersApi(config);
  const policies = new AccessPoliciesApi(config);
  const compliance = new ComplianceApi(config);
  const partners = new PartnersApi(config);
  const vehicles = new VehiclesApi(config);
  const drivers = new DriversApi(config);
  return {
    adminUsers: () => guard(async () => (await users.listUsers()).map(adminUserOf)),
    userPermissions: (userId: string) =>
      guard(async () => [...(await users.getUserPermissions({ id: userId }))].sort()),
    accessAttributes: (userId: string) =>
      guard(async () => adminAttributesOf(await policies.getUserAccessAttributes({ id: userId }))),
    setAccessAttributes: (userId: string, draft: AccessAttributesDraft) =>
      guard(async () => {
        await policies.setUserAccessAttributes({ id: userId, setUserAccessAttributesRequest: adminAttributesRequest(draft) });
      }),
    accessPolicies: (cursor?: string): Promise<AccessPolicyPage> =>
      guard(async () => {
        const page = await policies.listAccessPolicies({ cursor, limit: 100 });
        return { policies: page.items.map(adminPolicyOf), nextCursor: page.hasMore ? page.nextCursor ?? null : null };
      }),
    createAccessPolicy: async (draft: AccessPolicyDraft) => {
      const condition = adminConditionOf(draft.condition);
      return guard(async () => (await policies.createAccessPolicy({
        createAccessPolicyRequest: {
          code: draft.code.trim(), action: draft.action.trim(),
          effect: draft.effect as CreateAccessPolicyRequestEffectEnum, description: draft.description.trim(),
          condition, enabled: draft.enabled,
        },
      })).id);
    },
    reviseAccessPolicy: async (id: string, changes: AccessPolicyChanges) => {
      const condition = adminConditionOf(changes.condition);
      return guard(async () => {
        await policies.reviseAccessPolicy({
          id,
          reviseAccessPolicyRequest: {
            effect: changes.effect as ReviseAccessPolicyRequestEffectEnum, description: changes.description.trim(),
            condition, enabled: changes.enabled,
          },
        });
      });
    },
    deleteAccessPolicy: (id: string) =>
      guard(async () => {
        await policies.deleteAccessPolicy({ id });
      }),
    expiringDocuments: (withinDays: number) =>
      guard(async () => (await compliance.listExpiringDocuments({ withinDays })).map(adminDocumentRow)),
    ownerDocuments: (ownerType: DocumentOwnerType, ownerId: string) =>
      guard(async () => {
        const id = ownerId.trim();
        const docs = ownerType === 'PARTNER' ? await partners.listPartnerDocuments({ id })
          : ownerType === 'VEHICLE' ? await vehicles.listVehicleDocuments({ id })
            : await drivers.listDriverDocuments({ id });
        return docs.map(adminDocumentRow);
      }),
    attachDocument: (ownerType: DocumentOwnerType, ownerId: string, draft: DocumentDraft) =>
      guard(async () => {
        const id = ownerId.trim();
        const attachDocumentRequest = adminDocumentRequest(draft);
        const made = ownerType === 'PARTNER' ? await partners.attachPartnerDocument({ id, attachDocumentRequest })
          : ownerType === 'VEHICLE' ? await vehicles.attachVehicleDocument({ id, attachDocumentRequest })
            : await drivers.attachDriverDocument({ id, attachDocumentRequest });
        return text(made.id);
      }),
  };
}

function adminUserOf(u: User): AdminUser {
  return {
    id: text(u.id), email: text(u.email), fullName: text(u.fullName), status: text(u.status),
    orgName: text(u.orgName), lastLoginAt: text(u.lastLoginAt),
  };
}

function adminAttributesOf(a: UserAccessAttributes): AccessAttributesDraft {
  return {
    region: text(a.region), freightModes: (a.freightModes ?? []).map((m) => m as AccessFreightMode),
    partnerId: text(a.partnerId), approvalLimit: text(a.approvalLimit),
  };
}

/** An empty set of modes is left out rather than sent: absent means unrestricted, `[]` would mean none. */
function adminAttributesRequest(d: AccessAttributesDraft): SetUserAccessAttributesRequest {
  return {
    region: blankAsAbsent(d.region),
    freightModes: d.freightModes.length > 0
      ? d.freightModes.map((m) => m as SetUserAccessAttributesRequestFreightModesEnum) : undefined,
    partnerId: blankAsAbsent(d.partnerId), approvalLimit: numberOrAbsent(d.approvalLimit),
  };
}

function adminPolicyOf(p: AccessPolicy): AccessPolicyView {
  return {
    id: text(p.id), code: text(p.code), action: text(p.action), effect: text(p.effect) as PolicyEffect,
    description: text(p.description), condition: JSON.stringify(p.condition ?? {}, null, 2),
    enabled: p.enabled === true, updatedAt: text(p.updatedAt),
  };
}

/** The condition a desk typed, as the object the API takes; anything but a JSON object is refused here. */
function adminConditionOf(condition: string): { [key: string]: unknown } {
  let parsed: unknown;
  try {
    parsed = JSON.parse(condition);
  } catch {
    parsed = undefined;
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new ApiProblem('The condition must be a JSON object, such as {"attr": "subject.region", "op": "present"}.',
      undefined, {}, 'condition-not-json');
  }
  return parsed as { [key: string]: unknown };
}

/** `state` reads Expired, Expiring (within 30 days), Valid, or No expiry. */
function adminDocumentRow(d: ComplianceDocument): Row {
  const days = d.daysToExpiry;
  const state = d.expired ? 'Expired' : days === undefined || days === null ? 'No expiry'
    : days <= 30 ? 'Expiring' : 'Valid';
  return {
    id: text(d.id), ownerType: text(d.ownerType), ownerLabel: text(d.ownerLabel),
    documentType: text(d.documentType), documentNo: text(d.documentNo), issuingAuthority: text(d.issuingAuthority),
    issuedOn: text(d.issuedOn), expiresOn: text(d.expiresOn), daysToExpiry: text(days), state,
  };
}

function adminDocumentRequest(d: DocumentDraft): AttachDocumentRequest {
  return {
    documentType: d.documentType.trim(), documentNo: d.documentNo.trim(),
    issuingAuthority: blankAsAbsent(d.issuingAuthority),
    issuedOn: d.issuedOn.trim() ? day(d.issuedOn.trim()) : undefined,
    expiresOn: d.expiresOn.trim() ? day(d.expiresOn.trim()) : undefined,
  };
}

// Commercial

/** A blank optional text left out of a request. */
function commercialBlank(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

/** A blank optional figure left out of a request. */
function commercialNumber(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

function commercialGuideOf(g: CommercialRoutingGuide): RoutingGuideDetail {
  return {
    guide: {
      id: text(g.id), originTerminalCode: text(g.originTerminalCode), destinationTerminalCode: text(g.destinationTerminalCode),
      vehicleType: text(g.vehicleType), strategy: text(g.strategy), active: g.active ? 'Yes' : '',
    },
    entries: (g.entries ?? []).map((e) => ({
      rankNo: text(e.rankNo), vendorPartnerId: text(e.vendorPartnerId), vendorName: text(e.vendorName),
      responseSlaMinutes: text(e.responseSlaMinutes), agreedRate: text(e.agreedRate),
    })),
  };
}

function commercialAllocationRow(a: CommercialAllocation): Row {
  return {
    id: text(a.id), loadId: text(a.loadId), loadNo: text(a.loadNo), strategy: text(a.strategy), status: text(a.status),
    pendingVendorName: text(a.pendingVendorName), respondsBy: text(a.respondsBy),
    awardedVendorName: text(a.awardedVendorName), awardedAt: text(a.awardedAt), currentRank: text(a.currentRank),
    offersMade: text(a.offersMade), appliedRuleName: text(a.appliedRuleName), createdAt: text(a.createdAt),
  };
}

function commercialOfferRow(o: CommercialOffer): Row {
  return {
    id: text(o.id), rankNo: text(o.rankNo), vendorPartnerId: text(o.vendorPartnerId), vendorName: text(o.vendorName),
    outcome: text(o.outcome), offeredAt: text(o.offeredAt), respondsBy: text(o.respondsBy),
    respondedAt: text(o.respondedAt),
  };
}

function commercialBillRow(b: CommercialFreightBill): Row {
  return {
    id: text(b.id), billNo: text(b.billNo), status: text(b.status), source: text(b.source), currency: text(b.currency),
    tripNo: text(b.tripNo), loadNo: text(b.loadNo), vendorName: text(b.vendorName), tariffCode: text(b.tariffCode),
    pricedOn: text(b.pricedOn), chargeableWeightKg: text(b.chargeableWeightKg), baseFreight: text(b.baseFreight),
    detentionAmount: text(b.detentionAmount), multiDropAmount: text(b.multiDropAmount),
    fuelSurchargeAmount: text(b.fuelSurchargeAmount), computedAmount: text(b.computedAmount),
    claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount), variancePct: text(b.variancePct),
    resolution: text(b.resolution), matchedAt: text(b.matchedAt), settlementHeldBy: text(b.settlementHeldBy),
  };
}

function commercialBillLineRow(l: CommercialFreightBillLine): Row {
  return {
    lineNo: text(l.lineNo), chargeType: text(l.chargeType), narrative: text(l.narrative), quantity: text(l.quantity),
    unit: text(l.unit), rate: text(l.rate), amount: text(l.amount),
  };
}
