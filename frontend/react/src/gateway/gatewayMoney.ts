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
  Configuration, SettlementApi, ChargesApi, VendorAdvancesApi, VendorPayablesApi,
  type ChargeBasis as ApiChargeBasis, type ChargeCatalogueEntry as ApiChargeEntry,
  type ChargeDirection as ApiChargeDirection, type ChargeSide as ApiChargeSide,
  type ManualCharge as ApiManualCharge, type ManualChargeSubjectType as ApiManualChargeSubjectType,
  type VendorAdvance as ApiVendorAdvance, type VendorAdvanceMode as ApiVendorAdvanceMode,
  type VendorInvoice as ApiVendorInvoice, type VendorPayment as ApiVendorPayment,
  type VendorPaymentMode as ApiVendorPaymentMode, NumberSeriesApi,
  type NumberedDocumentType as ApiNumberedDocumentType, type NumberRelease, type NumberSeries as ApiNumberSeries,
  type NumberYearCode as ApiNumberYearCode,
} from '../api';
import {
  ApiProblem, type LmsGateway, type Row, type BillSettlementView, type ChargeCatalogueFilter,
  type ChargeEntryDraft, type ChargeEntryView, type ManualChargeDraft, type ManualChargeFilter,
  type ManualChargeView, type MoneyRequestResult, type VendorAdvanceDraft, type VendorAdvanceView,
  type VendorInvoiceBillView, type VendorInvoiceDraft, type VendorInvoiceFilter, type VendorInvoiceView,
  type VendorPaymentDraft, type VendorPaymentView, type NumberedDocumentType, type NumberFormatDraft,
  type NumberSeriesDraft, type NumberSeriesView, type NumberYearCode,
} from './lmsGateway';
import { blankAsAbsent, guard, text } from './gatewayShared';

// Charges, payables and advances

type MoneyVerbs = Pick<LmsGateway, 'listChargeCatalogue' | 'getChargeCatalogueEntry' | 'createChargeCatalogueEntry'
  | 'updateChargeCatalogueEntry' | 'listManualCharges' | 'recordManualCharge' | 'cancelManualCharge'
  | 'listVendorInvoices' | 'vendorInvoiceBills' | 'vendorInvoicePayments' | 'captureVendorInvoice'
  | 'listVendorPayments' | 'recordVendorPayment' | 'billSettlement' | 'listVendorAdvances' | 'requestVendorAdvance'
  | 'payVendorAdvance' | 'cancelVendorAdvance'>;

/** A date as the API's `yyyy-mm-dd`, which the client reads as UTC midnight. */
function dayOf(d: Date | undefined): string {
  return d ? d.toISOString().substring(0, 10) : '';
}

function chargeEntryOf(e: ApiChargeEntry): ChargeEntryView {
  return {
    id: text(e.id), code: text(e.code), name: text(e.name), basis: e.basis as unknown as ChargeEntryView['basis'],
    defaultRate: e.defaultRate, currency: text(e.currency),
    direction: e.direction as unknown as ChargeEntryView['direction'], active: e.active, version: String(e.version),
  };
}

function manualChargeOf(m: ApiManualCharge): ManualChargeView {
  return {
    id: text(m.id), chargeNo: text(m.chargeNo), chargeCode: text(m.chargeCode), chargeName: text(m.chargeName),
    direction: text(m.direction), subjectType: text(m.subjectType), subjectRef: text(m.subjectRef),
    tripId: text(m.tripId), quantity: m.quantity, rate: m.rate, amount: m.amount, currency: text(m.currency),
    reason: text(m.reason), status: text(m.status), billed: m.billed, clientChargeId: text(m.clientChargeId),
    freightBillNo: text(m.freightBillNo),
  };
}

function vendorInvoiceOf(i: ApiVendorInvoice): VendorInvoiceView {
  return {
    id: text(i.id), invoiceNo: text(i.invoiceNo), vendorName: text(i.vendorName),
    vendorInvoiceNo: text(i.vendorInvoiceNo), invoiceDate: dayOf(i.invoiceDate), dueDate: dayOf(i.dueDate),
    totalAmount: i.totalAmount, approvedAmount: i.approvedAmount, advancesDeducted: i.advancesDeducted,
    netPayable: i.netPayable, paidAmount: i.paidAmount, outstanding: i.outstanding, status: text(i.status),
    overdue: i.overdue, daysOverdue: i.daysOverdue, billCount: i.billCount, currency: text(i.currency),
  };
}

