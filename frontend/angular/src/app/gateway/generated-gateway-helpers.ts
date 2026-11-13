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
  Approval as ApiApproval, ClientCharge, ClientContract, ClientContractLane, ClientContractLaneRequest,
  ClientInvoice, ClientInvoiceLine, ClientStatementEntry, AllocationRule, AllocationRuleAction,
  AllocationRuleRequest, AllocationRuleRequestMaterialClassEnum, CustomFieldDefinition, Consignment, Load,
  Terminal, ParkedEvent, WebhookDelivery, WebhookSubscription, AddOrderLineRequest, Order, OrderLine,
  UpdateOrderRequest, HistoryEntry, EffectiveSetting, FreightMode as ApiFreightMode,
  HistoryEntry as SettingHistoryEntry, OrgUnit, SettingCombinationConditions, SettingDefinition, SettingProfile,
  SettingSource, Exception as ApiException, CreditLimitChange, CreditPosition, NumberRelease, NumberSeries,
  GatePass as ApiGatePass, GateVisit as ApiGateVisit, type TripStop as ApiTripStop, ChargeCatalogueEntry,
  ManualCharge as ApiManualCharge, VendorAdvance as ApiVendorAdvance, VendorInvoice as ApiVendorInvoice,
  VendorInvoiceBill, VendorPayment as ApiVendorPayment,
} from '../api';
import {
  type ContractDetail, type ContractDraft, type ContractLaneChoice, type ContractLaneDraft, AllocationRuleView,
  ApiProblem, ConsignmentCard, ConsignmentParticularsDraft, CustomFieldKind, PodStatus, FieldDefinition, FieldType,
  Row, RuleAction, RuleDraft, GeoPoint, MapFeature, WebhookView, OrderDesk, OrderHeaderDraft, OrderLineDraft,
  HistoryEntryRow, type EffectiveSettingView, type OrgUnitView, type SettingChange, type SettingConditions,
  type SettingDefinitionView, type SettingHistoryRow, type SettingProfileView, type SettingValue,
  type CreditLimitState, type CreditPositionView, type NumberedDocumentType, type NumberSeriesView,
  type NumberYearCode, type GatePassView, type GateVisitView, type TripStopView, type ChargeEntryView,
  type InvoiceBillView, type ManualChargeView, type MoneyOutcome, type VendorAdvanceView, type VendorInvoiceView,
  type VendorPaymentView,
} from './lms-gateway';
import { commercialNumber } from './generated-gateway-mappers';

export function chargeEntryOf(e: ChargeCatalogueEntry): ChargeEntryView {
  return {
    id: text(e.id), code: text(e.code), name: text(e.name), basis: text(e.basis), defaultRate: Number(e.defaultRate ?? 0),
    direction: text(e.direction), active: e.active !== false, version: Number(e.version ?? 0),
  };
}

export function manualChargeOf(c: ApiManualCharge): ManualChargeView {
  return {
    id: text(c.id), chargeNo: text(c.chargeNo), chargeCode: text(c.chargeCode), chargeName: text(c.chargeName),
    direction: text(c.direction), subjectType: text(c.subjectType), subjectRef: text(c.subjectRef), tripId: text(c.tripId),
    quantity: Number(c.quantity ?? 0), rate: Number(c.rate ?? 0), amount: Number(c.amount ?? 0), currency: text(c.currency),
    reason: text(c.reason), status: text(c.status), billed: c.billed === true, clientInvoiceId: text(c.clientInvoiceId),
    freightBillNo: text(c.freightBillNo), cancelReason: text(c.cancelReason),
  };
}

export function vendorInvoiceOf(i: ApiVendorInvoice): VendorInvoiceView {
  return {
    id: text(i.id), invoiceNo: text(i.invoiceNo), vendorName: text(i.vendorName), vendorInvoiceNo: text(i.vendorInvoiceNo),
    invoiceDate: text(i.invoiceDate), dueDate: text(i.dueDate), amount: Number(i.amount ?? 0), taxAmount: Number(i.taxAmount ?? 0),
    totalAmount: Number(i.totalAmount ?? 0), currency: text(i.currency), approvedAmount: Number(i.approvedAmount ?? 0),
    advancesDeducted: Number(i.advancesDeducted ?? 0), netPayable: Number(i.netPayable ?? 0),
    paidAmount: Number(i.paidAmount ?? 0), outstanding: Number(i.outstanding ?? 0), status: text(i.status),
    overdue: i.overdue === true, daysOverdue: Number(i.daysOverdue ?? 0),
  };
}

