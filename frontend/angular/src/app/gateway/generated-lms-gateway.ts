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

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import { DemoDataService } from '../api';
import {
  ClientBillingService, ClientCharge, ClientContract, ClientInvoice, ClientInvoiceLine, ClientStatementEntry,
} from '../api';
import type { ClientAgeingView, ClientInvoiceDetail, CreditNoteDraft, PaymentDraft } from './lms-gateway';
// Commercial
import {
  AddSlabRequestRateBasisEnum, Allocation as CommercialAllocation, DefineGuideRequestStrategyEnum,
  FreightBill as CommercialFreightBill, FreightBillLine as CommercialFreightBillLine, Offer as CommercialOffer,
  RoutingGuide as CommercialRoutingGuide,
} from '../api';
import type {
  AllocationDetail, FreightBillDetail, GuideDraft, GuideVendorDraft, RoutingGuideDetail, SlabDraft, TariffDraft,
} from './lms-gateway';
import type { DemoResetOutcome } from './lms-gateway';
// Rate cards and rail tariffs
import {
  RailTariff as RailRateCard, Tariff as RateCard, TariffCard as RateCardWithBands, TariffSlab as RateCardBand,
} from '../api';
import type { RailTariffDraft, RateCardPage, TariffCardDetail, TariffFilter } from './lms-gateway';
// Pickers
import type { PickPage, PickQuery } from './lms-gateway';
import {
  AllocationRule, AllocationRuleAction, AllocationRuleRequest, AllocationRuleRequestMaterialClassEnum,
  AllocationRulesService, AuthenticationService, ControlTowerService, CustomFieldDefinition, CustomFieldEntityType,
  ClaimStatus as ApiClaimStatus, ClaimType as ApiClaimType, Consignment, CustomFieldsService, CustomFieldType,
  DeliveryAndClaimsService, DriversService, EventDeadLetterService, LTLRatingService, MilkRunsService, MultimodalService, OrdersService, PartnersService,
  Load, PlanningService, PublicTrackingService, RegisterPartnerRequestPartnerTypeEnum, SettlementService, SourcingService,
  TelematicsService, Terminal, TerminalsService, TrackingLinksService, TripsService, VehiclesService,
  ParkedEvent, ParkedEventStatus, PublishLtlRateCardRequestRateBasisEnum,
  WebhookDelivery, WebhookEventType as ApiWebhookEventType, WebhookSubscription, WebhooksService,
  AddOrderLineRequest, Order, OrderLine, UpdateOrderRequest,
} from '../api';
import {
  AllocationRuleView, AllocationSummary, ApiProblem, Choice, ClaimDraft, ConsignmentPage, LoadBuild, ClaimMove, ConsignmentCard, ControlTowerSummary, Credentials, CustomFieldKind, CustomValues,
  DeliveryProofView, FieldChanges, FieldDefinition, FieldDraft, FieldType, IssuedTrackingLink, LmsGateway,
  OtpIssued, PartnerDraft, ProofDraft, PublicTrackingView, ResourceKey, Row, RuleAction, RuleDraft, RuleEvaluation,
  GeoPoint, MapFeature, Mapped, MappedDetail, SignedIn, SplitPart, SplitPartDraft, TrackingLinkView, TripRoute,
  ParkedEventDetail, ParkedFilter, RateCardDraft,
  WebhookChanges, WebhookDeliveryPage, WebhookDraft, WebhookSecret, WebhookView,
  OrderDesk, OrderHeaderDraft, OrderLineDraft, OrderRaiseDraft,
} from './lms-gateway';
// Warehouse operations
import { WarehouseService } from '../api';
import type {
  AsnDraft, DispatchDraft, OutboundDraft, ReceiptCountDraft, SkuDraft, StockMoveDraft, WarehouseDocument,
} from './lms-gateway';
// Administration
import {
  AccessPoliciesService, AccessPolicy, AttachDocumentRequest, ComplianceDocument, ComplianceService,
  CreateAccessPolicyRequestEffectEnum, ReviseAccessPolicyRequestEffectEnum, SetUserAccessAttributesRequest,
  SetUserAccessAttributesRequestFreightModesEnum, User, UserAccessAttributes, UsersService,
} from '../api';
import type {
  AccessAttributesDraft, AccessFreightMode, AccessPolicyChanges, AccessPolicyDraft, AccessPolicyPage,
  AccessPolicyView, AdminUser, DocumentDraft, DocumentOwnerType, PolicyEffect,
} from './lms-gateway';
// Control tower
import { Exception as ApiException, ExceptionCategory } from '../api';
import type {
  DetectorSettingsView, ExceptionDraft, ExceptionFilter, TripActivity, TripWatchDraft, TripWatchView,
} from './lms-gateway';

/** An instant as the React console shows one: `YYYY-MM-DD HH:mm` in UTC. */
function instant(v: string | null | undefined): string {
  return v ? text(new Date(v)) : '';
}