function vendorPaymentOf(p: ApiVendorPayment): VendorPaymentView {
  return {
    id: text(p.id), invoiceNo: text(p.invoiceNo), vendorInvoiceNo: text(p.vendorInvoiceNo),
    vendorName: text(p.vendorName), amount: p.amount, currency: text(p.currency), paidOn: dayOf(p.paidOn),
    mode: text(p.mode), reference: text(p.reference),
  };
}

function vendorAdvanceOf(a: ApiVendorAdvance): VendorAdvanceView {
  return {
    id: text(a.id), advanceNo: text(a.advanceNo), tripNo: text(a.tripNo), vendorName: text(a.vendorName),
    mode: text(a.mode), amount: a.amount, currency: text(a.currency), reason: text(a.reason), status: text(a.status),
    paidReference: text(a.paidReference), paidOn: dayOf(a.paidOn), freightReference: a.freightReference,
  };
}

/** Charges, payables and advances, over the same configuration as the rest of the gateway. */
export function moneyVerbs(config: Configuration, freightBills: SettlementApi): MoneyVerbs {
  const charges = new ChargesApi(config);
  const payables = new VendorPayablesApi(config);
  const advances = new VendorAdvancesApi(config);

  /** Resolves freight bill numbers to ids through the approved bills, refusing a number that is not one. */
  async function billIds(numbers: string[]): Promise<string[]> {
    const ids: string[] = [];
    for (const no of numbers) {
      const page = await freightBills.listFreightBills({ status: 'APPROVED_FOR_PAYMENT', q: no, limit: 50 });
      const hit = page.items.find((b) => b.billNo === no);
      if (!hit) {
        throw new ApiProblem(`No freight bill ${no} is approved for payment`, 404, {}, 'not-found');
      }
      ids.push(text(hit.id));
    }
    return ids;
  }

  return {
    listChargeCatalogue: (filter: ChargeCatalogueFilter) =>
      guard(async () => (await charges.listChargeCatalogue({
        active: filter.active ?? undefined, q: filter.q.trim() || undefined, limit: 200,
      })).items.map(chargeEntryOf)),
    getChargeCatalogueEntry: (id: string) => guard(async () => chargeEntryOf(await charges.getChargeCatalogueEntry({ id }))),
    createChargeCatalogueEntry: (d: ChargeEntryDraft) =>
      guard(async () => text((await charges.createChargeCatalogueEntry({
        createChargeCatalogueEntryRequest: {
          code: d.code.trim(), name: d.name.trim(), basis: d.basis as unknown as ApiChargeBasis,
          defaultRate: Number(d.defaultRate), direction: d.direction as unknown as ApiChargeDirection,
          active: d.active,
        },
      })).id)),
    updateChargeCatalogueEntry: (id: string, version: string, d: ChargeEntryDraft) =>
      guard(async () => {
        await charges.updateChargeCatalogueEntry({
          id, ifMatch: `"${version}"`,
          updateChargeCatalogueEntryRequest: {
            name: d.name.trim(), basis: d.basis as unknown as ApiChargeBasis, defaultRate: Number(d.defaultRate),
            direction: d.direction as unknown as ApiChargeDirection, active: d.active,
          },
        });
      }),
    listManualCharges: (filter: ManualChargeFilter) =>
      guard(async () => (await charges.listManualCharges({
        status: filter.status || undefined, tripId: filter.tripId || undefined, limit: 100,
      })).items.map(manualChargeOf)),
    recordManualCharge: (d: ManualChargeDraft) =>
      guard(async () => {
        const response = await charges.recordManualChargeRaw({
          recordManualChargeRequest: {
            catalogueId: d.catalogueId, subjectType: d.subjectType as unknown as ApiManualChargeSubjectType,
            subjectId: d.subjectId.trim(), direction: d.direction ? d.direction as unknown as ApiChargeSide : undefined,
            customerId: blankAsAbsent(d.customerId), quantity: Number(d.quantity),
            rate: d.rate.trim() ? Number(d.rate) : undefined, reason: d.reason.trim(),
            evidenceRef: blankAsAbsent(d.evidenceRef),
          },
        });
        return { id: text((await response.value()).id), pending: response.raw.status === 202 };
      }),
    cancelManualCharge: (id: string, reason: string) =>
      guard(async () => {
        const charge = await charges.getManualCharge({ id });
        await charges.cancelManualCharge({ id, ifMatch: `"${charge.version}"`, cancelMoneyRequest: { reason: reason.trim() } });
      }),
    listVendorInvoices: (filter: VendorInvoiceFilter) =>
      guard(async () => (await payables.listVendorInvoices({
        status: filter.status || undefined, overdue: filter.overdue ? true : undefined, limit: 100,
      })).items.map(vendorInvoiceOf)),
    vendorInvoiceBills: (id: string) =>
      guard(async (): Promise<VendorInvoiceBillView[]> => (await payables.listVendorInvoiceBills({ id })).map((b) => ({
        billId: text(b.billId), billNo: text(b.billNo), tripNo: text(b.tripNo), approvedAmount: b.approvedAmount,
      }))),
    vendorInvoicePayments: (id: string) =>
      guard(async () => (await payables.listVendorInvoicePayments({ id, limit: 100 })).items.map(vendorPaymentOf)),
    captureVendorInvoice: (d: VendorInvoiceDraft) =>
      guard(async () => {
        const ids = await billIds(d.billNos);
        return text((await payables.captureVendorInvoice({
          captureVendorInvoiceRequest: {
            vendorInvoiceNo: d.vendorInvoiceNo.trim(), invoiceDate: new Date(d.invoiceDate),
            amount: Number(d.amount), taxAmount: d.taxAmount.trim() ? Number(d.taxAmount) : undefined,
            freightBillIds: ids, documentRef: blankAsAbsent(d.documentRef),
          },
        })).id);
      }),
    listVendorPayments: () =>
      guard(async () => (await payables.listVendorPayments({ limit: 100 })).items.map(vendorPaymentOf)),
    recordVendorPayment: (invoiceId: string, d: VendorPaymentDraft) =>
      guard(async () => text((await payables.recordVendorPayment({
        id: invoiceId,
        recordVendorPaymentRequest: {
          amount: Number(d.amount), paidOn: new Date(d.paidOn), mode: d.mode as unknown as ApiVendorPaymentMode,
          reference: d.reference.trim(),
        },
      })).id)),
    billSettlement: (billId: string) =>
      guard(async (): Promise<BillSettlementView> => {
        const s = await payables.getBillSettlement({ id: billId });
        return {
          billNo: text(s.billNo), status: text(s.status), freightAmount: s.freightAmount, chargesAmount: s.chargesAmount,
          approvedAmount: s.approvedAmount, advancesPaid: s.advancesPaid, netPayable: s.netPayable,
        };
      }),
    listVendorAdvances: (tripId?: string) =>
      guard(async () => (await advances.listVendorAdvances({ tripId: tripId || undefined, limit: 100 }))
        .items.map(vendorAdvanceOf)),
    requestVendorAdvance: (d: VendorAdvanceDraft) =>
      guard(async (): Promise<MoneyRequestResult> => {
        const response = await advances.requestVendorAdvanceRaw({
          requestVendorAdvanceRequest: {
            tripId: d.tripId, mode: d.mode as unknown as ApiVendorAdvanceMode, amount: Number(d.amount),
            reason: d.reason.trim(),
          },
        });
        return { id: text((await response.value()).id), pending: response.raw.status === 202 };
      }),
    payVendorAdvance: (id: string, reference: string, paidOn: string) =>
      guard(async () => {
        const advance = await advances.getVendorAdvance({ id });
        await advances.payVendorAdvance({
          id, ifMatch: `"${advance.version}"`,
          payVendorAdvanceRequest: { reference: reference.trim(), paidOn: new Date(paidOn) },
        });
      }),
    cancelVendorAdvance: (id: string, reason: string) =>
      guard(async () => {
        const advance = await advances.getVendorAdvance({ id });
        await advances.cancelVendorAdvance({
          id, ifMatch: `"${advance.version}"`, cancelMoneyRequest: { reason: reason.trim() },
        });
      }),
  };
}