export function invoiceBillOf(b: VendorInvoiceBill): InvoiceBillView {
  return { billId: text(b.billId), billNo: text(b.billNo), tripNo: text(b.tripNo), approvedAmount: Number(b.approvedAmount ?? 0) };
}

export function vendorPaymentOf(p: ApiVendorPayment): VendorPaymentView {
  return {
    id: text(p.id), invoiceNo: text(p.invoiceNo), vendorName: text(p.vendorName), amount: Number(p.amount ?? 0),
    paidOn: text(p.paidOn), mode: text(p.mode), reference: text(p.reference),
  };
}

export function vendorAdvanceOf(a: ApiVendorAdvance): VendorAdvanceView {
  return {
    id: text(a.id), advanceNo: text(a.advanceNo), tripNo: text(a.tripNo), vendorName: text(a.vendorName), mode: text(a.mode),
    amount: Number(a.amount ?? 0), currency: text(a.currency), status: text(a.status), paidReference: text(a.paidReference),
    paidOn: text(a.paidOn), reason: text(a.reason),
  };
}

/** The answer to raising a charge or an advance: a 202 carries the approval request beside the id. */
export function moneyOutcomeOf(r: unknown): MoneyOutcome {
  const body = r as { id?: string; approval?: { id?: string } };
  return { id: text(body.id), pending: !!body.approval, approvalId: text(body.approval?.id) };
}

/** Minutes inside as people say them: `45 min`, `2 h 5 min`. */
function dwellOf(minutes: number | undefined): string {
  if (minutes === undefined || minutes === null) {
    return '';
  }
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}

export function gateVisitOf(v: ApiGateVisit): GateVisitView {
  return {
    id: text(v.id), terminalCode: text(v.terminalCode), registrationNo: text(v.registrationNo),
    driverName: text(v.driverName), purpose: text(v.purpose), tripNo: text(v.tripNo), gatePassNo: text(v.gatePassNo),
    status: text(v.status), enteredAt: instant(v.enteredAt), exitedAt: instant(v.exitedAt), dwell: dwellOf(v.dwellMinutes),
    entrySeals: (v.entrySealNos ?? []).join(', '), exitSeals: (v.exitSealNos ?? []).join(', '),
    weightRef: text(v.exitWeightRef || v.entryWeightRef), remarks: text(v.exitRemarks || v.entryRemarks),
  };
}

export function gatePassOf(p: ApiGatePass): GatePassView {
  return {
    id: text(p.id), passNo: text(p.passNo), terminalCode: text(p.terminalCode), registrationNo: text(p.registrationNo),
    tripNo: text(p.tripNo), purpose: text(p.purpose), validFrom: instant(p.validFrom), validUntil: instant(p.validUntil),
    status: text(p.status), expired: p.expired === true, cancelReason: text(p.cancelReason),
  };
}

export function tripStopOf(s: ApiTripStop): TripStopView {
  return {
    id: text(s.id), seq: s.seq ?? 0, kind: text(s.kind), terminalCode: text(s.terminalCode),
    terminalName: text(s.terminalName), plannedArrivalAt: instant(s.plannedArrivalAt), status: text(s.status),
    arrivedAt: instant(s.arrivedAt), departedAt: instant(s.departedAt), skipReason: text(s.skipReason),
    outOfOrder: s.outOfOrder === true, lateMinutes: s.lateMinutes ?? 0,
  };
}

/** A `datetime-local` value (the browser's zone) as an instant. */
export function instantOf(local: string): string {
  return new Date(local).toISOString();
}

/** A quantity typed into a field, or absent when blank (the server reads it as zero). */
export function quantityOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : Number(trimmed);
}

/** An instant as the React console shows one: `YYYY-MM-DD HH:mm` in UTC. */
export function instant(v: string | null | undefined): string {
  return v ? text(new Date(v)) : '';
}

