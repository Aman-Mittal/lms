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

import { ClientServiceType } from '../api';
import type { ClientStorage } from '../api';
import type { MarginPage, ServiceUsageDraft, StorageMonthView } from './client-billing-form-types';
import { GeneratedGatewayUsers } from './generated-gateway-users';
import { blankAsAbsent, text } from './generated-gateway-helpers';

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
export abstract class GeneratedGatewayClientBillingForms extends GeneratedGatewayUsers {
  grantPortalAccess(clientId: string, userId: string): Promise<void> {
    return this.call(this.clientBilling.grantClientPortalAccess({
      clientId, clientPortalUserRequest: { userId: userId.trim() },
    }), () => undefined);
  }

  recordServiceUsage(clientId: string, draft: ServiceUsageDraft): Promise<string> {
    return this.call(this.clientBilling.recordClientServiceUsage({
      clientId,
      serviceUsageRequest: {
        serviceType: draft.serviceType as ClientServiceType, serviceCode: blankAsAbsent(draft.serviceCode),
        quantity: Number(draft.quantity), occurredOn: draft.occurredOn, reference: blankAsAbsent(draft.reference),
        sourceKey: draft.sourceKey.trim(),
      },
    }), (r) => text(r.id));
  }

  computeClientStorage(clientId: string, period: string): Promise<StorageMonthView> {
    return this.call(this.clientBilling.computeClientStorage({
      clientId, clientStorageComputationRequest: { period },
    }), storageOf);
  }

  clientStorage(clientId: string, period: string): Promise<StorageMonthView> {
    return this.call(this.clientBilling.getClientStorage({ clientId, period }), storageOf);
  }

  clientMargins(period: string, cursor?: string): Promise<MarginPage> {
    return this.call(this.clientBilling.listClientMargins({ period, cursor: blankAsAbsent(cursor ?? ''), limit: 50 }), (page) => ({
      rows: page.items.map((m) => ({
        clientCode: text(m.clientCode), clientName: text(m.clientName), invoiceNo: text(m.invoiceNo),
        periodStart: text(m.periodStart), currency: text(m.currency), receivable: text(m.receivable),
        transportReceivable: text(m.transportReceivable), warehouseReceivable: text(m.warehouseReceivable),
        credited: text(m.credited), carrierPayable: text(m.carrierPayable), margin: text(m.margin),
        marginPct: text(m.marginPct), movements: text(m.movements), unpricedMovements: text(m.unpricedMovements),
      })),
      nextCursor: page.hasMore ? text(page.nextCursor) : '',
    }));
  }
}