// Number series

type NumberingVerbs = Pick<LmsGateway, 'numberSeries' | 'numberSeriesScopes' | 'createNumberSeries'
  | 'setNumberSeriesActive' | 'deleteNumberSeries' | 'previewNumberFormat' | 'nextNumber' | 'issueNumber'
  | 'releaseNumber' | 'numberReleases' | 'verifyNumber'>;

/** A number series as the administration desk lists it. */
function numberSeriesOf(n: ApiNumberSeries): NumberSeriesView {
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
function numberReleaseRow(r: NumberRelease): Row {
  return {
    id: text(r.id), number: text(r.number), financialYear: text(r.financialYear), reason: text(r.reason),
    releasedBy: text(r.releasedBy), releasedAt: text(r.releasedAt), reusedAt: r.reusedAt ? text(r.reusedAt) : 'Not yet',
  };
}

/** Number series administration, over the same configuration as the rest of the gateway. */
export function numberingVerbs(config: Configuration): NumberingVerbs {
  const numbering = new NumberSeriesApi(config);
  const format = (f: NumberFormatDraft) => ({
    prefix: f.prefix.trim(), yearCode: f.yearCode as unknown as ApiNumberYearCode, separator: f.separator,
    counterLength: f.counterLength, yearStartMonth: f.yearStartMonth, timeZone: f.timeZone.trim() || undefined,
  });
  return {
    numberSeries: (documentType?: NumberedDocumentType) =>
      guard(async () => (await numbering.listNumberSeries({
        documentType: documentType as unknown as ApiNumberedDocumentType | undefined, limit: 200,
      })).items.map(numberSeriesOf)),
    numberSeriesScopes: () =>
      guard(async () => (await numbering.listNumberSeriesScopes({ limit: 200 })).items.map((u) => ({
        id: text(u.id), name: text(u.name), path: text(u.path), unitType: text(u.unitType),
      }))),
    createNumberSeries: (draft: NumberSeriesDraft) =>
      guard(async () => text((await numbering.createNumberSeries({
        createNumberSeriesRequest: {
          ...format(draft), code: draft.code.trim(),
          documentType: draft.documentType as unknown as ApiNumberedDocumentType,
          orgUnitId: draft.orgUnitId || undefined, reuseReleased: draft.reuseReleased, active: draft.active,
          description: draft.description.trim() || undefined,
        },
      })).id)),
    setNumberSeriesActive: (id: string, active: boolean) =>
      guard(async () => {
        if (active) {
          await numbering.activateNumberSeries({ id });
        } else {
          await numbering.deactivateNumberSeries({ id });
        }
      }),
    deleteNumberSeries: (id: string) => guard(async () => { await numbering.deleteNumberSeries({ id }); }),
    previewNumberFormat: (f: NumberFormatDraft) =>
      guard(async () => {
        const p = await numbering.previewNumberFormat(format(f));
        return { sample: text(p.sample), nextYearSample: text(p.nextYearSample) };
      }),
    nextNumber: (id: string) => guard(async () => text((await numbering.previewNextNumber({ id })).number)),
    issueNumber: (id: string) => guard(async () => text((await numbering.issueNumber({ id })).number)),
    releaseNumber: (id: string, number: string, reason: string) =>
      guard(async () => {
        await numbering.releaseNumber({ id, releaseNumberRequest: { number: number.trim(), reason: reason.trim() } });
      }),
    numberReleases: (id: string) =>
      guard(async () => (await numbering.listNumberReleases({ id, limit: 100 })).items.map(numberReleaseRow)),
    verifyNumber: (documentType: NumberedDocumentType, number: string) =>
      guard(async () => {
        const v = await numbering.verifyDocumentNumber({
          documentType: documentType as unknown as ApiNumberedDocumentType, number: number.trim(),
        });
        return {
          belongs: v.belongs, seriesCode: text(v.seriesCode), financialYear: text(v.financialYear),
          counter: text(v.counter), status: text(v.status),
        };
      }),
  };
}
