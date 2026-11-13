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
import type { ListPartnersPartnerTypeEnum } from '../api';
import type {
  ApprovalPending, ClientAgeingView, ClientInvoiceDetail, CreditNoteDraft, PaymentDraft, ContractChargeDraft,
  ContractChecklistRow, ContractDetail, ContractDraft, ContractLaneChoice, ContractLaneDraft, Choice, LmsGateway,
} from './lmsGateway';
import {
  GatewayDeps, chargeRow, contractDetailOf, contractRow, contractTerms, day, guard, invoiceLineRow, invoiceRow,
  laneChoiceOf, laneRequest, pendingOf, statementRow, text,
} from './gatewayShared';

/** The client contracts, charges, invoices and statements. */
export function clientBillingVerbs(d: GatewayDeps): Pick<LmsGateway,
  | 'clientChoices' | 'clientContracts' | 'contractDetail' | 'createContractDraft'
  | 'reviseContractDraft' | 'reviseContractLanes' | 'contractLanes' | 'deleteContractDraft'
  | 'contractChecklist' | 'requestContractVerification' | 'requestContractExtension'
  | 'requestContractChargeAddition' | 'clientCharges' | 'markConsignmentBillable' | 'runClientBilling'
  | 'clientInvoices' | 'clientInvoice' | 'recordClientPayment' | 'issueClientCreditNote'
  | 'clientStatement' | 'clientAgeing'> {
  const { partners, clientBilling } = d;

  return {
    clientChoices: (): Promise<Choice[]> =>
      guard(async () => (await partners.listPartners({
        partnerType: 'CUSTOMER' as unknown as ListPartnersPartnerTypeEnum, limit: 200,
      })).items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` }))),
    clientContracts: (clientId: string) =>
      guard(async () => (await clientBilling.listClientContracts({ clientId })).map(contractRow)),
    contractDetail: (contractId: string): Promise<ContractDetail> =>
      guard(async () => contractDetailOf(await clientBilling.getClientContract({ id: contractId }))),
    createContractDraft: (orgUnitId: string, clientId: string, draft: ContractDraft): Promise<string> =>
      guard(async () => text((await clientBilling.createClientContractDraft({
        clientContractDraftRequest: { orgUnitId, clientPartnerId: clientId, ...contractTerms(draft) },
      })).id)),
    reviseContractDraft: (current: ContractDetail, draft: ContractDraft): Promise<void> =>
      guard(async () => {
        await clientBilling.reviseClientContractDraft({
          id: current.id, ifMatch: current.version,
          reviseClientContractRequest: {
            orgUnitId: current.orgUnitId, currency: current.currency, ...contractTerms(draft),
            transportTaxRatePct: Number(current.transportTaxRatePct || 0),
            warehouseTaxRatePct: Number(current.warehouseTaxRatePct || 0),
            storageUnitsPerPallet: current.storageUnitsPerPallet, billOnDispatch: current.billOnDispatch,
            valueAddedServices: current.services.map((v) => ({ serviceCode: v.serviceCode, unit: v.unit, rate: Number(v.rate) })),
          },
        });
      }),
    reviseContractLanes: (current: ContractDetail, lanes: ContractLaneDraft[], directOrdersAllowed: boolean,
      allowedLoadTypes: string[]): Promise<void> =>
      guard(async () => {
        await clientBilling.reviseClientContractDraft({
          id: current.id, ifMatch: current.version,
          reviseClientContractRequest: {
            orgUnitId: current.orgUnitId, currency: current.currency, ...contractTerms({
              code: current.code, effectiveFrom: current.effectiveFrom, validTill: current.validTill,
              transportRatePerKg: current.transportRatePerKg, transportMinimumCharge: current.transportMinimumCharge,
              storageRatePerPalletDay: current.storageRatePerPalletDay,
              handlingInRatePerPallet: current.handlingInRatePerPallet,
              handlingOutRatePerPallet: current.handlingOutRatePerPallet,
            }),
            transportTaxRatePct: Number(current.transportTaxRatePct || 0),
            warehouseTaxRatePct: Number(current.warehouseTaxRatePct || 0),
            storageUnitsPerPallet: current.storageUnitsPerPallet, billOnDispatch: current.billOnDispatch,
            valueAddedServices: current.services.map((v) => ({ serviceCode: v.serviceCode, unit: v.unit, rate: Number(v.rate) })),
            directOrdersAllowed, allowedLoadTypes, lanes: lanes.map(laneRequest),
          },
        });
      }),
    contractLanes: (clientId: string, onDate?: string): Promise<ContractLaneChoice[]> =>
      guard(async () => (await clientBilling.listClientContractLanes({
        clientId, onDate: onDate ? day(onDate) : undefined,
      })).map(laneChoiceOf)),
    deleteContractDraft: (contractId: string, version: string): Promise<void> =>
      guard(async () => {
        await clientBilling.deleteClientContractDraft({ id: contractId, ifMatch: version });
      }),
    contractChecklist: (contractId: string): Promise<ContractChecklistRow[]> =>
      guard(async () => (await clientBilling.getClientContractChecklist({ id: contractId })).map((i) => ({
        code: text(i.code), required: !!i.required, passed: !!i.passed, message: text(i.message),
      }))),
    requestContractVerification: (contractId: string, version: string): Promise<ApprovalPending | null> =>
      guard(async () => pendingOf(await clientBilling.requestClientContractVerificationRaw({
        id: contractId, ifMatch: version,
      }))),
    requestContractExtension: (contractId: string, validTill: string, reason: string): Promise<ApprovalPending | null> =>
      guard(async () => pendingOf(await clientBilling.requestClientContractExtensionRaw({
        id: contractId, clientContractExtensionRequest: { validTill: day(validTill), reason },
      }))),
    requestContractChargeAddition: (contractId: string, draft: ContractChargeDraft): Promise<ApprovalPending | null> =>
      guard(async () => pendingOf(await clientBilling.requestClientContractChargeAdditionRaw({
        id: contractId,
        clientContractChargeAdditionRequest: {
          serviceCode: draft.serviceCode.trim(), unit: draft.unit.trim(), rate: Number(draft.rate),
          effectiveFrom: day(draft.effectiveFrom), reason: draft.reason,
        },
      }))),
    clientCharges: (clientId: string, period: string) =>
      guard(async () => (await clientBilling.listClientCharges({ clientId, period: day(period), limit: 200 }))
        .items.map(chargeRow)),
    markConsignmentBillable: (consignmentId: string, reason: string) =>
      guard(async () => {
        await clientBilling.markConsignmentBillable({ consignmentId, billingEligibilityRequest: { reason } });
      }),
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
  };
}
