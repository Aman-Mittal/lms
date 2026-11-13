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
  type RailTariffDraft, type RateCardPage, type TariffCardDetail, type TariffFilter, ApiProblem, Choice, Row,
  MapFeature, Mapped, type DetectorSettingsView, type ExceptionDraft, type ExceptionFilter, type TripActivity,
  type TripWatchDraft, type TripWatchView, type CreditChangeStatus, type CreditLimitChangeDraft,
  type CreditOverrideDraft, type CreditPositionView, type GateEntryDraft, type GateExitDraft, type GatePassDraft,
  type GatePassFilter, type GatePassPage, type GatePassQuery, type GatePassVerdict, type GateRegisterFilter,
  type GateVisitPage, type GateVisitView, type TripStopView, type BillSettlementView, type ChargeCatalogueFilter,
  type ChargeCataloguePage, type ChargeEntryDraft, type ChargeEntryView, type InvoiceBillView,
  type ManualChargeDraft, type ManualChargeFilter, type ManualChargePage, type MoneyOutcome,
  type VendorAdvanceDraft, type VendorAdvancePage, type VendorInvoiceDraft, type VendorInvoiceFilter,
  type VendorInvoicePage, type VendorInvoiceView, type VendorPaymentDraft, type VendorPaymentPage,
} from './lms-gateway';
import {
  ExceptionCategory, CreditOverrideRequestSubjectTypeEnum, GatePurpose as ApiGatePurpose, BillSettlement,
  VendorPayment as ApiVendorPayment,
} from '../api';
import { GeneratedGatewayOrders } from './generated-gateway-orders';
import { RATE_CARD_PAGE, railRateCardRow, rateCardDetailOf, rateCardRow } from './generated-gateway-mappers';
import {
  absentOn404, blankAsAbsent, chargeEntryOf, creditChangeRow, creditPositionOf, decimalOrAbsent, gatePassOf,
  gateVisitOf, instant, instantOf, invoiceBillOf, manualChargeOf, moneyOutcomeOf, noteOf, text, ticketRow,
  tripStopOf, vendorAdvanceOf, vendorInvoiceOf, vendorPaymentOf,
} from './generated-gateway-helpers';

/** The control tower, rate and rail tariffs, credit, the yard gate and trip stops, charges, payables and advances. */
export abstract class GeneratedGatewayMoney extends GeneratedGatewayOrders {
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

  creditPosition(partnerId: string): Promise<CreditPositionView> {
    return this.call(this.credit.getCreditPosition({ id: partnerId }), creditPositionOf);
  }

  grantCreditOverride(partnerId: string, draft: CreditOverrideDraft): Promise<string> {
    return this.call(this.credit.grantCreditOverride({
      id: partnerId,
      creditOverrideRequest: {
        subjectType: draft.subjectType as CreditOverrideRequestSubjectTypeEnum, orderId: draft.orderId || undefined,
        period: draft.period || undefined, upTo: draft.upTo, reason: draft.reason.trim(),
      },
    }), (r) => text(r.id));
  }

  requestCreditLimitChange(partnerId: string, draft: CreditLimitChangeDraft): Promise<string> {
    return this.call(this.credit.requestCreditLimitChange({
      id: partnerId,
      creditLimitChangeRequest: { creditLimit: draft.creditLimit, softLimit: draft.softLimit, reason: draft.reason.trim() },
    }), (r) => text(r.id));
  }

  creditLimitChanges(status?: CreditChangeStatus): Promise<Row[]> {
    return this.call(this.credit.listCreditLimitChanges({ status, limit: 100 }), (s) => s.items.map(creditChangeRow));
  }

  partnerCreditLimitChanges(partnerId: string): Promise<Row[]> {
    return this.call(this.credit.listPartnerCreditLimitChanges({ id: partnerId, limit: 100 }),
      (s) => s.items.map(creditChangeRow));
  }

  approveCreditLimitChange(id: string, note: string): Promise<void> {
    return this.call(this.credit.approveCreditLimitChange({ id, creditLimitDecisionRequest: noteOf(note) ?? {} }),
      () => undefined);
  }

