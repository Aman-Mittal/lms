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

import { ClientBillingApi, ClientServiceType, type ClientStorage, type Configuration } from '../api';
import type { LmsGateway } from './lmsGateway';
import type { MarginPage, StorageMonthView } from './clientBillingFormTypes';
import { blankAsAbsent, day, guard, text } from './gatewayShared';

type FormVerbs = Pick<LmsGateway, 'grantPortalAccess' | 'recordServiceUsage' | 'computeClientStorage'
  | 'clientStorage' | 'clientMargins'>;

function storageOf(s: ClientStorage): StorageMonthView {
  return {
    periodStart: text(s.periodStart), computed: s.computed === true, unitDays: text(s.unitDays ?? 0),
    palletDays: text(s.palletDays ?? 0),
    days: (s.days ?? []).map((d) => ({
      heldOn: text(d.heldOn), unitsHeld: text(d.unitsHeld), unitsPerPallet: text(d.unitsPerPallet),
      pallets: text(d.pallets), charged: !!d.chargeId,
    })),
  };
}

/** Portal access, hand-recorded service usage, a month's storage and the margin report of the client billing desk. */
export function clientBillingFormVerbs(config: Configuration): FormVerbs {
  const billing = new ClientBillingApi(config);

  return {
    grantPortalAccess: (clientId, userId) =>
      guard(async () => { await billing.grantClientPortalAccess({ clientId, clientPortalUserRequest: { userId: userId.trim() } }); }),

    recordServiceUsage: (clientId, draft) =>
      guard(async () => text((await billing.recordClientServiceUsage({
        clientId,
        serviceUsageRequest: {
          serviceType: draft.serviceType as ClientServiceType, serviceCode: blankAsAbsent(draft.serviceCode),
          quantity: Number(draft.quantity), occurredOn: day(draft.occurredOn), reference: blankAsAbsent(draft.reference),
          sourceKey: draft.sourceKey.trim(),
        },
      })).id)),

    computeClientStorage: (clientId, period) =>
      guard(async () => storageOf(await billing.computeClientStorage({
        clientId, clientStorageComputationRequest: { period: day(period) },
      }))),

    clientStorage: (clientId, period) =>
      guard(async () => storageOf(await billing.getClientStorage({ clientId, period: day(period) }))),

    clientMargins: (period, cursor) =>
      guard(async (): Promise<MarginPage> => {
        const page = await billing.listClientMargins({ period: day(period), cursor: blankAsAbsent(cursor ?? ''), limit: 50 });
        return {
          rows: page.items.map((m) => ({
            clientCode: text(m.clientCode), clientName: text(m.clientName), invoiceNo: text(m.invoiceNo),
            periodStart: text(m.periodStart), currency: text(m.currency), receivable: text(m.receivable),
            transportReceivable: text(m.transportReceivable), warehouseReceivable: text(m.warehouseReceivable),
            credited: text(m.credited), carrierPayable: text(m.carrierPayable), margin: text(m.margin),
            marginPct: text(m.marginPct), movements: text(m.movements), unpricedMovements: text(m.unpricedMovements),
          })),
          nextCursor: page.hasMore ? text(page.nextCursor) : '',
        };
      }),
  };
}