/** A customer's credit position, every amount as text and a missing one blank. */
export function creditPositionOf(p: CreditPosition): CreditPositionView {
  return {
    partnerId: text(p.partnerId), partnerCode: text(p.partnerCode), partnerStatus: text(p.partnerStatus),
    trading: p.trading, currency: text(p.currency), creditLimit: text(p.creditLimit), softLimit: text(p.softLimit),
    openOrders: text(p.exposure.openOrders), openOrderCount: text(p.exposure.openOrderCount),
    unpricedOpenOrderCount: text(p.exposure.unpricedOpenOrderCount),
    unbilledCharges: text(p.exposure.unbilledCharges), unbilledChargeCount: text(p.exposure.unbilledChargeCount),
    outstandingInvoices: text(p.exposure.outstandingInvoices), exposure: text(p.exposure.total),
    headroom: text(p.headroom), limitState: text(p.limitState) as CreditLimitState,
    pendingChangeId: text(p.pendingChangeId), asOf: instant(p.asOf),
  };
}

/** A number series as the administration desk lists it. */
export function numberSeriesOf(n: NumberSeries): NumberSeriesView {
  return {
    id: text(n.id), code: text(n.code), documentType: text(n.documentType) as NumberedDocumentType,
    orgUnitId: text(n.orgUnitId), scope: n.orgUnitId ? text(n.orgUnitName) : 'Whole tenant', prefix: text(n.prefix),
    yearCode: text(n.yearCode) as NumberYearCode, separator: text(n.separator), counterLength: text(n.counterLength),
    yearStartMonth: text(n.yearStartMonth), timeZone: text(n.timeZone), reuseReleased: n.reuseReleased,
    active: n.active, description: text(n.description), financialYear: text(n.financialYear),
    issuedThisYear: text(n.issuedThisYear), lastIssued: text(n.lastIssued),
    releasedAvailable: text(n.releasedAvailable), sample: text(n.sample),
  };
}

/** A number given back to its series. */
export function numberReleaseRow(r: NumberRelease): Row {
  return {
    id: text(r.id), number: text(r.number), financialYear: text(r.financialYear), reason: text(r.reason),
    releasedBy: text(r.releasedBy), releasedAt: instant(r.releasedAt),
    reusedAt: r.reusedAt ? instant(r.reusedAt) : 'Not yet',
  };
}

/** A credit limit change request as the administration desk lists it. */
export function creditChangeRow(c: CreditLimitChange): Row {
  return {
    id: text(c.id), partnerId: text(c.partnerId), partnerCode: text(c.partnerCode), status: text(c.status),
    previousLimit: text(c.previousLimit), previousSoftLimit: text(c.previousSoftLimit),
    requestedLimit: text(c.requestedLimit), requestedSoftLimit: text(c.requestedSoftLimit), reason: text(c.reason),
    requestedBy: text(c.requestedBy), requestedAt: instant(c.requestedAt), decidedBy: text(c.decidedBy),
    decidedAt: instant(c.decidedAt), decisionNote: text(c.decisionNote),
  };
}

/** A ticket as the exception desk lists and opens it. */
export function ticketRow(e: ApiException): Row {
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
export function decimalOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

/** A blank note is no note. */
export function noteOf(note: string): { note: string } | undefined {
  return note.trim() ? { note: note.trim() } : undefined;
}

/** The result, or null when the API answered 404; any other failure is thrown on. */
export async function absentOn404<T>(call: Promise<T>): Promise<T | null> {
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

/** The terms of a contract draft as the request carries them: a blank is sent as absent. */
export function contractTerms(draft: ContractDraft) {
  return {
    code: blankAsAbsent(draft.code), effectiveFrom: draft.effectiveFrom, validTill: draft.validTill || undefined,
    transportRatePerKg: commercialNumber(draft.transportRatePerKg),
    transportMinimumCharge: commercialNumber(draft.transportMinimumCharge),
    storageRatePerPalletDay: commercialNumber(draft.storageRatePerPalletDay),
    handlingInRatePerPallet: commercialNumber(draft.handlingInRatePerPallet),
    handlingOutRatePerPallet: commercialNumber(draft.handlingOutRatePerPallet),
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
      rateBasis: basis as ClientContractLaneRequest['rateBasis'], rate: Number(rate),
    };
  });
}

