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
  type ApprovalPending, type CloseKindChoice, type ClientAgeingView, type ClientInvoiceDetail,
  type CreditNoteDraft, type PaymentDraft, type ContractChargeDraft, type ContractChecklistRow,
  type ContractDetail, type ContractDraft, type ContractLaneChoice, type ContractLaneDraft, Choice,
  ConsignmentPage, LoadBuild, Row, WebhookChanges, WebhookDeliveryPage, WebhookDraft, WebhookSecret, WebhookView,
  OrderDesk, OrderHeaderDraft, OrderLineDraft, OrderRaiseDraft, HistoryPage, type AsnDraft, type DispatchDraft,
  type OutboundDraft, type ReceiptCountDraft, type SkuDraft, type StockMoveDraft, type WarehouseDocument,
} from './lms-gateway';
import { type CloseKind as ApiCloseKind, WebhookEventType as ApiWebhookEventType } from '../api';
import { GeneratedGatewayDelivery } from './generated-gateway-delivery';
import {
  blankAsAbsent, chargeRow, consignmentRow, contractDetailOf, contractRow, contractTerms, deliveryRow,
  headerRequest, historyRowOf, invoiceLineRow, invoiceRow, laneChoiceOf, laneRequest, lineRequest, loadRow,
  numberOrAbsent, orderDeskOf, statementRow, text, webhookOf,
} from './generated-gateway-helpers';
import { documentOf, flatRow, stockMove } from './generated-gateway-mappers';

/** The webhooks, load building, the order desk, client billing and the warehouse. */
export abstract class GeneratedGatewayOrders extends GeneratedGatewayDelivery {
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

  removeFromLoad(loadId: string, version: string, consignmentId: string): Promise<void> {
    return this.call(this.loads.removeConsignmentFromLoad({ id: loadId, ifMatch: version, consignmentId }),
      () => undefined);
  }

  unplanLoad(loadId: string, version: string): Promise<void> {
    return this.call(this.loads.unplanLoad({ id: loadId, ifMatch: version }), () => undefined);
  }