/** A ticket as the exception desk lists and opens it. */
function ticketRow(e: ApiException): Row {
  return {
    id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
    category: text(e.category), description: text(e.description), source: text(e.source),
    tripId: text(e.tripId), tripNo: text(e.tripNo), registrationNo: text(e.registrationNo),
    subjectLabel: text(e.subjectLabel), assigneeName: text(e.assigneeName), raisedBy: text(e.raisedBy),
    raisedAt: instant(e.raisedAt), resolution: text(e.resolution), resolvedBy: text(e.resolvedBy),
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

/** The result, or null when the API answered 404; any other failure is thrown on. */
async function absentOn404<T>(call: Promise<T>): Promise<T | null> {
  try {
    return await call;
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

function definitionOf(d: CustomFieldDefinition): FieldDefinition {
  return {
    id: d.id ?? '',
    kind: d.entityType as unknown as CustomFieldKind,
    key: d.key ?? '',
    label: d.label ?? '',
    type: d.fieldType as unknown as FieldType,
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
    materialClass: text(r.materialClass), action: (text(r.action) || 'PREFER') as RuleAction,
    vendors: (r.vendors ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
  };
}

/** The contract's request: a condition left out is sent as absent, not as an empty string. */
function ruleRequest(draft: RuleDraft): AllocationRuleRequest {
  return {
    name: draft.name, priority: draft.priority, active: draft.active,
    originTerminalId: draft.originTerminalId || undefined,
    destinationTerminalId: draft.destinationTerminalId || undefined,
    minWeightKg: draft.minWeightKg, maxWeightKg: draft.maxWeightKg,
    materialClass: (draft.materialClass || undefined) as unknown as AllocationRuleRequestMaterialClassEnum | undefined,
    action: draft.action as unknown as AllocationRuleAction,
    vendorPartnerIds: draft.vendorIds,
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

function headerRequest(h: OrderHeaderDraft): UpdateOrderRequest {
  return {
    customerPartnerId: h.customerPartnerId.trim(), originTerminalId: h.originTerminalId.trim(),
    requestedPickupAt: blankAsAbsent(h.requestedPickupAt), requestedDeliveryAt: blankAsAbsent(h.requestedDeliveryAt),
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
      requestedPickupAt: text(o.requestedPickupAt), requestedDeliveryAt: text(o.requestedDeliveryAt),
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

/** The adapter: the only file in the Angular app (besides app config) that knows the generated client. */
@Injectable()
export class GeneratedLmsGateway extends LmsGateway {
  private readonly auth = inject(AuthenticationService);
  private readonly controlTower = inject(ControlTowerService);
  private readonly demo = inject(DemoDataService);
  private readonly partners = inject(PartnersService);
  private readonly vehicles = inject(VehiclesService);
  private readonly drivers = inject(DriversService);
  private readonly terminals = inject(TerminalsService);
  private readonly orders = inject(OrdersService);
  private readonly loads = inject(PlanningService);
  private readonly trips = inject(TripsService);
  private readonly freightBills = inject(SettlementService);
  private readonly customFields = inject(CustomFieldsService);
  private readonly delivery = inject(DeliveryAndClaimsService);
  private readonly links = inject(TrackingLinksService);
  private readonly publicRead = inject(PublicTrackingService);
  private readonly rules = inject(AllocationRulesService);
  private readonly sourcing = inject(SourcingService);
  private readonly telematics = inject(TelematicsService);
  private readonly multimodal = inject(MultimodalService);
  private readonly milkRuns = inject(MilkRunsService);
  private readonly ltl = inject(LTLRatingService);
  private readonly deadLetters = inject(EventDeadLetterService);
  private readonly hooks = inject(WebhooksService);
  private readonly clientBilling = inject(ClientBillingService);

  private readonly listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: () => this.call(this.partners.listPartners({ limit: 50 }), (s) => s.items.map((p) => ({
      id: text(p.id), code: text(p.code), legalName: text(p.legalName),
      partnerType: text(p.partnerType), status: text(p.status) }))),
    vehicles: () => this.call(this.vehicles.listVehicles({ limit: 50 }), (s) => s.items.map((v) => ({
      id: text(v.id), registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType),
      status: text(v.status), payloadCapacityKg: text(v.payloadCapacityKg) }))),
    drivers: () => this.call(this.drivers.listDrivers({ limit: 50 }), (s) => s.items.map((d) => ({
      id: text(d.id), fullName: text(d.fullName), licenceNo: text(d.licenceNo),
      licenceExpiresOn: text(d.licenceExpiresOn), status: text(d.status) }))),
    terminals: () => this.call(this.terminals.listTerminals({ limit: 50 }), (s) => s.items.map((t) => ({
      id: text(t.id), code: text(t.code), name: text(t.name),
      functionalCategory: text(t.functionalCategory), dockCount: text(t.dockCount) }))),
    orders: () => this.call(this.orders.listOrders({ limit: 50 }), (s) => s.items.map((o) => ({
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status),
      customerName: text(o.customerName), originTerminalCode: text(o.originTerminalCode) }))),
    loads: () => this.call(this.loads.listLoads({ limit: 50 }), (s) => s.items.map((l) => ({
      id: text(l.id), loadNo: text(l.loadNo), status: text(l.status),
      vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) }))),
    trips: () => this.call(this.trips.listTrips({ limit: 50 }), (s) => s.items.map((t) => ({
      id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) }))),
    'freight-bills': () => this.call(this.freightBills.listFreightBills({ limit: 50 }), (s) => s.items.map((b) => ({
      id: text(b.id), billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) }))),
    exceptions: () => this.call(this.controlTower.listExceptions({ limit: 50 }), (s) => s.items.map((e) => ({
      id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
      category: text(e.category), description: text(e.description) }))),
    claims: () => this.claims(),
  };

  signIn(credentials: Credentials): Promise<SignedIn> {
    return this.call(this.auth.login({ loginRequest: credentials }), (r) => ({
      token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissions: r.permissions ?? [] }));
  }

  summary(): Promise<ControlTowerSummary> {
    return this.call(this.controlTower.summariseExceptions(), (s) => ({
      openP1: s.openP1 ?? 0, openP2: s.openP2 ?? 0, openP3: s.openP3 ?? 0,
      unassigned: s.unassigned ?? 0, settlementHolds: s.settlementHolds ?? 0,
      openWithoutTrip: s.openWithoutTrip ?? 0 }));
  }

  resetDemoData(confirm: string): Promise<DemoResetOutcome> {
    return this.call(this.demo.resetDemoData({ demoResetRequest: { confirm } }), (r) => ({
      tenantCode: r.tenantCode, rowsDeleted: r.rowsDeleted, signInAgain: r.signInAgain }));
  }

  list(resource: ResourceKey): Promise<Row[]> {
    return this.listers[resource]();
  }

  claims(consignmentId?: string): Promise<Row[]> {
    return this.call(this.delivery.listClaims({ consignmentId: consignmentId || undefined, limit: 50 }),
      (s) => s.items.map((c) => ({
        id: text(c.id), claimNo: text(c.claimNo), claimType: text(c.claimType), amount: text(c.amount),
        status: text(c.status), consignmentId: text(c.consignmentId),
        approvedAmount: text(c.approvedAmount), description: text(c.description),
        resolutionNote: text(c.resolutionNote) })));
  }

  registerPartner(orgUnitId: string, draft: PartnerDraft): Promise<void> {
    return this.call(this.partners.registerPartner({
      registerPartnerRequest: {
        orgUnitId, code: draft.code, legalName: draft.legalName,
        partnerType: draft.partnerType as RegisterPartnerRequestPartnerTypeEnum,
      },
    }), () => undefined);
  }

  listFields(kind?: CustomFieldKind): Promise<FieldDefinition[]> {
    return this.call(
      this.customFields.listCustomFieldDefinitions({
        entityType: kind as unknown as CustomFieldEntityType | undefined,
      }),
      (slice) => slice.items.map(definitionOf));
  }

  defineField(draft: FieldDraft): Promise<void> {
    return this.call(this.customFields.defineCustomField({
      defineCustomFieldRequest: {
        entityType: draft.kind as unknown as CustomFieldEntityType, key: draft.key,
        label: draft.label, fieldType: draft.type as unknown as CustomFieldType,
        required: draft.required, options: draft.options,
      },
    }), () => undefined);
  }

  reviseField(id: string, changes: FieldChanges): Promise<void> {
    return this.call(this.customFields.reviseCustomField({
      id,
      reviseCustomFieldRequest: {
        label: changes.label, required: changes.required, options: changes.options,
        active: changes.active,
      },
    }), () => undefined);
  }

  customValues(kind: CustomFieldKind, recordId: string): Promise<CustomValues> {
    return this.call(this.customFields.getCustomFieldValues({
      entityType: kind as unknown as CustomFieldEntityType, entityId: recordId,
    }), (v) => v.values as CustomValues);
  }

  saveCustomValues(kind: CustomFieldKind, recordId: string, values: CustomValues): Promise<CustomValues> {
    return this.call(this.customFields.setCustomFieldValues({
      entityType: kind as unknown as CustomFieldEntityType, entityId: recordId,
      customFieldValues: { values },
    }), (v) => v.values as CustomValues);
  }

  raiseClaim(draft: ClaimDraft): Promise<string> {
    return this.call(this.delivery.raiseClaim({
      raiseClaimRequest: {
        consignmentId: draft.consignmentId, claimType: draft.claimType as unknown as ApiClaimType,
        amount: draft.amount, description: draft.description,
      },
    }), (made) => made.id ?? '');
  }

  transitionClaim(id: string, move: ClaimMove): Promise<void> {
    return this.call(this.delivery.transitionClaim({
      id,
      transitionClaimRequest: {
        target: move.target as unknown as ApiClaimStatus, approvedAmount: move.approvedAmount,
        resolutionNote: move.resolutionNote,
      },
    }), () => undefined);
  }

  issueDeliveryOtp(consignmentId: string): Promise<OtpIssued> {
    return this.call(this.delivery.issueDeliveryOtp({ consignmentId }),
      (issued) => ({ expiresAt: text(issued.expiresAt), otp: issued.otp }));
  }

  recordDeliveryProof(consignmentId: string, draft: ProofDraft): Promise<void> {
    return this.call(this.delivery.recordDeliveryProof({
      consignmentId,
      recordDeliveryProofRequest: {
        otp: draft.otp, recipientName: draft.recipientName, photoRef: draft.photoRef || undefined,
      },
    }), () => undefined);
  }

  async deliveryProof(consignmentId: string): Promise<DeliveryProofView | null> {
    try {
      return await this.call(this.delivery.getDeliveryProof({ consignmentId }), (p) => ({
        consignmentId: p.consignmentId ?? consignmentId,
        status: (p.status as unknown as string) === 'CONFIRMED' ? 'CONFIRMED' as const : 'OTP_ISSUED' as const,
        recipientName: text(p.recipientName), photoRef: text(p.photoRef),
        deliveredAt: text(p.deliveredAt), otpExpiresAt: text(p.otpExpiresAt),
      }));
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  consignmentByRef(trackingRef: string): Promise<ConsignmentCard> {
    return this.call(this.loads.trackConsignment({ trackingRef }), consignmentCard);
  }

  consignment(id: string): Promise<ConsignmentCard> {
    return this.call(this.loads.getConsignment({ id }), consignmentCard);
  }

  async consignmentParts(id: string): Promise<ConsignmentCard[]> {
    // At most 500 parts, so at most three pages: read them all.
    const parts: ConsignmentCard[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.call(this.loads.listConsignmentParts({ id, cursor, limit: 200 }), (p) => p);
      parts.push(...page.items.map(consignmentCard));
      cursor = page.hasMore ? page.nextCursor ?? undefined : undefined;
    } while (cursor);
    return parts;
  }

  splitConsignment(id: string, parts: SplitPartDraft[]): Promise<SplitPart[]> {
    return this.call(this.loads.splitConsignment({
      id,
      splitConsignmentRequest: {
        parts: parts.map((p) => ({ deadWeightKg: Number(p.deadWeightKg), volumeM3: Number(p.volumeM3) })),
      },
    }), (made) => made.map((p) => ({ id: p.id, trackingRef: p.trackingRef })));
  }

  issueTrackingLink(consignmentId: string, expiresInDays?: number): Promise<IssuedTrackingLink> {
    return this.call(this.links.issueTrackingLink({
      consignmentId, issueTrackingLinkRequest: expiresInDays ? { expiresInDays } : undefined,
    }), (issued) => ({ id: issued.id, token: issued.token, expiresAt: text(issued.expiresAt) }));
  }

  trackingLinks(consignmentId: string): Promise<TrackingLinkView[]> {
    return this.call(this.links.listTrackingLinks({ consignmentId }), (list) => list.items.map((l) => ({
      id: text(l.id), createdAt: text(l.createdAt), expiresAt: text(l.expiresAt),
      revokedAt: text(l.revokedAt), status: ((l.status as unknown as string) ?? 'ACTIVE') as TrackingLinkView['status'],
    })));
  }

  revokeTrackingLink(id: string): Promise<void> {
    return this.call(this.links.revokeTrackingLink({ id }), () => undefined);
  }

  async publicTracking(token: string): Promise<PublicTrackingView | null> {
    try {
      return await this.call(this.publicRead.getPublicTracking({ token }), (t) => ({
        reference: text(t.reference), status: text(t.status), originName: text(t.originName),
        destinationName: text(t.destinationName), requestedDeliveryAt: text(t.requestedDeliveryAt),
        deliveredAt: text(t.deliveredAt), proofRecorded: t.proofRecorded ?? false,
        position: t.position
          ? { lat: text(t.position.lat), lon: text(t.position.lon), recordedAt: text(t.position.recordedAt) }
          : null,
        partsTotal: t.partsTotal ?? null, partsDelivered: t.partsDelivered ?? null,
      }));
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  allocationRules(): Promise<AllocationRuleView[]> {
    return this.call(this.rules.listAllocationRules({ limit: 200 }), (s) => s.items.map(ruleOf));
  }

  createAllocationRule(draft: RuleDraft): Promise<string> {
    return this.call(this.rules.createAllocationRule({ allocationRuleRequest: ruleRequest(draft) }), (made) => made.id ?? '');
  }

  reviseAllocationRule(id: string, draft: RuleDraft): Promise<void> {
    return this.call(this.rules.reviseAllocationRule({ id, allocationRuleRequest: ruleRequest(draft) }), () => undefined);
  }

  evaluateAllocation(loadId: string): Promise<RuleEvaluation> {
    return this.call(this.rules.evaluateAllocationRules({ allocationEvaluationRequest: { loadId } }), (e) => ({
      loadNo: text(e.loadNo), ruleName: text(e.matchedRuleName),
      action: (e.matchedRuleId ? text(e.action) : '') as RuleAction | '',
      nextVendor: text(e.nextVendor?.vendorName),
      candidates: (e.candidateOrder ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
    }));
  }

  allocation(id: string): Promise<AllocationSummary> {
    return this.call(this.sourcing.getAllocation({ id }), (a) => ({
      id: text(a.id), loadNo: text(a.loadNo), status: text(a.status),
      appliedRuleName: text(a.appliedRuleName),
      appliedRuleAction: (a.appliedRuleId ? text(a.appliedRuleAction) : '') as RuleAction | '',
    }));
  }

  activeVendors(): Promise<Choice[]> {
    return this.call(this.partners.listPartners({ partnerType: 'VENDOR', status: 'ACTIVE', limit: 200 }),
      (s) => s.items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` })));
  }

  terminalChoices(): Promise<Choice[]> {
    return this.call(this.terminals.listTerminals({ limit: 200 }),
      (s) => s.items.map((t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` })));
  }

  async networkMap(): Promise<Mapped> {
    const [sites, positions] = await Promise.all([
      this.call(this.terminals.listTerminals({ limit: 200 }), (x) => x),
      this.call(this.telematics.listPositions(), (x) => x),
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
  }

  async tripRoute(tripId: string): Promise<TripRoute> {
    const trip = await this.call(this.trips.getTrip({ id: tripId }), (x) => x);
    const [track, progress] = await Promise.all([
      this.call(this.telematics.getTrack({ tripId }), (x) => x),
      this.call(this.telematics.getProgress({ tripId }), (x) => x).catch(() => null),
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
  }

  itineraries(): Promise<Row[]> {
    return this.call(this.multimodal.listItineraries({ limit: 50 }), (s) => s.items.map((i) => ({
      id: text(i.id), itineraryNo: text(i.itineraryNo), shipmentRef: text(i.shipmentRef), status: text(i.status),
      legCount: text(i.legCount), totalCost: text(i.totalCost), connectionMissed: i.connectionMissed ? 'Missed' : '',
    })));
  }

  async itinerary(id: string): Promise<MappedDetail> {
    const i = await this.call(this.multimodal.getItinerary({ id }), (x) => x);
    const legs = [...(i.legs ?? [])].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
    const sites = await this.terminalsById(legs.flatMap((l) => [l.fromTerminalId, l.toTerminalId]));
    const features: MapFeature[] = [];
    for (const l of legs) {
      const from = anchorOf(sites.get(l.fromTerminalId ?? ''));
      const to = anchorOf(sites.get(l.toTerminalId ?? ''));
      if (from && to) {
        features.push({
          id: text(l.id), kind: text(l.mode) === 'RAIL' ? 'leg-RAIL' : 'leg-ROAD',
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
  }

  milkRunRoutes(): Promise<Row[]> {
    return this.call(this.milkRuns.listMilkRunRoutes({ limit: 50 }), (s) => s.items.map((r) => ({
      id: text(r.id), code: text(r.code), name: text(r.name), plantCode: text(r.plantCode),
      daysOfWeek: text(r.daysOfWeek), departureTime: text(r.departureTime), effectiveFrom: text(r.effectiveFrom),
    })));
  }

  async milkRunRoute(id: string): Promise<MappedDetail> {
    const [route, stops] = await Promise.all([
      this.call(this.milkRuns.getMilkRunRoute({ id }), (x) => x),
      this.call(this.milkRuns.getMilkRunRouteStops({ id }), (x) => x),
    ]);
    const ordered = [...stops].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
    const sites = await this.terminalsById([route.plantTerminalId, ...ordered.map((s) => s.terminalId)]);
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
      const at = anchorOf(sites.get(s.terminalId ?? ''));
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
  }

  circuitRuns(routeCode: string): Promise<Row[]> {
    return this.call(this.milkRuns.listCircuitRuns({ routeCode, limit: 50 }), (s) => s.items.map((r) => ({
      id: text(r.id), runDate: text(r.runDate), status: text(r.status), tripNo: text(r.tripNo),
      stopsVisited: text(r.stopsVisited), stopsSkipped: text(r.stopsSkipped), chargeAmount: text(r.chargeAmount),
    })));
  }

  ltlReceivables(): Promise<Row[]> {
    return this.call(this.ltl.listDocketReceivables({ limit: 50 }), (s) => s.items.map((r) => ({
      id: text(r.id), docketNo: text(r.docketNo), status: text(r.status), customerName: text(r.customerName),
      lane: `${text(r.originZone)} → ${text(r.destinationZone)}`, rateCardCode: text(r.rateCardCode),
      chargeableWeightKg: text(r.chargeableWeightKg), totalAmount: text(r.totalAmount), currency: text(r.currency),
    })));
  }

  assignLtlZone(terminalId: string, zoneCode: string, outOfDeliveryArea: boolean): Promise<void> {
    return this.call(this.ltl.assignLtlZone({ terminalId, assignLtlZoneRequest: { zoneCode, outOfDeliveryArea } }),
      () => undefined);
  }

  publishLtlRateCard(draft: RateCardDraft): Promise<string> {
    return this.call(this.ltl.publishLtlRateCard({
      publishLtlRateCardRequest: {
        code: draft.code, originZone: draft.originZone, destinationZone: draft.destinationZone,
        effectiveFrom: draft.effectiveFrom,
        rateBasis: draft.rateBasis as unknown as PublishLtlRateCardRequestRateBasisEnum, rateAmount: draft.rateAmount,
        kgPerCft: draft.kgPerCft, minimumChargeableWeightKg: draft.minimumChargeableWeightKg,
        docketCharge: draft.docketCharge,
      },
    }), (made) => made.id ?? '');
  }

  parkedEvents(filter: ParkedFilter): Promise<Row[]> {
    return this.call(this.deadLetters.listParkedEvents({
      status: filter.status as unknown as ParkedEventStatus | undefined, eventType: filter.eventType || undefined,
      listenerId: filter.listenerId || undefined, limit: 50,
    }), (s) => s.items.map(parkedRow));
  }

  parkedEvent(id: string): Promise<ParkedEventDetail> {
    return this.call(this.deadLetters.getParkedEvent({ id }),
      (d) => ({ summary: parkedRow(d.summary), payload: text(d.payload) }));
  }

  replayParkedEvent(id: string): Promise<{ outcome: string; attempts: number }> {
    return this.call(this.deadLetters.replayParkedEvent({ id }),
      (r) => ({ outcome: text(r.outcome), attempts: r.attempts ?? 0 }));
  }

  discardParkedEvent(id: string, reason: string): Promise<void> {
    return this.call(this.deadLetters.discardParkedEvent({ id, discardParkedEventRequest: { reason } }),
      () => undefined);
  }

  webhooks(): Promise<WebhookView[]> {
    return this.call(this.hooks.listWebhookSubscriptions({ limit: 200 }), (s) => s.items.map(webhookOf));
  }

  subscribeWebhook(draft: WebhookDraft): Promise<WebhookSecret> {
    return this.call(this.hooks.subscribeWebhook({
      webhookSubscriptionRequest: { name: draft.name, url: draft.url, eventTypes: draft.eventTypes as ApiWebhookEventType[] },
    }), (r) => ({ id: r.id, secret: r.secret }));
  }

  reviseWebhook(id: string, changes: WebhookChanges): Promise<void> {
    return this.call(this.hooks.reviseWebhookSubscription({
      id,
      webhookRevisionRequest: {
        name: changes.name, url: changes.url, eventTypes: changes.eventTypes as ApiWebhookEventType[],
        active: changes.active,
      },
    }), () => undefined);
  }

  rotateWebhookSecret(id: string): Promise<WebhookSecret> {
    return this.call(this.hooks.rotateWebhookSecret({ id }), (r) => ({ id: r.id, secret: r.secret }));
  }

  pingWebhook(id: string): Promise<string> {
    return this.call(this.hooks.pingWebhook({ id }), (r) => r.deliveryId);
  }

  webhookDeliveries(id: string, cursor?: string): Promise<WebhookDeliveryPage> {
    return this.call(this.hooks.listWebhookDeliveries({ id, cursor, limit: 20 }),
      (page) => ({ rows: page.items.map(deliveryRow), nextCursor: page.nextCursor ?? null }));
  }

  redeliverWebhook(deliveryId: string): Promise<void> {
    return this.call(this.hooks.redeliverWebhook({ id: deliveryId }), () => undefined);
  }

  async loadBuild(loadId: string): Promise<LoadBuild> {
    const [load, aboard] = await Promise.all([
      this.call(this.loads.getLoad({ id: loadId }), loadRow),
      this.call(this.loads.listLoadConsignments({ id: loadId }), (rows) => rows.map(consignmentRow)),
    ]);
    return { load, aboard };
  }

  unassignedConsignments(orderId?: string, cursor?: string): Promise<ConsignmentPage> {
    return this.call(this.loads.listUnassignedConsignments({ orderId: orderId || undefined, cursor, limit: 200 }),
      (page) => ({ rows: page.items.map(consignmentRow), nextCursor: page.nextCursor ?? null }));
  }

  assignConsignments(loadId: string, consignmentIds: string[]): Promise<void> {
    return this.call(this.loads.assignConsignmentsInBulk({ id: loadId, bulkAssignConsignmentsRequest: { consignmentIds } }),
      () => undefined);
  }

  async orderDesk(orderId: string): Promise<OrderDesk> {
    const [order, lines] = await Promise.all([
      this.call(this.orders.getOrder({ id: orderId }), (o) => o),
      this.call(this.orders.listOrderLines({ id: orderId }), (l) => l),
    ]);
    return orderDeskOf(order, lines);
  }

  raiseOrder(orgUnitId: string, draft: OrderRaiseDraft): Promise<string> {
    return this.call(this.orders.raiseOrder({
      raiseOrderRequest: {
        orgUnitId, orderNo: draft.orderNo.trim(), customerPartnerId: draft.customerPartnerId.trim(),
        originTerminalId: draft.originTerminalId.trim(), requestedPickupAt: blankAsAbsent(draft.requestedPickupAt),
        requestedDeliveryAt: blankAsAbsent(draft.requestedDeliveryAt),
      },
    }), (r) => text(r.id));
  }

  reviseOrderHeader(orderId: string, version: string, header: OrderHeaderDraft): Promise<void> {
    return this.call(this.orders.updateOrder({ id: orderId, ifMatch: version, updateOrderRequest: headerRequest(header) }),
      () => undefined);
  }

  replaceOrderLines(orderId: string, version: string, lines: OrderLineDraft[]): Promise<void> {
    return this.call(this.orders.replaceOrderLines({
      id: orderId, ifMatch: version, replaceOrderLinesRequest: { lines: lines.map(lineRequest) },
    }), () => undefined);
  }

  removeOrderLine(orderId: string, version: string, lineId: string): Promise<void> {
    return this.call(this.orders.removeOrderLine({ id: orderId, ifMatch: version, lineId }), () => undefined);
  }

  amendOrder(orderId: string, version: string, header: OrderHeaderDraft | null,
    lines: OrderLineDraft[] | null): Promise<void> {
    return this.call(this.orders.amendOrder({
      id: orderId, ifMatch: version,
      amendOrderRequest: {
        header: header ? headerRequest(header) : undefined, lines: lines ? lines.map(lineRequest) : undefined,
      },
    }), () => undefined);
  }

  validateOrder(orderId: string): Promise<void> {
    return this.call(this.orders.validateOrder({ id: orderId }), () => undefined);
  }

  cancelOrder(orderId: string, reason: string): Promise<void> {
    return this.call(this.orders.cancelOrder({ id: orderId, cancelOrderRequest: { reason: blankAsAbsent(reason) } }),
      () => undefined);
  }

  generateConsignments(orderId: string): Promise<number> {
    return this.call(this.loads.generateConsignments({ id: orderId }), (made) => made.length);
  }

  clientChoices(): Promise<Choice[]> {
    return this.call(this.partners.listPartners({ partnerType: 'CUSTOMER', limit: 200 }),
      (s) => s.items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` })));
  }

  clientContracts(clientId: string): Promise<Row[]> {
    return this.call(this.clientBilling.listClientContracts({ clientId }), (rows) => rows.map(contractRow));
  }

  clientCharges(clientId: string, period: string): Promise<Row[]> {
    return this.call(this.clientBilling.listClientCharges({ clientId, period, limit: 200 }),
      (page) => page.items.map(chargeRow));
  }

  runClientBilling(clientId: string, period: string): Promise<string> {
    return this.call(this.clientBilling.runClientBilling({ clientId, clientBillingRunRequest: { period } }),
      (r) => r.id);
  }

  clientInvoices(clientId: string): Promise<Row[]> {
    return this.call(this.clientBilling.listClientInvoices({ clientId, limit: 100 }),
      (page) => page.items.map(invoiceRow));
  }

  async clientInvoice(invoiceId: string): Promise<ClientInvoiceDetail> {
    const [invoice, lines] = await Promise.all([
      this.call(this.clientBilling.getClientInvoice({ id: invoiceId }), invoiceRow),
      this.call(this.clientBilling.getClientInvoiceLines({ id: invoiceId }), (rows) => rows.map(invoiceLineRow)),
    ]);
    return { invoice, lines };
  }

  recordClientPayment(clientId: string, draft: PaymentDraft): Promise<void> {
    return this.call(this.clientBilling.recordClientPayment({
      clientId,
      clientPaymentRequest: { amount: draft.amount, receivedOn: draft.receivedOn, reference: draft.reference },
    }), () => undefined);
  }

  async issueClientCreditNote(invoiceId: string, draft: CreditNoteDraft): Promise<string> {
    const issued = await this.call(this.clientBilling.issueClientCreditNote({
      id: invoiceId, clientCreditNoteRequest: { reason: draft.reason, amount: draft.amount },
    }), (r) => r.id);
    return this.call(this.clientBilling.getClientCreditNote({ id: issued }), (n) => text(n.creditNoteNo));
  }

  clientStatement(clientId: string): Promise<Row[]> {
    return this.call(this.clientBilling.getClientStatement({ clientId, limit: 200 }),
      (page) => page.items.map(statementRow));
  }

  clientAgeing(clientId: string): Promise<ClientAgeingView> {
    return this.call(this.clientBilling.getClientAgeing({ clientId }), (a) => ({
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
    }));
  }

  // Warehouse operations
  private readonly warehouse = inject(WarehouseService);

  warehouseChoices(): Promise<Choice[]> {
    return this.call(this.terminals.listTerminals({ category: 'WAREHOUSE', limit: 200 }),
      (s) => s.items.map((t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` })));
  }

  warehouseBins(warehouseId: string): Promise<Row[]> {
    return this.call(this.warehouse.listBins({ warehouseId, limit: 200 }), (s) => s.items.map(flatRow));
  }

  createBin(warehouseId: string, code: string, capacityUnits: number): Promise<string> {
    return this.call(this.warehouse.createBin({ warehouseId, createBinRequest: { code: code.trim(), capacityUnits } }),
      (r) => r.id);
  }

  skus(): Promise<Row[]> {
    return this.call(this.warehouse.listSkus({ limit: 200 }), (s) => s.items.map(flatRow));
  }

  createSku(draft: SkuDraft): Promise<string> {
    return this.call(this.warehouse.createSku({
      createSkuRequest: {
        code: draft.code.trim(), description: draft.description.trim(),
        ownerPartnerId: blankAsAbsent(draft.ownerPartnerId), uom: blankAsAbsent(draft.uom),
      },
    }), (r) => r.id);
  }

  asns(warehouseId: string): Promise<Row[]> {
    return this.call(this.warehouse.listAsns({ warehouseId, limit: 100 }), (s) => s.items.map(flatRow));
  }

  asn(id: string): Promise<WarehouseDocument> {
    return this.call(this.warehouse.getAsn({ id }), (a) => documentOf(a, a.lines));
  }

  createAsn(draft: AsnDraft): Promise<string> {
    return this.call(this.warehouse.createAsn({
      createAsnRequest: {
        warehouseId: draft.warehouseId, asnNo: draft.asnNo.trim(), supplierPartnerId: blankAsAbsent(draft.supplierPartnerId),
        lines: draft.lines.map((l) => ({ skuId: l.skuId.trim(), lotNo: blankAsAbsent(l.lotNo), quantity: Number(l.quantity) })),
      },
    }), (r) => r.id);
  }

  receiveAsn(id: string, counts: ReceiptCountDraft[], crossDock: boolean): Promise<void> {
    return this.call(this.warehouse.receiveAsn({
      id, receiveAsnRequest: {
        crossDock,
        lines: counts.map((c) => ({
          lineNo: c.lineNo, receivedQty: Number(c.receivedQty), damagedQty: Number(c.damagedQty || 0),
        })),
      },
    }), () => undefined);
  }

  putaway(draft: StockMoveDraft): Promise<void> {
    return this.call(this.warehouse.putaway({ putawayRequest: stockMove(draft) }), () => undefined);
  }

  stock(warehouseId: string): Promise<Row[]> {
    return this.call(this.warehouse.listStock({ warehouseId, limit: 200 }), (s) => s.items.map(flatRow));
  }

  stockMovements(skuId: string): Promise<Row[]> {
    return this.call(this.warehouse.listStockMovements({ skuId, limit: 100 }), (s) => s.items.map(flatRow));
  }

  adjustStock(draft: StockMoveDraft): Promise<string> {
    return this.call(this.warehouse.adjustStock({ adjustStockRequest: { ...stockMove(draft), reason: draft.reason.trim() } }),
      (r) => r.id);
  }

  releaseQuarantine(draft: StockMoveDraft): Promise<string> {
    return this.call(this.warehouse.releaseQuarantinedStock({
      releaseQuarantineRequest: { ...stockMove(draft), reason: draft.reason.trim() },
    }), (r) => r.id);
  }

  writeOffQuarantine(warehouseId: string, draft: StockMoveDraft): Promise<string> {
    return this.call(this.warehouse.writeOffQuarantinedStock({
      writeOffQuarantineRequest: {
        warehouseId, skuId: draft.skuId.trim(), lotNo: blankAsAbsent(draft.lotNo), quantity: Number(draft.quantity),
        reason: draft.reason.trim(),
      },
    }), (r) => r.id);
  }

  outboundOrders(warehouseId: string): Promise<Row[]> {
    return this.call(this.warehouse.listOutboundOrders({ warehouseId, limit: 100 }), (s) => s.items.map(flatRow));
  }

  outboundOrder(id: string): Promise<WarehouseDocument> {
    return this.call(this.warehouse.getOutboundOrder({ id }), (o) => documentOf(o, o.lines));
  }

  createOutboundOrder(draft: OutboundDraft): Promise<string> {
    return this.call(this.warehouse.createOutboundOrder({
      createOutboundOrderRequest: {
        warehouseId: draft.warehouseId, orderNo: draft.orderNo.trim(), shipTo: blankAsAbsent(draft.shipTo),
        lines: draft.lines.map((l) => ({ skuId: l.skuId.trim(), quantity: Number(l.quantity) })),
      },
    }), (r) => r.id);
  }

  pickOutboundOrder(id: string): Promise<void> {
    return this.call(this.warehouse.pickOutboundOrder({ id }), () => undefined);
  }

  packOutboundOrder(id: string): Promise<string> {
    return this.call(this.warehouse.packOutboundOrder({ id }), (r) => r.id);
  }

  cancelBackOrder(id: string): Promise<void> {
    return this.call(this.warehouse.cancelOutboundOrderBackOrder({ id }), () => undefined);
  }

  handlingUnits(warehouseId: string): Promise<Row[]> {
    return this.call(this.warehouse.listHandlingUnits({ warehouseId, limit: 100 }), (s) => s.items.map(flatRow));
  }

  handlingUnit(id: string): Promise<WarehouseDocument> {
    return this.call(this.warehouse.getHandlingUnit({ id }), (u) => documentOf(u, u.contents));
  }

  dispatchHandlingUnit(id: string, draft: DispatchDraft): Promise<void> {
    return this.call(this.warehouse.dispatchHandlingUnit({
      id, dispatchRequest: {
        reference: blankAsAbsent(draft.reference), destinationTerminalId: blankAsAbsent(draft.destinationTerminalId),
        consigneePartnerId: blankAsAbsent(draft.consigneePartnerId), grossWeightKg: numberOrAbsent(draft.grossWeightKg),
      },
    }), () => undefined);
  }

  pickWaves(warehouseId: string): Promise<Row[]> {
    return this.call(this.warehouse.listPickWaves({ warehouseId, limit: 100 }), (s) => s.items.map(flatRow));
  }

  pickWave(id: string): Promise<WarehouseDocument> {
    return this.call(this.warehouse.getPickWave({ id }), (w) => documentOf(w, w.orders));
  }

  releasePickWave(warehouseId: string, orderIds: string[]): Promise<string> {
    return this.call(this.warehouse.releasePickWave({ releasePickWaveRequest: { warehouseId, orderIds } }), (r) => r.id);
  }

  pickPickWave(id: string): Promise<void> {
    return this.call(this.warehouse.pickPickWave({ id }), () => undefined);
  }

  // Control tower

  exceptionTickets(filter: ExceptionFilter): Promise<Row[]> {
    return this.call(this.controlTower.listExceptions({
      status: filter.status || undefined, severity: (filter.severity || undefined) as 'P1' | 'P2' | 'P3' | undefined,
      limit: 50,
    }), (page) => page.items.map(ticketRow));
  }

  exceptionTicket(id: string): Promise<Row> {
    return this.call(this.controlTower.getException({ id }), ticketRow);
  }

  exceptionHistory(id: string): Promise<Row[]> {
    return this.call(this.controlTower.getExceptionHistory({ id }), (steps) => steps.map((a) => ({
      action: text(a.action), fromStatus: text(a.fromStatus), toStatus: text(a.toStatus), note: text(a.note),
      actor: text(a.actor), occurredAt: instant(a.occurredAt),
    })));
  }

  raiseException(draft: ExceptionDraft): Promise<string> {
    return this.call(this.controlTower.raiseException({
      raiseExceptionRequest: {
        tripId: draft.tripId.trim(), category: draft.category as ExceptionCategory, description: draft.description.trim(),
      },
    }), (r) => text(r.id));
  }

  assignException(id: string, assigneeUserId: string, note: string): Promise<void> {
    return this.call(this.controlTower.assignException({
      id, assignExceptionRequest: { assigneeUserId: assigneeUserId.trim(), note: note.trim() || undefined },
    }), () => undefined);
  }

  investigateException(id: string, note: string): Promise<void> {
    return this.call(this.controlTower.investigateException({ id, exceptionNoteRequest: noteOf(note) }), () => undefined);
  }

  resolveException(id: string, resolution: string): Promise<void> {
    return this.call(this.controlTower.resolveException({
      id, resolveExceptionRequest: { resolution: resolution.trim() },
    }), () => undefined);
  }

  reopenException(id: string, reason: string): Promise<void> {
    return this.call(this.controlTower.reopenException({ id, reopenExceptionRequest: { reason: reason.trim() } }),
      () => undefined);
  }

  closeException(id: string, note: string): Promise<void> {
    return this.call(this.controlTower.closeException({ id, exceptionNoteRequest: noteOf(note) }), () => undefined);
  }

  userChoices(): Promise<Choice[]> {
    return this.call(this.users.listUsers(), (users) => users.map((u) => ({
      id: text(u.id), label: `${text(u.fullName)} · ${text(u.email)}`,
    })));
  }

  detectorSettings(): Promise<DetectorSettingsView> {
    return this.call(this.controlTower.getDetectorSettings(), (d) => ({
      etaSlipMinutes: d.etaSlipMinutes ?? 0, temperatureExcursionMinutes: d.temperatureExcursionMinutes ?? 0,
      customised: d.customised ?? false,
    }));
  }

  saveDetectorSettings(etaSlipMinutes: number, temperatureExcursionMinutes: number): Promise<void> {
    return this.call(this.controlTower.setDetectorSettings({
      setDetectorSettingsRequest: { etaSlipMinutes, temperatureExcursionMinutes },
    }), () => undefined);
  }

  tripWatch(tripId: string): Promise<TripWatchView | null> {
    return absentOn404(this.call(this.controlTower.getTripWatch({ tripId }), (w) => ({
      committedArrivalAt: instant(w.committedArrivalAt), tempMinC: text(w.tempMinC), tempMaxC: text(w.tempMaxC),
      outsideRangeSince: instant(w.outsideRangeSince), lastReadingAt: instant(w.lastReadingAt),
    })));
  }

  setTripWatch(tripId: string, draft: TripWatchDraft): Promise<void> {
    return this.call(this.controlTower.setTripWatch({
      tripId,
      setTripWatchRequest: {
        committedArrivalAt: draft.committedArrivalAt ? new Date(draft.committedArrivalAt).toISOString() : undefined,
        tempMinC: decimalOrAbsent(draft.tempMinC), tempMaxC: decimalOrAbsent(draft.tempMaxC),
      },
    }), () => undefined);
  }

  clearTripWatch(tripId: string): Promise<void> {
    return this.call(this.controlTower.clearTripWatch({ tripId }), () => undefined);
  }

  async tripActivity(tripId: string): Promise<TripActivity> {
    const [progress, crossings] = await Promise.all([
      absentOn404(this.call(this.telematics.getProgress({ tripId }), (p) => p)),
      this.call(this.telematics.listCrossings({ tripId }), (c) => c),
    ]);
    return {
      progress: progress && {
        tripNo: text(progress.tripNo), status: text(progress.status), lastFixAt: instant(progress.lastFixAt),
        completionPct: text(progress.completionPct), remainingDistanceM: text(progress.remainingDistanceM),
        estimatedMinutesRemaining: text(progress.estimatedMinutesRemaining),
        offRoute: progress.offRoute ? 'Yes' : '', deviationM: text(progress.deviationM),
      },
      crossings: crossings.map((c) => ({
        id: text(c.id), eventType: text(c.eventType), terminalCode: text(c.terminalCode), occurredAt: instant(c.occurredAt),
      })),
    };
  }

  vehiclePositions(): Promise<Mapped> {
    return this.call(this.telematics.listPositions(), (positions) => ({
      rows: positions.map((v) => ({
        id: text(v.vehicleId), registrationNo: text(v.registrationNo), tripId: text(v.tripId), tripNo: text(v.tripNo),
        tripStatus: text(v.tripStatus), speedKph: text(v.speedKph), recordedAt: instant(v.recordedAt),
        ageSeconds: text(v.ageSeconds),
      })),
      features: positions
        .filter((v) => v.lat !== undefined && v.lon !== undefined)
        .map((v): MapFeature => ({
          id: text(v.vehicleId), kind: 'vehicle', label: `${text(v.registrationNo)}${v.tripNo ? ` · ${v.tripNo}` : ''}`,
          points: [{ lat: v.lat as number, lon: v.lon as number }],
        })),
    }));
  }

  openDeviations(): Promise<Row[]> {
    return this.call(this.telematics.listOpenDeviations(), (rows) => rows.map((d) => ({
      id: text(d.id), tripId: text(d.tripId), tripNo: text(d.tripNo), detectedAt: instant(d.detectedAt),
      distanceM: text(d.distanceM), corridorM: text(d.corridorM),
    })));
  }

  // Rate cards and rail tariffs
  tariffs(filter: TariffFilter, cursor?: string): Promise<RateCardPage> {
    return this.call(this.freightBills.listTariffs({
      vendorPartnerId: filter.vendorPartnerId.trim() || undefined,
      superseded: filter.state === '' ? undefined : filter.state === 'superseded',
      cursor, limit: RATE_CARD_PAGE,
    }), (s) => ({ rows: s.items.map(rateCardRow), nextCursor: s.nextCursor ?? null }));
  }

  tariffCard(id: string): Promise<TariffCardDetail> {
    return this.call(this.freightBills.getTariff({ id }), rateCardDetailOf);
  }

  railTariffs(cursor?: string): Promise<RateCardPage> {
    return this.call(this.multimodal.listRailTariffs({ cursor, limit: RATE_CARD_PAGE }),
      (s) => ({ rows: (s.items ?? []).map(railRateCardRow), nextCursor: s.nextCursor ?? null }));
  }

  railTariff(id: string): Promise<Row> {
    return this.call(this.multimodal.getRailTariff({ id }), railRateCardRow);
  }

  publishRailTariff(draft: RailTariffDraft): Promise<string> {
    const figure = (value: string): number | undefined => (value.trim() ? Number(value.trim()) : undefined);
    return this.call(this.multimodal.publishRailTariff({
      publishRailTariffRequest: {
        code: draft.code.trim(), carrierPartnerId: draft.carrierPartnerId.trim(),
        originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
        effectiveFrom: draft.effectiveFrom, currency: draft.currency.trim() || undefined,
        perWagonRate: figure(draft.perWagonRate), perContainerRate: figure(draft.perContainerRate),
        minimumCharge: figure(draft.minimumCharge),
      },
    }), (r) => text(r.id));
  }

  /** Each distinct terminal named, read once. */
  private async terminalsById(ids: Array<string | null | undefined>): Promise<Map<string, Terminal>> {
    const distinct = [...new Set(ids.filter((id): id is string => !!id))];
    const read = await Promise.all(distinct.map((id) => this.call(this.terminals.getTerminal({ id }), (t) => t)));
    return new Map(read.map((t) => [text(t.id), t]));
  }

  /** Awaits a generated call, maps its result, and turns any failure into an ApiProblem. */
  private async call<T, R>(request: Observable<T>, map: (value: T) => R): Promise<R> {
    try {
      return map(await firstValueFrom(request));
    } catch (e) {
      if (e instanceof HttpErrorResponse) {
        const body = e.error as { detail?: string; code?: string; errors?: Record<string, string> } | null;
        if (body?.errors && e.status !== 409) {
          throw new ApiProblem(Object.entries(body.errors).map(([k, v]) => `${k}: ${v}`).join('; '), e.status, body.errors,
            body.code ?? '');
        }
        throw new ApiProblem(body?.detail ?? (e.status ? `${e.status} ${e.statusText}` : 'Could not reach the API. Try again.'),
          e.status, body?.errors ?? {}, body?.code ?? '');
      }
      throw e;
    }
  }

  // Pickers
  pick({ kind, q, cursor, warehouseId }: PickQuery): Promise<PickPage> {
    const search = q.trim() || undefined;
    const at = cursor || undefined;
    const limit = 20;
    const page = <T>(items: T[], nextCursor: string | null | undefined, label: (item: T) => Choice): PickPage =>
      ({ items: items.map(label), nextCursor: nextCursor ?? null });
    switch (kind) {
      case 'partner':
      case 'customer':
      case 'vendor':
        return this.call(this.partners.listPartners({
          partnerType: kind === 'partner' ? undefined : kind === 'customer' ? 'CUSTOMER' : 'VENDOR',
          q: search, cursor: at, limit,
        }), (s) => page(s.items, s.nextCursor, (p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` })));
      case 'terminal':
      case 'warehouse':
        return this.call(this.terminals.listTerminals({
          category: kind === 'warehouse' ? 'WAREHOUSE' : undefined, q: search, cursor: at, limit,
        }), (s) => page(s.items, s.nextCursor, (t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` })));
      case 'vehicle':
        return this.call(this.vehicles.listVehicles({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (v) => ({ id: text(v.id), label: `${text(v.registrationNo)} · ${text(v.vehicleType)}` })));
      case 'driver':
        return this.call(this.drivers.listDrivers({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (d) => ({ id: text(d.id), label: `${text(d.licenceNo)} · ${text(d.fullName)}` })));
      case 'sku':
        return this.call(this.warehouse.listSkus({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (k) => ({ id: text(k.id), label: `${text(k.code)} · ${text(k.description)}` })));
      case 'bin':
        if (!warehouseId) {
          return Promise.resolve({ items: [], nextCursor: null });
        }
        return this.call(this.warehouse.listBins({ warehouseId, q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (b) => ({ id: text(b.id), label: text(b.code) })));
      case 'order':
        return this.call(this.orders.listOrders({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (o) => ({ id: text(o.id), label: `${text(o.orderNo)} · ${text(o.status)}` })));
      case 'load':
        return this.call(this.loads.listLoads({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (l) => ({ id: text(l.id), label: `${text(l.loadNo)} · ${text(l.status)}` })));
      case 'trip':
        return this.call(this.trips.listTrips({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (t) => ({ id: text(t.id), label: `${text(t.tripNo)} · ${text(t.status)}` })));
      case 'ticket':
        return this.call(this.controlTower.listExceptions({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (x) => ({
            id: text(x.id), label: `${text(x.ticketNo)} · ${text(x.category)} · ${text(x.status)}`,
          })));
      case 'allocation':
        return this.call(this.sourcing.listAllocations({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (a) => ({ id: text(a.id), label: `${text(a.loadNo)} · ${text(a.status)}` })));
      case 'bill':
        return this.call(this.freightBills.listFreightBills({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (b) => ({ id: text(b.id), label: `${text(b.billNo)} · ${text(b.status)}` })));
      case 'consignment':
        return this.call(this.loads.listConsignments({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (c) => ({ id: text(c.id), label: `${text(c.trackingRef)} · ${text(c.status)}` })));
      case 'unassigned':
        return this.call(this.loads.listUnassignedConsignments({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (c) => ({ id: text(c.id), label: `${text(c.trackingRef)} · ${text(c.status)}` })));
      case 'routing-guide':
        return this.call(this.sourcing.listRoutingGuides({ q: search, cursor: at, limit }),
          (s) => page(s.items, s.nextCursor, (g) => ({ id: text(g.id), label: guideLabel(g) })));
    }
  }

  // Administration
  private readonly users = inject(UsersService);
  private readonly accessPolicyApi = inject(AccessPoliciesService);
  private readonly compliance = inject(ComplianceService);

  adminUsers(): Promise<AdminUser[]> {
    return this.call(this.users.listUsers(), (list) => list.map(adminUserOf));
  }

  userPermissions(userId: string): Promise<string[]> {
    return this.call(this.users.getUserPermissions({ id: userId }), (codes) => [...codes].sort());
  }

  accessAttributes(userId: string): Promise<AccessAttributesDraft> {
    return this.call(this.accessPolicyApi.getUserAccessAttributes({ id: userId }), adminAttributesOf);
  }

  setAccessAttributes(userId: string, draft: AccessAttributesDraft): Promise<void> {
    return this.call(this.accessPolicyApi.setUserAccessAttributes({
      id: userId, setUserAccessAttributesRequest: adminAttributesRequest(draft),
    }), () => undefined);
  }

  accessPolicies(cursor?: string): Promise<AccessPolicyPage> {
    return this.call(this.accessPolicyApi.listAccessPolicies({ cursor, limit: 100 }), (page) => ({
      policies: page.items.map(adminPolicyOf), nextCursor: page.hasMore ? page.nextCursor ?? null : null,
    }));
  }

  async createAccessPolicy(draft: AccessPolicyDraft): Promise<string> {
    const condition = adminConditionOf(draft.condition);
    return this.call(this.accessPolicyApi.createAccessPolicy({
      createAccessPolicyRequest: {
        code: draft.code.trim(), action: draft.action.trim(),
        effect: draft.effect as CreateAccessPolicyRequestEffectEnum, description: draft.description.trim(),
        condition, enabled: draft.enabled,
      },
    }), (made) => text(made.id));
  }

  async reviseAccessPolicy(id: string, changes: AccessPolicyChanges): Promise<void> {
    const condition = adminConditionOf(changes.condition);
    return this.call(this.accessPolicyApi.reviseAccessPolicy({
      id,
      reviseAccessPolicyRequest: {
        effect: changes.effect as ReviseAccessPolicyRequestEffectEnum, description: changes.description.trim(),
        condition, enabled: changes.enabled,
      },
    }), () => undefined);
  }

  deleteAccessPolicy(id: string): Promise<void> {
    return this.call(this.accessPolicyApi.deleteAccessPolicy({ id }), () => undefined);
  }

  expiringDocuments(withinDays: number): Promise<Row[]> {
    return this.call(this.compliance.listExpiringDocuments({ withinDays }), (docs) => docs.map(adminDocumentRow));
  }

  ownerDocuments(ownerType: DocumentOwnerType, ownerId: string): Promise<Row[]> {
    const id = ownerId.trim();
    const listed = ownerType === 'PARTNER' ? this.partners.listPartnerDocuments({ id })
      : ownerType === 'VEHICLE' ? this.vehicles.listVehicleDocuments({ id })
        : this.drivers.listDriverDocuments({ id });
    return this.call(listed, (docs) => docs.map(adminDocumentRow));
  }

  attachDocument(ownerType: DocumentOwnerType, ownerId: string, draft: DocumentDraft): Promise<string> {
    const id = ownerId.trim();
    const attachDocumentRequest = adminDocumentRequest(draft);
    const made = ownerType === 'PARTNER' ? this.partners.attachPartnerDocument({ id, attachDocumentRequest })
      : ownerType === 'VEHICLE' ? this.vehicles.attachVehicleDocument({ id, attachDocumentRequest })
        : this.drivers.attachDriverDocument({ id, attachDocumentRequest });
    return this.call(made, (r) => text(r.id));
  }

  // Commercial
  publishTariff(orgUnitId: string, draft: TariffDraft): Promise<string> {
    return this.call(this.freightBills.publishTariff({
      publishTariffRequest: {
        orgUnitId, code: draft.code.trim(), vendorPartnerId: draft.vendorPartnerId.trim(),
        originTerminalId: draft.originTerminalId.trim(), destinationTerminalId: draft.destinationTerminalId.trim(),
        vehicleType: draft.vehicleType.trim(), effectiveFrom: draft.effectiveFrom,
        currency: commercialBlank(draft.currency), minimumCharge: commercialNumber(draft.minimumCharge),
        detentionFreeHours: commercialNumber(draft.detentionFreeHours),
        detentionHourlyRate: commercialNumber(draft.detentionHourlyRate),
        additionalDropFee: commercialNumber(draft.additionalDropFee),
      },
    }), (r) => text(r.id));
  }

  addTariffSlab(tariffId: string, slab: SlabDraft): Promise<string> {
    return this.call(this.freightBills.addTariffSlab({
      id: tariffId,
      addSlabRequest: {
        minWeightKg: Number(slab.minWeightKg), maxWeightKg: commercialNumber(slab.maxWeightKg),
        rateBasis: slab.rateBasis as unknown as AddSlabRequestRateBasisEnum, rateAmount: Number(slab.rateAmount),
      },
    }), (r) => text(r.id));
  }

  recordFuelSurcharge(month: string, surchargePct: number): Promise<string> {
    return this.call(this.freightBills.recordFuelSurcharge({ fuelSurchargeRequest: { month, surchargePct } }),
      (r) => text(r.id));
  }

  defineRoutingGuide(orgUnitId: string, draft: GuideDraft): Promise<string> {
    return this.call(this.sourcing.defineRoutingGuide({
      defineGuideRequest: {
        orgUnitId, originTerminalId: draft.originTerminalId.trim(),
        destinationTerminalId: draft.destinationTerminalId.trim(), vehicleType: draft.vehicleType.trim(),
        strategy: draft.strategy as unknown as DefineGuideRequestStrategyEnum,
      },
    }), (r) => text(r.id));
  }

  routingGuide(id: string): Promise<RoutingGuideDetail> {
    return this.call(this.sourcing.getRoutingGuide({ id }), commercialGuideOf);
  }

  addGuideVendor(guideId: string, draft: GuideVendorDraft): Promise<void> {
    return this.call(this.sourcing.addRoutingGuideVendor({
      id: guideId,
      addVendorRequest: {
        vendorPartnerId: draft.vendorPartnerId.trim(), rankNo: draft.rankNo,
        responseSlaMinutes: draft.responseSlaMinutes, agreedRate: commercialNumber(draft.agreedRate),
      },
    }), () => undefined);
  }

  retireRoutingGuide(id: string): Promise<void> {
    return this.call(this.sourcing.retireRoutingGuide({ id }), () => undefined);
  }

  allocations(status?: string): Promise<Row[]> {
    return this.call(this.sourcing.listAllocations({ status: status || undefined, limit: 100 }),
      (page) => page.items.map(commercialAllocationRow));
  }

  async allocationDetail(id: string): Promise<AllocationDetail> {
    const [allocation, offers] = await Promise.all([
      this.call(this.sourcing.getAllocation({ id }), commercialAllocationRow),
      this.call(this.sourcing.listOffers({ id }), (rows) => rows.map(commercialOfferRow)),
    ]);
    return { allocation, offers };
  }

  allocateLoad(loadId: string): Promise<string> {
    return this.call(this.sourcing.allocateLoad({ allocateRequest: { loadId: loadId.trim() } }), (r) => text(r.id));
  }

  acceptOffer(allocationId: string, vendorPartnerId: string): Promise<void> {
    return this.call(this.sourcing.acceptOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } }),
      () => undefined);
  }

  rejectOffer(allocationId: string, vendorPartnerId: string): Promise<void> {
    return this.call(this.sourcing.rejectOffer({ id: allocationId, vendorResponseRequest: { vendorPartnerId } }),
      () => undefined);
  }

  cancelAllocation(id: string): Promise<void> {
    return this.call(this.sourcing.cancelAllocation({ id }), () => undefined);
  }

  freightBillList(status?: string): Promise<Row[]> {
    return this.call(this.freightBills.listFreightBills({ status: status || undefined, limit: 100 }),
      (page) => page.items.map(commercialBillRow));
  }

  async freightBill(id: string): Promise<FreightBillDetail> {
    const [bill, lines] = await Promise.all([
      this.call(this.freightBills.getFreightBill({ id }), commercialBillRow),
      this.call(this.freightBills.getFreightBillLines({ id }), (rows) => rows.map(commercialBillLineRow)),
    ]);
    return { bill, lines };
  }

  matchFreightBill(id: string, claimedAmount: number): Promise<string> {
    return this.call(this.freightBills.matchFreightBill({ id, matchRequest: { claimedAmount } }), (r) => text(r.status));
  }

  resolveFreightBill(id: string, reason: string): Promise<void> {
    return this.call(this.freightBills.resolveDispute({ id, resolveDisputeRequest: { reason } }), () => undefined);
  }
}

// Administration

function adminUserOf(u: User): AdminUser {
  return {
    id: text(u.id), email: text(u.email), fullName: text(u.fullName), status: text(u.status),
    orgName: text(u.orgName), lastLoginAt: text(u.lastLoginAt),
  };
}

function adminAttributesOf(a: UserAccessAttributes): AccessAttributesDraft {
  return {
    region: text(a.region), freightModes: (a.freightModes ?? []).map((m) => m as string as AccessFreightMode),
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
    issuingAuthority: blankAsAbsent(d.issuingAuthority), issuedOn: blankAsAbsent(d.issuedOn),
    expiresOn: blankAsAbsent(d.expiresOn),
  };
}

// Warehouse operations

/** A record's scalar fields as display cells; nested lists are mapped by the caller. */
function flatRow(record: object): Row {
  return Object.fromEntries(Object.entries(record)
    .filter(([, v]) => v === null || v === undefined || typeof v !== 'object')
    .map(([k, v]) => [k, text(v)]));
}

function documentOf(header: object, lines: object[] | undefined): WarehouseDocument {
  return { header: flatRow(header), lines: (lines ?? []).map(flatRow) };
}

function stockMove(d: StockMoveDraft) {
  return { binId: d.binId.trim(), skuId: d.skuId.trim(), lotNo: blankAsAbsent(d.lotNo), quantity: Number(d.quantity) };
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

// Rate cards and rail tariffs

/** Rate cards read a page at a time; the desk follows the cursor for more. */
const RATE_CARD_PAGE = 50;

function rateCardRow(t: RateCard | RateCardWithBands): Row {
  return {
    id: text(t.id), code: text(t.code), orgUnitName: text(t.orgUnitName), vendorCode: text(t.vendorCode),
    vendorName: text(t.vendorName), originTerminalCode: text(t.originTerminalCode),
    destinationTerminalCode: text(t.destinationTerminalCode), vehicleType: text(t.vehicleType),
    currency: text(t.currency), effectiveFrom: text(t.effectiveFrom), effectiveTo: text(t.effectiveTo),
    superseded: t.superseded ? 'Yes' : '', minimumCharge: text(t.minimumCharge),
    detentionFreeHours: text(t.detentionFreeHours), detentionHourlyRate: text(t.detentionHourlyRate),
    additionalDropFee: text(t.additionalDropFee), slabCount: text(t.slabCount),
  };
}

function rateCardDetailOf(t: RateCardWithBands): TariffCardDetail {
  return {
    card: rateCardRow(t),
    slabs: (t.slabs ?? []).map((b: RateCardBand) => ({
      minWeightKg: text(b.minWeightKg), maxWeightKg: b.maxWeightKg === undefined || b.maxWeightKg === null ? '—' : text(b.maxWeightKg),
      rateBasis: text(b.rateBasis), rateAmount: text(b.rateAmount),
    })),
  };
}

function railRateCardRow(r: RailRateCard): Row {
  return {
    id: text(r.id), code: text(r.code), carrierCode: text(r.carrierCode), originTerminalCode: text(r.originTerminalCode),
    destinationTerminalCode: text(r.destinationTerminalCode), currency: text(r.currency),
    perWagonRate: text(r.perWagonRate), perContainerRate: text(r.perContainerRate), minimumCharge: text(r.minimumCharge),
    effectiveFrom: text(r.effectiveFrom), effectiveTo: text(r.effectiveTo),
  };
}

/** A routing guide is its lane and vehicle type; a retired one says so. */
function guideLabel(g: CommercialRoutingGuide): string {
  const lane = `${text(g.originTerminalCode)} → ${text(g.destinationTerminalCode)} · ${text(g.vehicleType)}`;
  return g.active === false ? `${lane} · retired` : lane;
}