/** A lane as the request carries it: a blank end, mode, load type or list is sent as absent. */
export function laneRequest(l: ContractLaneDraft): ClientContractLaneRequest {
  return {
    laneCode: l.laneCode.trim(), originTerminalId: blankAsAbsent(l.originTerminalId),
    originZone: blankAsAbsent(l.originZone), destinationTerminalId: blankAsAbsent(l.destinationTerminalId),
    destinationZone: blankAsAbsent(l.destinationZone),
    freightMode: blankAsAbsent(l.freightMode) as ClientContractLaneRequest['freightMode'],
    loadType: blankAsAbsent(l.loadType), rateBasis: l.rateBasis as ClientContractLaneRequest['rateBasis'],
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

export function definitionOf(d: CustomFieldDefinition): FieldDefinition {
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

export function ruleOf(r: AllocationRule): AllocationRuleView {
  return {
    id: text(r.id), name: text(r.name), priority: r.priority ?? 0, active: r.active ?? true,
    originTerminalId: text(r.originTerminalId), destinationTerminalId: text(r.destinationTerminalId),
    minWeightKg: text(r.minWeightKg), maxWeightKg: text(r.maxWeightKg),
    materialClass: text(r.materialClass), action: (text(r.action) || 'PREFER') as RuleAction,
    vendors: (r.vendors ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
  };
}

/** The contract's request: a condition left out is sent as absent, not as an empty string. */
export function ruleRequest(draft: RuleDraft): AllocationRuleRequest {
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
export function blankAsAbsent(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed ? trimmed : undefined;
}

export function numberOrAbsent(value: string): number | undefined {
  const trimmed = value.trim();
  return trimmed ? Number(trimmed) : undefined;
}

export function headerRequest(h: OrderHeaderDraft): UpdateOrderRequest {
  return {
    customerPartnerId: h.customerPartnerId.trim(), originTerminalId: h.originTerminalId.trim(),
    requestedPickupAt: blankAsAbsent(h.requestedPickupAt), requestedDeliveryAt: blankAsAbsent(h.requestedDeliveryAt),
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
function actorOf(e: HistoryEntry): string {
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
    occurredAt: instant(e.occurredAt),
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
      requestedPickupAt: text(o.requestedPickupAt), requestedDeliveryAt: text(o.requestedDeliveryAt),
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

/** The adapter: the only file in the Angular app (besides app config) that knows the generated client. */

// Profile and settings

const SCOPE_WORDS: Record<string, string> = {
  PLATFORM: 'Platform', ORG_DEFAULT: 'Organisation default', ORG_UNIT: 'Org unit', CUSTOMER: 'Customer',
  VENDOR: 'Vendor', VEHICLE: 'Vehicle', CONTRACT: 'Contract', USER: 'You',
};

/** Where a value came from, in words: the API's label (a unit with its level, a combination's conditions). */
function sourceText(src: SettingSource): string {
  if (src.kind === 'SYSTEM_DEFAULT') {
    return 'System default';
  }
  const scope = SCOPE_WORDS[String(src.scopeType)] ?? String(src.scopeType);
  const from = src.effectiveFrom ? ` (from ${String(src.effectiveFrom).slice(0, 10)})` : '';
  return `${src.label || `${scope}: ${text(src.profileName)}`}${from}`;
}

function conditionsOf(c: SettingCombinationConditions | undefined): SettingConditions | null {
  return c ? {
    orgUnitId: text(c.orgUnitId), customerId: text(c.customerId), vendorId: text(c.vendorId),
    vehicleId: text(c.vehicleId), freightMode: text(c.freightMode), priority: c.priority ?? 0,
  } : null;
}

export function conditionsRequest(c: SettingConditions): SettingCombinationConditions {
  return {
    orgUnitId: c.orgUnitId || undefined, customerId: c.customerId || undefined, vendorId: c.vendorId || undefined,
    vehicleId: c.vehicleId || undefined, freightMode: (c.freightMode || undefined) as ApiFreightMode | undefined,
    priority: c.priority,
  };
}
export function orgUnitViewOf(u: OrgUnit): OrgUnitView {
  return {
    id: text(u.id), parentId: text(u.parentId), slug: text(u.slug), path: text(u.path), name: text(u.name),
    unitType: text(u.unitType), level: u.level, countryCode: text(u.countryCode), stateCode: text(u.stateCode),
    effectiveCountry: text(u.effectiveCountry), effectiveState: text(u.effectiveState), version: text(u.version),
  };
}

export function effectiveOf(e: EffectiveSetting): EffectiveSettingView {
  return {
    key: text(e.key), type: text(e.type), category: text(e.category), label: text(e.label),
    value: (e.value ?? null) as SettingValue | null, source: sourceText(e.source),
    sourceScope: e.source.kind === 'SYSTEM_DEFAULT' ? '' : text(e.source.scopeType),
    effectiveFrom: e.source.effectiveFrom ? String(e.source.effectiveFrom).slice(0, 10) : '',
  };
}

export function definitionViewOf(d: SettingDefinition): SettingDefinitionView {
  return {
    key: d.key, type: text(d.type), category: text(d.category), label: d.label, description: d.description,
    defaultValue: (d.defaultValue ?? null) as SettingValue | null, scopes: (d.scopes ?? []).map(String),
    allowedValues: d.allowedValues ?? [], min: d.min ?? null, max: d.max ?? null,
    userPreference: d.userPreference, effectiveDated: d.effectiveDated, tenantOverridable: d.tenantOverridable,
  };
}

export function profileViewOf(p: SettingProfile): SettingProfileView {
  return {
    id: text(p.id), scopeType: text(p.scopeType), scopeId: text(p.scopeId), scopeLabel: text(p.scopeLabel),
    name: text(p.name), version: text(p.version), values: (p.values ?? {}) as Record<string, SettingValue>,
    versions: (p.versions ?? []).map((v) => ({
      key: text(v.key), effectiveFrom: v.effectiveFrom ? String(v.effectiveFrom).slice(0, 10) : '',
      value: (v.value ?? null) as SettingValue | null, status: text(v.status),
    })),
    conditions: conditionsOf(p.conditions),
    updatedAt: instant(p.updatedAt),
  };
}

function shown(v: unknown): string {
  return v === null || v === undefined ? '' : typeof v === 'object' ? JSON.stringify(v) : String(v);
}

/** One row per setting version a history entry changed. */
export function settingHistoryRows(e: SettingHistoryEntry): SettingHistoryRow[] {
  const before = (e.before ?? {}) as Record<string, unknown>;
  const after = (e.after ?? {}) as Record<string, unknown>;
  const keys = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter((k) => k !== 'scopeType').sort();
  return keys.map((k) => ({
    id: `${text(e.id)}:${k}`, action: text(e.action), actor: actorOf(e), occurredAt: instant(e.occurredAt),
    setting: k, before: shown(before[k]), after: shown(after[k]),
  }));
}

/** Undated changes go in `values`, dated ones in `versions`. */
export function changesRequest(changes: SettingChange[]) {
  const values: Record<string, unknown> = {};
  const versions: Array<{ key: string; effectiveFrom: string; value: unknown }> = [];
  for (const c of changes) {
    if (c.effectiveFrom) {
      versions.push({ key: c.key, effectiveFrom: c.effectiveFrom, value: c.value });
    } else {
      values[c.key] = c.value;
    }
  }
  return { values, versions };
}

/** A proof's status as the delivery desk knows it. */
export function proofStatus(raw: string): PodStatus {
  const known: string[] = ['PENDING', 'OTP_ISSUED', 'CONFIRMED', 'SUBMITTED', 'NOT_RECEIVED', 'REVERSED'];
  return (known.includes(raw) ? raw : 'OTP_ISSUED') as PodStatus;
}

/** An approval request as an inbox row. */
export function approvalRow(a: ApiApproval): Row {
  return {
    id: text(a.id), subjectType: text(a.subjectType), subjectId: text(a.subjectId), action: text(a.action),
    summary: text(a.summary), status: text(a.status), requestedAt: instant(a.requestedAt), dueAt: instant(a.dueAt),
    approvals: `${a.approvalsGiven}/${a.approvalsRequired}`, waitingFor: (a.pendingPermissions ?? []).join(', '),
    decidedAt: instant(a.decidedAt), reason: text(a.reason), mine: a.mineToDecide ? 'yes' : '',
  };
}

/** A payload or policy as readable `key: value` lines. */
export function keyValues(value: object | null | undefined): string {
  return Object.entries(value ?? {})
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${k}: ${Array.isArray(v) ? v.join(', ') : String(v)}`).join('\n');
}
