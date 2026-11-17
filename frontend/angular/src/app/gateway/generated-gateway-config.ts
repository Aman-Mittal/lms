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

import type { ConfigAction, ConfigKind } from './config-types';
import { GeneratedGatewayMaster } from './generated-gateway-master';

/** Switching allocation rules, units, catalogue charges, webhooks, custom fields, bins, SKUs and guide vendors off and on, and voiding invoices. */
export abstract class GeneratedGatewayConfig extends GeneratedGatewayMaster {
  switchConfigRecord(kind: ConfigKind, id: string, action: ConfigAction, reason: string, version: number,
    parentId?: string): Promise<void> {
    const ifMatch = `"${version}"`;
    const reasonRequest = { reason: reason.trim() };
    const done = () => undefined;
    const on = action === 'reactivate';
    switch (kind) {
      case 'allocationRules':
        return on
          ? this.call(this.rules.reactivateAllocationRule({ id, ifMatch, reasonRequest }), done)
          : this.call(this.rules.deactivateAllocationRule({ id, ifMatch, reasonRequest }), done);
      case 'orgUnits':
        return on
          ? this.call(this.orgUnitsApi.reactivateOrgUnit({ id, ifMatch, reasonRequest }), done)
          : this.call(this.orgUnitsApi.deactivateOrgUnit({ id, ifMatch, reasonRequest }), done);
      case 'chargeCatalogue':
        return on
          ? this.call(this.charges.reactivateChargeCatalogueEntry({ id, ifMatch, reasonRequest }), done)
          : this.call(this.charges.deactivateChargeCatalogueEntry({ id, ifMatch, reasonRequest }), done);
      case 'webhooks':
        return on
          ? this.call(this.hooks.reactivateWebhookSubscription({ id, ifMatch, reasonRequest }), done)
          : this.call(this.hooks.deactivateWebhookSubscription({ id, ifMatch, reasonRequest }), done);
      case 'customFields':
        return on
          ? this.call(this.customFields.reactivateCustomField({ id, ifMatch, reasonRequest }), done)
          : this.call(this.customFields.deactivateCustomField({ id, ifMatch, reasonRequest }), done);
      case 'bins':
        return on
          ? this.call(this.warehouse.reactivateBin({ warehouseId: parentId ?? '', binId: id, ifMatch, reasonRequest }), done)
          : this.call(this.warehouse.deactivateBin({ warehouseId: parentId ?? '', binId: id, ifMatch, reasonRequest }), done);
      case 'skus':
        return on
          ? this.call(this.warehouse.reactivateSku({ id, ifMatch, reasonRequest }), done)
          : this.call(this.warehouse.deactivateSku({ id, ifMatch, reasonRequest }), done);
      case 'guideVendors':
        return on
          ? this.call(this.sourcing.reactivateRoutingGuideVendor({ id: parentId ?? '', entryId: id, ifMatch, reasonRequest }), done)
          : this.call(this.sourcing.deactivateRoutingGuideVendor({ id: parentId ?? '', entryId: id, ifMatch, reasonRequest }), done);
      case 'vendorInvoices':
        return this.call(this.payables.voidVendorInvoice({ id, ifMatch, reasonRequest }), done);
    }
  }
}