  cancelLoad(loadId: string, version: string, reason: string): Promise<void> {
    return this.call(this.loads.cancelLoad({
      id: loadId, ifMatch: version, cancelLoadRequest: { reason: blankAsAbsent(reason) },
    }), () => undefined);
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
        orgUnitId, orderNo: blankAsAbsent(draft.orderNo), customerPartnerId: draft.customerPartnerId.trim(),
        originTerminalId: draft.originTerminalId.trim(), requestedPickupAt: blankAsAbsent(draft.requestedPickupAt),
        requestedDeliveryAt: blankAsAbsent(draft.requestedDeliveryAt),
        contractLaneId: blankAsAbsent(draft.contractLaneId), loadType: blankAsAbsent(draft.loadType),
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

  cancelOrder(orderId: string, reason: string): Promise<ApprovalPending | null> {
    return this.call(this.orders.cancelOrder({ id: orderId, cancelOrderRequest: { reason: blankAsAbsent(reason) } }),
      (t) => (t ? { id: text(t.id), status: text(t.status) } : null));
  }

  closeOrder(orderId: string, kind: CloseKindChoice, reason: string): Promise<void> {
    return this.call(this.orders.closeOrder({
      id: orderId, closeOrderRequest: { kind: kind as ApiCloseKind, reason: blankAsAbsent(reason) },
    }), () => undefined);
  }

  closeOrderLine(orderId: string, lineId: string, kind: CloseKindChoice, reason: string): Promise<void> {
    return this.call(this.orders.closeOrderLine({
      id: orderId, lineId, closeOrderRequest: { kind: kind as ApiCloseKind, reason: blankAsAbsent(reason) },
    }), () => undefined);
  }

  orderHistory(orderId: string, cursor?: string): Promise<HistoryPage> {
    return this.call(this.orders.getOrderHistory({ id: orderId, cursor: cursor || undefined, limit: 20 }),
      (page) => ({ entries: page.items.map(historyRowOf), nextCursor: page.nextCursor ?? null }));
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

  contractDetail(contractId: string): Promise<ContractDetail> {
    return this.call(this.clientBilling.getClientContract({ id: contractId }), contractDetailOf);
  }

  createContractDraft(orgUnitId: string, clientId: string, draft: ContractDraft): Promise<string> {
    return this.call(this.clientBilling.createClientContractDraft({
      clientContractDraftRequest: { orgUnitId, clientPartnerId: clientId, ...contractTerms(draft) },
    }), (r) => text(r.id));
  }

  reviseContractDraft(current: ContractDetail, draft: ContractDraft): Promise<void> {
    return this.call(this.clientBilling.reviseClientContractDraft({
      id: current.id, ifMatch: current.version,
      reviseClientContractRequest: {
        orgUnitId: current.orgUnitId, currency: current.currency, ...contractTerms(draft),
        transportTaxRatePct: Number(current.transportTaxRatePct || 0),
        warehouseTaxRatePct: Number(current.warehouseTaxRatePct || 0),
        storageUnitsPerPallet: current.storageUnitsPerPallet, billOnDispatch: current.billOnDispatch,
        valueAddedServices: current.services.map((v) => ({ serviceCode: v.serviceCode, unit: v.unit, rate: Number(v.rate) })),
      },
    }), () => undefined);
  }

  reviseContractLanes(current: ContractDetail, lanes: ContractLaneDraft[], directOrdersAllowed: boolean,
    allowedLoadTypes: string[]): Promise<void> {
    return this.call(this.clientBilling.reviseClientContractDraft({
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
    }), () => undefined);
  }

  contractLanes(clientId: string, onDate?: string): Promise<ContractLaneChoice[]> {
    return this.call(this.clientBilling.listClientContractLanes({ clientId, onDate: onDate || undefined }),
      (rows) => rows.map(laneChoiceOf));
  }

  deleteContractDraft(contractId: string, version: string): Promise<void> {
    return this.call(this.clientBilling.deleteClientContractDraft({ id: contractId, ifMatch: version }), () => undefined);
  }

  contractChecklist(contractId: string): Promise<ContractChecklistRow[]> {
    return this.call(this.clientBilling.getClientContractChecklist({ id: contractId }), (items) => items.map((i) => ({
      code: text(i.code), required: !!i.required, passed: !!i.passed, message: text(i.message),
    })));
  }

  requestContractVerification(contractId: string, version: string): Promise<ApprovalPending | null> {
    return this.call(this.clientBilling.requestClientContractVerification({ id: contractId, ifMatch: version }),
      (t) => (t ? { id: text(t.id), status: text(t.status) } : null));
  }

  requestContractExtension(contractId: string, validTill: string, reason: string): Promise<ApprovalPending | null> {
    return this.call(this.clientBilling.requestClientContractExtension({
      id: contractId, clientContractExtensionRequest: { validTill, reason },
    }), (t) => (t ? { id: text(t.id), status: text(t.status) } : null));
  }

  requestContractChargeAddition(contractId: string, draft: ContractChargeDraft): Promise<ApprovalPending | null> {
    return this.call(this.clientBilling.requestClientContractChargeAddition({
      id: contractId,
      clientContractChargeAdditionRequest: {
        serviceCode: draft.serviceCode.trim(), unit: draft.unit.trim(), rate: Number(draft.rate),
        effectiveFrom: draft.effectiveFrom, reason: draft.reason,
      },
    }), (t) => (t ? { id: text(t.id), status: text(t.status) } : null));
  }

  clientCharges(clientId: string, period: string): Promise<Row[]> {
    return this.call(this.clientBilling.listClientCharges({ clientId, period, limit: 200 }),
      (page) => page.items.map(chargeRow));
  }

  markConsignmentBillable(consignmentId: string, reason: string): Promise<void> {
    return this.call(this.clientBilling.markConsignmentBillable({
      consignmentId, billingEligibilityRequest: { reason },
    }), () => undefined);
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
}
