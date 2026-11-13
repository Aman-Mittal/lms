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
import { formatDay, formatInstant } from '../display';
import {
  AllocationRulesApi, AuthenticationApi, ControlTowerApi, Configuration, CustomFieldsApi, DeliveryAndClaimsApi,
  DriversApi, EventDeadLetterApi, LTLRatingApi, OrdersApi, MilkRunsApi, MultimodalApi, PartnersApi, PlanningApi,
  PublicTrackingApi, ResponseError, SettlementApi, TerminalsApi, SourcingApi, TelematicsApi, TrackingLinksApi,
  TripsApi, VehiclesApi, WebhooksApi, type Consignment, type CustomFieldDefinition, type AllocationRule,
  type AllocationRuleAction, type Load, type AllocationRuleRequest, type AddOrderLineRequest, type Order,
  type OrderLine, type UpdateOrderRequest, type HistoryEntry, type AllocationRuleRequestMaterialClassEnum,
  type Terminal, type ParkedEvent, type WebhookDelivery, type WebhookSubscription, DemoDataApi, ApprovalsApi,
  type Approval as ApiApproval, ClientBillingApi, type ClientCharge, type ClientContract, type ClientContractLane,
  type ClientContractLaneRequest, type ClientInvoice, type ClientInvoiceLine, type ClientStatementEntry,
  type Exception as ApiException,
} from '../api';
import {
  type ApprovalPending, type ContractDetail, type ContractDraft, type ContractLaneChoice, type ContractLaneDraft,
  ApiProblem, type AllocationRuleView, type ConsignmentCard, type ConsignmentParticularsDraft,
  type CustomFieldKind, type FieldDefinition, type FieldType, type ResourceKey, type Row, type RuleAction,
  type RuleDraft, type GeoPoint, type MapFeature, type WebhookView, type OrderDesk, type OrderHeaderDraft,
  type OrderLineDraft, type HistoryEntryRow,
} from './lmsGateway';
import { commercialNumber } from './gatewayCommercial';

/** What the verb groups share: the generated clients, the token and the cached read. */
export interface GatewayDeps {
  getToken: () => string | null;
  config: Configuration;
  auth: AuthenticationApi;
  controlTower: ControlTowerApi;
  demo: DemoDataApi;
  partners: PartnersApi;
  vehicles: VehiclesApi;
  drivers: DriversApi;
  terminals: TerminalsApi;
  orders: OrdersApi;
  loads: PlanningApi;
  trips: TripsApi;
  freightBills: SettlementApi;
  customFields: CustomFieldsApi;
  telematics: TelematicsApi;
  multimodal: MultimodalApi;
  milkRuns: MilkRunsApi;
  ltl: LTLRatingApi;
  deadLetters: EventDeadLetterApi;
  approvalsApi: ApprovalsApi;
  webhooks: WebhooksApi;
  delivery: DeliveryAndClaimsApi;
  links: TrackingLinksApi;
  rules: AllocationRulesApi;
  sourcing: SourcingApi;
  clientBilling: ClientBillingApi;
  publicTracking: PublicTrackingApi;
  cachedGet: <T>(key: string, path: string, load: () => Promise<T>) => Promise<T>;
  listClaims: (consignmentId?: string) => Promise<Row[]>;
  listers: Record<ResourceKey, () => Promise<Row[]>>;
}