  rejectCreditLimitChange(id: string, note: string): Promise<void> {
    return this.call(this.credit.rejectCreditLimitChange({ id, creditLimitDecisionRequest: noteOf(note) ?? {} }),
      () => undefined);
  }

  // Yard gate
  openGateVisits(): Promise<GateVisitView[]> {
    return this.call(this.gate.listOpenGateVisits({}), (visits) => visits.map(gateVisitOf));
  }

  gateVisits(filter: GateRegisterFilter, cursor?: string): Promise<GateVisitPage> {
    return this.call(this.gate.listGateVisits({
      status: filter.status || undefined, q: filter.q.trim() || undefined, cursor: cursor || undefined, limit: 25,
    }), (s) => ({ items: s.items.map(gateVisitOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null }));
  }

  recordGateEntry(draft: GateEntryDraft): Promise<string> {
    return this.call(this.gate.recordGateEntry({
      gateEntryRequest: {
        terminalId: draft.terminalId || undefined, vehicleId: draft.vehicleId || undefined,
        registrationNo: draft.vehicleId ? undefined : blankAsAbsent(draft.registrationNo),
        driverId: draft.driverId || undefined, driverName: draft.driverId ? undefined : blankAsAbsent(draft.driverName),
        purpose: draft.purpose as unknown as ApiGatePurpose, tripId: draft.tripId || undefined,
        gatePassNo: blankAsAbsent(draft.gatePassNo), sealNos: draft.sealNos.length ? draft.sealNos : undefined,
        weightRef: blankAsAbsent(draft.weightRef), remarks: blankAsAbsent(draft.remarks),
      },
    }), (r) => text(r.id));
  }

  recordGateExit(visitId: string, draft: GateExitDraft): Promise<void> {
    return this.call(this.gate.recordGateExit({
      id: visitId,
      gateExitRequest: {
        sealNos: draft.sealNos.length ? draft.sealNos : undefined, weightRef: blankAsAbsent(draft.weightRef),
        remarks: blankAsAbsent(draft.remarks),
      },
    }), () => undefined);
  }

  gatePasses(filter: GatePassFilter, cursor?: string): Promise<GatePassPage> {
    return this.call(this.gate.listGatePasses({
      status: filter.status || undefined, q: filter.q.trim() || undefined, cursor: cursor || undefined, limit: 25,
    }), (s) => ({ items: s.items.map(gatePassOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null }));
  }

  issueGatePass(draft: GatePassDraft): Promise<string> {
    return this.call(this.gate.issueGatePass({
      issueGatePassRequest: {
        terminalId: draft.terminalId || undefined, vehicleId: draft.vehicleId || undefined,
        registrationNo: draft.vehicleId ? undefined : blankAsAbsent(draft.registrationNo),
        tripId: draft.tripId || undefined, purpose: draft.purpose as unknown as ApiGatePurpose,
        validFrom: instantOf(draft.validFrom), validUntil: instantOf(draft.validUntil),
        passNo: blankAsAbsent(draft.passNo),
      },
    }), (r) => text(r.id));
  }

  async cancelGatePass(id: string, reason: string): Promise<void> {
    // The version is read fresh: a cancellation must not rest on what the list showed earlier.
    const pass = await this.call(this.gate.getGatePass({ id }), (p) => p);
    return this.call(this.gate.cancelGatePass({
      id, ifMatch: `"${pass.version ?? 0}"`, cancelGatePassRequest: { reason: reason.trim() },
    }), () => undefined);
  }

  checkGatePass(query: GatePassQuery): Promise<GatePassVerdict> {
    return this.call(this.gate.checkGatePass({
      terminalId: query.terminalId || undefined, vehicleId: query.vehicleId || undefined,
      registrationNo: query.vehicleId ? undefined : blankAsAbsent(query.registrationNo),
      tripId: query.tripId || undefined, passNo: blankAsAbsent(query.passNo),
    }), (v) => ({
      admits: v.admits, passRequired: v.passRequired, passNo: text(v.passNo), code: text(v.code), message: text(v.message),
    }));
  }

  // Trip stops
  tripStops(tripId: string): Promise<TripStopView[]> {
    return this.call(this.trips.getTripStops({ id: tripId }), (s) => s.stops.map(tripStopOf));
  }

  arriveAtStop(tripId: string, stopId: string): Promise<void> {
    return this.call(this.trips.arriveAtTripStop({ id: tripId, stopId, milestoneRequest: {} }), () => undefined);
  }

  departFromStop(tripId: string, stopId: string): Promise<void> {
    return this.call(this.trips.departFromTripStop({ id: tripId, stopId, milestoneRequest: {} }), () => undefined);
  }

  skipStop(tripId: string, stopId: string, reason: string): Promise<void> {
    return this.call(this.trips.skipTripStop({ id: tripId, stopId, skipTripStopRequest: { reason: reason.trim() } }),
      () => undefined);
  }

  // Charges, payables and advances
  chargeCatalogue(filter: ChargeCatalogueFilter, cursor?: string): Promise<ChargeCataloguePage> {
    return this.call(this.charges.listChargeCatalogue({
      q: filter.q.trim() || undefined, active: filter.activeOnly ? true : undefined, cursor: cursor || undefined, limit: 50,
    }), (s) => ({ items: s.items.map(chargeEntryOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null }));
  }

  chargeEntry(id: string): Promise<ChargeEntryView> {
    return this.call(this.charges.getChargeCatalogueEntry({ id }), chargeEntryOf);
  }

  createChargeEntry(draft: ChargeEntryDraft): Promise<string> {
    return this.call(this.charges.createChargeCatalogueEntry({
      createChargeCatalogueEntryRequest: {
        code: draft.code.trim(), name: draft.name.trim(), basis: draft.basis as never, defaultRate: draft.defaultRate,
        direction: draft.direction as never, active: draft.active,
      },
    }), (r) => text(r.id));
  }

  updateChargeEntry(id: string, version: number, draft: ChargeEntryDraft): Promise<void> {
    return this.call(this.charges.updateChargeCatalogueEntry({
      id, ifMatch: `"${version}"`,
      updateChargeCatalogueEntryRequest: {
        name: draft.name.trim(), basis: draft.basis as never, defaultRate: draft.defaultRate,
        direction: draft.direction as never, active: draft.active,
      },
    }), () => undefined);
  }

  manualCharges(filter: ManualChargeFilter, cursor?: string): Promise<ManualChargePage> {
    return this.call(this.charges.listManualCharges({
      status: filter.status || undefined, tripId: filter.tripId || undefined, cursor: cursor || undefined, limit: 25,
    }), (s) => ({ items: s.items.map(manualChargeOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null }));
  }

  recordManualCharge(draft: ManualChargeDraft): Promise<MoneyOutcome> {
    return this.call(this.charges.recordManualCharge({
      recordManualChargeRequest: {
        catalogueId: draft.catalogueId, subjectType: draft.subjectType as never, subjectId: draft.subjectId.trim(),
        direction: (draft.direction || undefined) as never, customerId: blankAsAbsent(draft.customerId),
        quantity: draft.quantity, rate: draft.rate ?? undefined, reason: draft.reason.trim(),
        evidenceRef: blankAsAbsent(draft.evidenceRef),
      },
    }), moneyOutcomeOf);
  }

  async cancelManualCharge(id: string, reason: string): Promise<void> {
    const charge = await this.call(this.charges.getManualCharge({ id }), (c) => c);
    return this.call(this.charges.cancelManualCharge({
      id, ifMatch: `"${charge.version ?? 0}"`, cancelMoneyRequest: { reason: reason.trim() },
    }), () => undefined);
  }

  vendorInvoices(filter: VendorInvoiceFilter, cursor?: string): Promise<VendorInvoicePage> {
    return this.call(this.payables.listVendorInvoices({
      status: filter.status || undefined, overdue: filter.overdue ? true : undefined, cursor: cursor || undefined, limit: 25,
    }), (s) => ({ items: s.items.map(vendorInvoiceOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null }));
  }

  vendorInvoice(id: string): Promise<VendorInvoiceView> {
    return this.call(this.payables.getVendorInvoice({ id }), vendorInvoiceOf);
  }

  vendorInvoiceBills(id: string): Promise<InvoiceBillView[]> {
    return this.call(this.payables.listVendorInvoiceBills({ id }), (bills) => bills.map(invoiceBillOf));
  }

  async captureVendorInvoice(draft: VendorInvoiceDraft): Promise<string> {
    const ids: string[] = [];
    for (const number of draft.billNumbers) {
      const found = await this.call(this.freightBills.listFreightBills({ q: number, status: 'APPROVED_FOR_PAYMENT', limit: 50 }),
        (s) => s.items.find((b) => b.billNo === number));
      if (!found) {
        throw new ApiProblem(`No freight bill numbered ${number} is approved for payment.`, 404, {}, 'not-found');
      }
      ids.push(text(found.id));
    }
    return this.call(this.payables.captureVendorInvoice({
      captureVendorInvoiceRequest: {
        vendorInvoiceNo: draft.vendorInvoiceNo.trim(), invoiceDate: draft.invoiceDate, amount: draft.amount,
        taxAmount: draft.taxAmount, freightBillIds: ids, documentRef: blankAsAbsent(draft.documentRef),
      },
    }), (r) => text(r.id));
  }

  vendorPayments(invoiceId?: string, cursor?: string): Promise<VendorPaymentPage> {
    const page = (s: { items: ApiVendorPayment[]; hasMore: boolean; nextCursor?: string }): VendorPaymentPage =>
      ({ items: s.items.map(vendorPaymentOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null });
    return invoiceId
      ? this.call(this.payables.listVendorInvoicePayments({ id: invoiceId, cursor: cursor || undefined, limit: 50 }), page)
      : this.call(this.payables.listVendorPayments({ cursor: cursor || undefined, limit: 25 }), page);
  }

  recordVendorPayment(invoiceId: string, draft: VendorPaymentDraft): Promise<string> {
    return this.call(this.payables.recordVendorPayment({
      id: invoiceId,
      recordVendorPaymentRequest: {
        amount: draft.amount, paidOn: draft.paidOn, mode: draft.mode as never, reference: draft.reference.trim(),
      },
    }), (r) => text(r.id));
  }

  billSettlement(billId: string): Promise<BillSettlementView> {
    return this.call(this.payables.getBillSettlement({ id: billId }), (b: BillSettlement) => ({
      billNo: text(b.billNo), status: text(b.status), freightAmount: Number(b.freightAmount ?? 0),
      chargesAmount: Number(b.chargesAmount ?? 0), approvedAmount: Number(b.approvedAmount ?? 0),
      advancesPaid: Number(b.advancesPaid ?? 0), netPayable: Number(b.netPayable ?? 0), invoiceNo: text(b.invoiceNo),
      invoiceStatus: text(b.invoiceStatus),
    }));
  }

  vendorAdvances(tripId?: string, cursor?: string): Promise<VendorAdvancePage> {
    return this.call(this.advances.listVendorAdvances({ tripId: tripId || undefined, cursor: cursor || undefined, limit: 50 }),
      (s) => ({ items: s.items.map(vendorAdvanceOf), nextCursor: s.hasMore ? s.nextCursor ?? null : null }));
  }

  requestVendorAdvance(draft: VendorAdvanceDraft): Promise<MoneyOutcome> {
    return this.call(this.advances.requestVendorAdvance({
      requestVendorAdvanceRequest: {
        tripId: draft.tripId, mode: draft.mode as never, amount: draft.amount, reason: draft.reason.trim(),
      },
    }), moneyOutcomeOf);
  }

  async payVendorAdvance(id: string, reference: string, paidOn: string): Promise<void> {
    const advance = await this.call(this.advances.getVendorAdvance({ id }), (a) => a);
    return this.call(this.advances.payVendorAdvance({
      id, ifMatch: `"${advance.version ?? 0}"`, payVendorAdvanceRequest: { reference: reference.trim(), paidOn },
    }), () => undefined);
  }

  async cancelVendorAdvance(id: string, reason: string): Promise<void> {
    const advance = await this.call(this.advances.getVendorAdvance({ id }), (a) => a);
    return this.call(this.advances.cancelVendorAdvance({
      id, ifMatch: `"${advance.version ?? 0}"`, cancelMoneyRequest: { reason: reason.trim() },
    }), () => undefined);
  }
}
