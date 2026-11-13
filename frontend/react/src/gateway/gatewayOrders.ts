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
import type { WebhookEventType as ApiWebhookEventType, CloseKind as ApiCloseKind } from '../api';
import type {
  ApprovalPending, CloseKindChoice, ConsignmentPage, LoadBuild, LmsGateway, WebhookChanges, WebhookDeliveryPage,
  WebhookDraft, WebhookSecret, OrderDesk, OrderHeaderDraft, OrderLineDraft, OrderRaiseDraft, HistoryPage,
} from './lmsGateway';
import {
  GatewayDeps, blankAsAbsent, consignmentRow, deliveryRow, guard, headerRequest, historyRowOf, instantOrAbsent,
  lineRequest, loadRow, orderDeskOf, text, webhookOf,
} from './gatewayShared';

/** The webhooks, load building and the order desk. */
export function orderVerbs(d: GatewayDeps): Pick<LmsGateway,
  | 'webhooks' | 'subscribeWebhook' | 'reviseWebhook' | 'rotateWebhookSecret' | 'pingWebhook'
  | 'webhookDeliveries' | 'redeliverWebhook' | 'loadBuild' | 'unassignedConsignments'
  | 'assignConsignments' | 'removeFromLoad' | 'unplanLoad' | 'cancelLoad' | 'orderDesk' | 'raiseOrder'
  | 'reviseOrderHeader' | 'replaceOrderLines' | 'removeOrderLine' | 'amendOrder' | 'validateOrder'
  | 'cancelOrder' | 'closeOrder' | 'closeOrderLine' | 'orderHistory' | 'generateConsignments'> {
  const { orders, loads, webhooks } = d;

  return {
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
    removeFromLoad: (loadId: string, version: string, consignmentId: string) =>
      guard(async () => {
        await loads.removeConsignmentFromLoad({ id: loadId, ifMatch: version, consignmentId });
      }),
    unplanLoad: (loadId: string, version: string) =>
      guard(async () => {
        await loads.unplanLoad({ id: loadId, ifMatch: version });
      }),
    cancelLoad: (loadId: string, version: string, reason: string) =>
      guard(async () => {
        await loads.cancelLoad({ id: loadId, ifMatch: version, cancelLoadRequest: { reason: blankAsAbsent(reason) } });
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
          orgUnitId, orderNo: blankAsAbsent(draft.orderNo), customerPartnerId: draft.customerPartnerId.trim(),
          originTerminalId: draft.originTerminalId.trim(), requestedPickupAt: instantOrAbsent(draft.requestedPickupAt),
          requestedDeliveryAt: instantOrAbsent(draft.requestedDeliveryAt),
          contractLaneId: blankAsAbsent(draft.contractLaneId), loadType: blankAsAbsent(draft.loadType),
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
    cancelOrder: (orderId: string, reason: string): Promise<ApprovalPending | null> =>
      guard(async () => {
        // 204 when cancelled, 202 with the approval request when one is needed first.
        const raw = await orders.cancelOrderRaw({ id: orderId, cancelOrderRequest: { reason: blankAsAbsent(reason) } });
        if (raw.raw.status !== 202) {
          return null;
        }
        const t = await raw.value();
        return { id: text(t.id), status: text(t.status) };
      }),
    closeOrder: (orderId: string, kind: CloseKindChoice, reason: string) =>
      guard(async () => {
        await orders.closeOrder({
          id: orderId, closeOrderRequest: { kind: kind as ApiCloseKind, reason: blankAsAbsent(reason) },
        });
      }),
    closeOrderLine: (orderId: string, lineId: string, kind: CloseKindChoice, reason: string) =>
      guard(async () => {
        await orders.closeOrderLine({
          id: orderId, lineId, closeOrderRequest: { kind: kind as ApiCloseKind, reason: blankAsAbsent(reason) },
        });
      }),
    orderHistory: (orderId: string, cursor?: string): Promise<HistoryPage> =>
      guard(async () => {
        const page = await orders.getOrderHistory({ id: orderId, cursor: cursor || undefined, limit: 20 });
        return { entries: page.items.map(historyRowOf), nextCursor: page.nextCursor ?? null };
      }),
    generateConsignments: (orderId: string) =>
      guard(async () => (await loads.generateConsignments({ id: orderId })).length),
  };
}