/** A ticket as the exception desk lists and opens it. */
export function ticketRow(e: ApiException): Row {
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
export function decimalOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

/** A blank note is no note. */
export function noteOf(note: string): { note: string } | undefined {
  return note.trim() ? { note: note.trim() } : undefined;
}

/** The call's result, or null when the API answers 404; any other failure as an ApiProblem. */
export async function absentOn404<T>(call: () => Promise<T>): Promise<T | null> {
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
export function loadRow(l: Load): Row {
  return {
    id: text(l.id), loadNo: text(l.loadNo), status: text(l.status), freightMode: text(l.freightMode),
    vehicleRegistrationNo: text(l.vehicleRegistrationNo), plannedWeightKg: text(l.plannedWeightKg),
    capacityWeightKg: text(l.capacityWeightKg), weightUtilisationPct: text(l.weightUtilisationPct),
    consignmentCount: text(l.consignmentCount), version: text(l.version),
  };
}

/** A consignment as a load list or a work queue shows it. */
export function consignmentRow(c: Consignment): Row {
  return {
    id: text(c.id), trackingRef: text(c.trackingRef), status: text(c.status),
    consigneeName: text(c.consigneeName), destinationTerminalCode: text(c.destinationTerminalCode),
    totalDeadWeightKg: text(c.totalDeadWeightKg), materialClasses: text(c.materialClasses),
    containsHazmat: c.containsHazmat ? 'Yes' : '',
  };
}

/** A client contract version as the billing desk lists it. */
export function contractRow(c: ClientContract): Row {
  return {
    id: text(c.id), code: text(c.code), currency: text(c.currency), effectiveFrom: text(c.effectiveFrom),
    effectiveTo: text(c.effectiveTo), transportRatePerKg: text(c.transportRatePerKg),
    handlingInRatePerPallet: text(c.handlingInRatePerPallet), handlingOutRatePerPallet: text(c.handlingOutRatePerPallet),
    storageRatePerPalletDay: text(c.storageRatePerPalletDay),
    billing: c.billOnDispatch ? 'On dispatch' : 'On proof of delivery',
    status: text(c.status), state: text(c.state), validTill: text(c.validTill), version: text(c.version),
  };
}

/** One contract version in full, for the lifecycle panel. */
export function contractDetailOf(c: ClientContract): ContractDetail {
  return {
    id: text(c.id), code: text(c.code), clientPartnerId: text(c.clientPartnerId), orgUnitId: text(c.orgUnitId),
    status: text(c.status), state: text(c.state), effectiveFrom: text(c.effectiveFrom),
    effectiveTo: text(c.effectiveTo), validTill: text(c.validTill), rejectionReason: text(c.rejectionReason),
    version: text(c.version), currency: text(c.currency), transportRatePerKg: text(c.transportRatePerKg),
    transportMinimumCharge: text(c.transportMinimumCharge), storageRatePerPalletDay: text(c.storageRatePerPalletDay),
    handlingInRatePerPallet: text(c.handlingInRatePerPallet), handlingOutRatePerPallet: text(c.handlingOutRatePerPallet),
    transportTaxRatePct: text(c.transportTaxRatePct), warehouseTaxRatePct: text(c.warehouseTaxRatePct),
    storageUnitsPerPallet: c.storageUnitsPerPallet ?? 1, billOnDispatch: !!c.billOnDispatch,
    services: (c.valueAddedServices ?? []).map((v) => ({ serviceCode: text(v.serviceCode), unit: text(v.unit), rate: text(v.rate) })),
    directOrdersAllowed: c.directOrdersAllowed ?? true, allowedLoadTypes: c.allowedLoadTypes ?? [],
    lanes: (c.lanes ?? []).map(laneDraftOf),
  };
}

/** A stored lane as the editor restates it: terminal ids or zone codes, slabs as one text. */
function laneDraftOf(l: ClientContractLane): ContractLaneDraft {
  return {
    laneCode: text(l.laneCode), originTerminalId: text(l.originTerminalId), originZone: text(l.originZone),
    destinationTerminalId: text(l.destinationTerminalId), destinationZone: text(l.destinationZone),
    freightMode: text(l.freightMode), loadType: text(l.loadType), rateBasis: text(l.rateBasis), rate: text(l.rate),
    transitDays: text(l.transitDays),
    slabs: (l.slabs ?? []).map((s) => `${text(s.minWeightKg)}-${text(s.maxWeightKg)}:${text(s.rateBasis)}:${text(s.rate)}`)
      .join('; '),
    consigneeIds: (l.consigneeIds ?? []).join(', '),
  };
}

/** Weight slabs typed as `min-max:BASIS:rate; ...`, a blank max being the open top band. */
function slabRequests(slabs: string): NonNullable<ClientContractLaneRequest['slabs']> {
  return slabs.split(';').map((part) => part.trim()).filter(Boolean).map((part) => {
    const [band = '', basis = '', rate = ''] = part.split(':').map((piece) => piece.trim());
    const [min = '', max = ''] = band.split('-').map((piece) => piece.trim());
    return {
      minWeightKg: Number(min), maxWeightKg: max ? Number(max) : undefined,
      rateBasis: basis as unknown as NonNullable<ClientContractLaneRequest['slabs']>[number]['rateBasis'],
      rate: Number(rate),
    };
  });
}

/** A lane as the request carries it: a blank end, mode, load type or list is sent as absent. */
export function laneRequest(l: ContractLaneDraft): ClientContractLaneRequest {
  return {
    laneCode: l.laneCode.trim(), originTerminalId: blankAsAbsent(l.originTerminalId),
    originZone: blankAsAbsent(l.originZone), destinationTerminalId: blankAsAbsent(l.destinationTerminalId),
    destinationZone: blankAsAbsent(l.destinationZone),
    freightMode: blankAsAbsent(l.freightMode) as unknown as ClientContractLaneRequest['freightMode'],
    loadType: blankAsAbsent(l.loadType), rateBasis: l.rateBasis as unknown as ClientContractLaneRequest['rateBasis'],
    rate: numberOrAbsent(l.rate), transitDays: numberOrAbsent(l.transitDays), slabs: slabRequests(l.slabs),
    consigneeIds: l.consigneeIds.split(',').map((id) => id.trim()).filter(Boolean),
  };
}

/** A lane of the contract in force as the order desk offers it, in one line. */
export function laneChoiceOf(l: ClientContractLane): ContractLaneChoice {
  const place = (terminal?: string, code?: string, zone?: string) => text(code) || (zone ? `zone ${zone}` : text(terminal));
  const route = `${place(l.originTerminalId, l.originTerminalCode, l.originZone)} to `
    + place(l.destinationTerminalId, l.destinationTerminalCode, l.destinationZone);
  const extras = [text(l.freightMode), text(l.loadType), l.transitDays ? `${l.transitDays} days` : ''].filter(Boolean);
  return {
    id: text(l.id), laneCode: text(l.laneCode), originTerminalId: text(l.originTerminalId),
    destinationTerminalId: text(l.destinationTerminalId), freightMode: text(l.freightMode), loadType: text(l.loadType),
    transitDays: text(l.transitDays), label: `${text(l.laneCode)}: ${route}${extras.length ? ` (${extras.join(', ')})` : ''}`,
  };
}

/** The terms of a contract draft as the request carries them: a blank is sent as absent. */
export function contractTerms(draft: ContractDraft) {
  return {
    code: blankAsAbsent(draft.code), effectiveFrom: day(draft.effectiveFrom),
    validTill: draft.validTill ? day(draft.validTill) : undefined,
    transportRatePerKg: commercialNumber(draft.transportRatePerKg),
    transportMinimumCharge: commercialNumber(draft.transportMinimumCharge),
    storageRatePerPalletDay: commercialNumber(draft.storageRatePerPalletDay),
    handlingInRatePerPallet: commercialNumber(draft.handlingInRatePerPallet),
    handlingOutRatePerPallet: commercialNumber(draft.handlingOutRatePerPallet),
  };
}

/** 202 with the approval request, or 204 when the action was done at once. */
export async function pendingOf(
  raw: { raw: Response; value(): Promise<{ id: string; status: string }> },
): Promise<ApprovalPending | null> {
  if (raw.raw.status !== 202) {
    return null;
  }
  const t = await raw.value();
  return { id: text(t.id), status: text(t.status) };
}

/** A charge as the billing desk lists it; `invoiced` and `late` read Yes or nothing. */
export function chargeRow(c: ClientCharge): Row {
  return {
    id: text(c.id), occurredOn: text(c.occurredOn), serviceType: text(c.serviceType), serviceCode: text(c.serviceCode),
    quantity: text(c.quantity), unit: text(c.unit), reference: text(c.reference),
    invoiced: c.invoiceId ? 'Yes' : '', late: c.late ? 'Yes' : '',
    billable: c.billable === false ? 'Awaiting proof' : 'Yes',
  };
}

export function invoiceRow(i: ClientInvoice): Row {
  return {
    id: text(i.id), invoiceNo: text(i.invoiceNo), periodStart: text(i.periodStart), periodEnd: text(i.periodEnd),
    status: text(i.status), currency: text(i.currency), taxableAmount: text(i.taxableAmount),
    taxAmount: text(i.taxAmount), totalAmount: text(i.totalAmount), creditedAmount: text(i.creditedAmount),
    lineCount: text(i.lineCount), issuedAt: text(i.issuedAt), clientName: text(i.clientName),
  };
}

export function invoiceLineRow(l: ClientInvoiceLine): Row {
  return {
    lineNo: text(l.lineNo), serviceType: text(l.serviceType), narrative: text(l.narrative),
    quantity: text(l.quantity), unit: text(l.unit), rate: text(l.rate), amount: text(l.amount),
    taxAmount: text(l.taxAmount), lineTotal: text(l.lineTotal), creditedAmount: text(l.creditedAmount),
  };
}

export function statementRow(e: ClientStatementEntry): Row {
  return {
    id: text(e.documentId), entryType: text(e.entryType), documentNo: text(e.documentNo), issuedAt: text(e.issuedAt),
    invoiceNo: text(e.invoiceNo), reason: text(e.reason), debit: text(e.debit), credit: text(e.credit),
    balance: text(e.balance),
  };
}

/** A `YYYY-MM-DD` day as the generated client sends a date. */
export function day(value: string): Date {
  return new Date(`${value}T00:00:00Z`);
}

/** A display cell: the contract's optional fields become an empty string. */
export function text(v: unknown): string {
  if (v === null || v === undefined) {
    return '';
  }
  if (v instanceof Date) {
    // The person's date format and time zone (display.ts); a calendar day is never shifted.
    return v.toISOString().endsWith('T00:00:00.000Z') ? formatDay(v) : formatInstant(v);
  }
  return String(v);
}

export function definitionOf(d: CustomFieldDefinition): FieldDefinition {
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

export function ruleOf(r: AllocationRule): AllocationRuleView {
  return {
    id: text(r.id), name: text(r.name), priority: r.priority ?? 0, active: r.active ?? true,
    originTerminalId: text(r.originTerminalId), destinationTerminalId: text(r.destinationTerminalId),
    minWeightKg: text(r.minWeightKg), maxWeightKg: text(r.maxWeightKg),
    materialClass: text(r.materialClass), action: (r.action ?? 'PREFER') as RuleAction,
    vendors: (r.vendors ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
  };
}

export function consignmentCard(c: Consignment): ConsignmentCard {
  return {
    id: text(c.id), trackingRef: text(c.trackingRef), status: text(c.status), orderNo: text(c.orderNo),
    consigneeName: text(c.consigneeName), destinationTerminalCode: text(c.destinationTerminalCode),
    deadWeightKg: text(c.totalDeadWeightKg), volumeM3: text(c.totalVolumeM3),
    chargeableWeightKg: text(c.chargeableWeightKg), loadNo: text(c.loadNo), freightMode: text(c.freightMode),
    parentId: text(c.parentConsignmentId), parentTrackingRef: text(c.parentTrackingRef),
    partCount: c.partCount ?? null, partsDelivered: c.partsDelivered ?? null, partsCancelled: c.partsCancelled ?? null,
    proofStatus: text(c.proofStatus), proofCondition: text(c.proofCondition),
    handlingUnitNo: text(c.handlingUnitNo), version: text(c.version),
    contactName: text(c.contactName), contactPhone: text(c.contactPhone),
    consigneeReference: text(c.consigneeReference), clientReference: text(c.clientReference),
    deliveryWindowFrom: isoInstant(c.deliveryWindowFrom), deliveryWindowTo: isoInstant(c.deliveryWindowTo),
    remarks: text(c.remarks),
  };
}

/** An instant as the amendment takes it back: ISO-8601 to the second, in UTC; empty when absent. */
function isoInstant(v: unknown): string {
  if (v === null || v === undefined || v === '') {
    return '';
  }
  const at = v instanceof Date ? v : new Date(String(v));
  return Number.isNaN(at.getTime()) ? String(v) : at.toISOString().replace('.000Z', 'Z');
}

/** Every particular, an empty one sent as empty text so the amendment clears it. */
export function particularsRequest(p: ConsignmentParticularsDraft) {
  return {
    contactName: p.contactName.trim(), contactPhone: p.contactPhone.trim(),
    consigneeReference: p.consigneeReference.trim(), clientReference: p.clientReference.trim(),
    deliveryWindowFrom: p.deliveryWindowFrom.trim(), deliveryWindowTo: p.deliveryWindowTo.trim(),
    remarks: p.remarks.trim(),
  };
}

/** The contract's request: a condition left out is sent as absent, not as an empty string. */
export function ruleRequest(draft: RuleDraft): AllocationRuleRequest {
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
export function terminalFeature(t: Terminal, kind: 'site' | 'stop' | 'plant' = 'site'): MapFeature | null {
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
export function anchorOf(t: Terminal | undefined): GeoPoint | null {
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
export function parkedRow(e: ParkedEvent): Row {
  return {
    id: text(e.id), parkedNo: text(e.parkedNo), eventType: text(e.eventType), listenerId: text(e.listenerId),
    status: text(e.status), attempts: text(e.attempts), lastFailedAt: text(e.lastFailedAt),
    lastError: text(e.lastError), parkedAt: text(e.parkedAt), resolvedAt: text(e.resolvedAt),
    resolutionNote: text(e.resolutionNote),
  };
}

export function webhookOf(w: WebhookSubscription): WebhookView {
  return {
    id: text(w.id), name: text(w.name), url: text(w.url), eventTypes: w.eventTypes ?? [],
    active: w.active ?? false, pausedReason: text(w.pausedReason), consecutiveFailures: w.consecutiveFailures ?? 0,
  };
}

/** A delivery as a list row. */
export function deliveryRow(d: WebhookDelivery): Row {
  return {
    id: text(d.id), eventType: text(d.eventType), status: text(d.status), attempts: text(d.attempts),
    lastStatusCode: text(d.lastStatusCode), lastError: text(d.lastError), nextAttemptAt: text(d.nextAttemptAt),
    deliveredAt: text(d.deliveredAt), createdAt: text(d.createdAt),
  };
}

/** A draft's optional value, left out of the request when blank. */
export function blankAsAbsent(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

export function numberOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

export function instantOrAbsent(value: string): Date | undefined {
  const trimmed = value.trim();
  return trimmed ? new Date(trimmed) : undefined;
}

/** An instant as a draft holds it, to the second, or blank. */
function isoOf(d: Date | null | undefined): string {
  return d ? d.toISOString().replace(/\.\d{3}Z$/, 'Z') : '';
}

export function headerRequest(h: OrderHeaderDraft): UpdateOrderRequest {
  return {
    customerPartnerId: h.customerPartnerId.trim(), originTerminalId: h.originTerminalId.trim(),
    requestedPickupAt: instantOrAbsent(h.requestedPickupAt),
    requestedDeliveryAt: instantOrAbsent(h.requestedDeliveryAt),
    originHubTerminalId: blankAsAbsent(h.originHubTerminalId),
    destinationHubTerminalId: blankAsAbsent(h.destinationHubTerminalId),
    contractLaneId: blankAsAbsent(h.contractLaneId), loadType: blankAsAbsent(h.loadType),
  };
}

export function lineRequest(l: OrderLineDraft): AddOrderLineRequest {
  return {
    lineNo: l.lineNo, materialCode: l.materialCode.trim(), materialDescription: l.materialDescription.trim(),
    materialClass: l.materialClass, hazmatUnCode: blankAsAbsent(l.hazmatUnCode), quantity: Number(l.quantity),
    uom: l.uom.trim(), deadWeightKg: Number(l.deadWeightKg), lengthM: numberOrAbsent(l.lengthM),
    widthM: numberOrAbsent(l.widthM), heightM: numberOrAbsent(l.heightM),
    volumetricDivisor: numberOrAbsent(l.volumetricDivisor), consigneePartnerId: blankAsAbsent(l.consigneePartnerId),
    destinationTerminalId: l.destinationTerminalId.trim(),
  };
}

/** Who took a step, as far as the reader may know: a portal reader is never told staff names. */
export function actorOf(e: HistoryEntry): string {
  if (e.actorName) {
    return text(e.actorName);
  }
  if (e.actorKind === 'YOU') {
    return 'you';
  }
  return e.actorKind === 'USER' ? text(e.actorId) || 'another user' : '';
}

/** A history entry as the desk lists it: the status move and any reason pulled out of the snapshots. */
export function historyRowOf(e: HistoryEntry): HistoryEntryRow {
  const before = (e.before ?? {}) as Record<string, unknown>;
  const after = (e.after ?? {}) as Record<string, unknown>;
  const from = text(before['status']);
  const to = text(after['status']);
  return {
    id: text(e.id), action: text(e.action), actor: actorOf(e),
    occurredAt: text(e.occurredAt),
    change: to && from !== to ? (from ? `${from} → ${to}` : to) : '',
    reason: text(after['reason']),
  };
}

/** An order and its lines as the desk shows them, each line ready to be restated by an edit. */
export function orderDeskOf(o: Order, lines: OrderLine[]): OrderDesk {
  return {
    order: {
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status), customerName: text(o.customerName),
      originTerminalCode: text(o.originTerminalCode), freightMode: text(o.freightMode),
      lineCount: text(o.lineCount), plannedLineCount: text(o.plannedLineCount),
      totalDeadWeightKg: text(o.totalDeadWeightKg), containsHazmat: o.containsHazmat ? 'Yes' : '',
      requestedPickupAt: text(o.requestedPickupAt), requestedDeliveryAt: text(o.requestedDeliveryAt),
      closeKind: text(o.closeKind), closeReason: text(o.closeReason),
      contractCode: text(o.contractCode), contractLaneCode: text(o.contractLaneCode), loadType: text(o.loadType),
      transitDays: text(o.transitDays), expectedDeliveryDate: text(o.expectedDeliveryDate),
    },
    version: text(o.version),
    header: {
      customerPartnerId: text(o.customerPartnerId), originTerminalId: text(o.originTerminalId),
      requestedPickupAt: isoOf(o.requestedPickupAt), requestedDeliveryAt: isoOf(o.requestedDeliveryAt),
      originHubTerminalId: text(o.originHubTerminalId), destinationHubTerminalId: text(o.destinationHubTerminalId),
      contractLaneId: text(o.contractLaneId), loadType: text(o.loadType),
    },
    lines: lines.map((l) => ({
      id: text(l.id), lineNo: l.lineNo ?? 0, materialCode: text(l.materialCode),
      materialDescription: text(l.materialDescription), materialClass: text(l.materialClass),
      hazmatUnCode: text(l.hazmatUnCode), quantity: text(l.quantity), uom: text(l.uom),
      deadWeightKg: text(l.deadWeightKg), lengthM: text(l.lengthM), widthM: text(l.widthM),
      heightM: text(l.heightM), volumetricDivisor: text(l.volumetricDivisor),
      consigneePartnerId: text(l.consigneePartnerId), destinationTerminalId: text(l.destinationTerminalId),
      consigneeName: text(l.consigneeName), destinationTerminalCode: text(l.destinationTerminalCode),
      planned: l.planned ?? false, closeKind: text(l.closeKind),
      cancelled: l.cancelled ?? false,
    })),
  };
}

/** An approval request as an inbox row. */
export function approvalRow(a: ApiApproval): Row {
  return {
    id: text(a.id), subjectType: text(a.subjectType), subjectId: text(a.subjectId), action: text(a.action),
    summary: text(a.summary), status: text(a.status), requestedAt: text(a.requestedAt), dueAt: text(a.dueAt),
    approvals: `${a.approvalsGiven}/${a.approvalsRequired}`, waitingFor: (a.pendingPermissions ?? []).join(', '),
    decidedAt: text(a.decidedAt), reason: text(a.reason), mine: a.mineToDecide ? 'yes' : '',
  };
}

/** A payload or policy as readable `key: value` lines. */
export function keyValues(value: object | null | undefined): string {
  return Object.entries(value ?? {})
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : String(v)}`).join('\n');
}

/** Runs a call and turns whatever it throws into an ApiProblem. */
export async function guard<T>(call: () => Promise<T>): Promise<T> {
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
