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
  ChargesApi, OrganisationUnitsApi, VendorPayablesApi, WarehouseApi, type Configuration,
} from '../api';
import type { ConfigAction, ConfigKind, LmsGateway } from './lmsGateway';
import { GatewayDeps, guard } from './gatewayShared';

/**
 * Switching allocation rules, units, catalogue charges, webhooks, custom fields, bins, SKUs and routing-guide vendors
 * off and on, and voiding vendor invoices, each with a reason and conditional on the version the screen read.
 */
export function configVerbs(config: Configuration, d: GatewayDeps): Pick<LmsGateway, 'switchConfigRecord'> {
  const rules = d.rules;
  const units = new OrganisationUnitsApi(config);
  const charges = new ChargesApi(config);
  const payables = new VendorPayablesApi(config);
  const warehouse = new WarehouseApi(config);
  const { webhooks, customFields, sourcing } = d;

  return {
    switchConfigRecord: (kind: ConfigKind, id: string, action: ConfigAction, reason: string, version: number,
      parentId?: string) => guard(async () => {
      const ifMatch = String(version);
      const reasonRequest = { reason: reason.trim() };
      const on = action === 'reactivate';
      switch (kind) {
        case 'allocationRules':
          await (on ? rules.reactivateAllocationRule : rules.deactivateAllocationRule)
            .call(rules, { id, ifMatch, reasonRequest });
          break;
        case 'orgUnits':
          await (on ? units.reactivateOrgUnit : units.deactivateOrgUnit).call(units, { id, ifMatch, reasonRequest });
          break;
        case 'chargeCatalogue':
          await (on ? charges.reactivateChargeCatalogueEntry : charges.deactivateChargeCatalogueEntry)
            .call(charges, { id, ifMatch, reasonRequest });
          break;
        case 'webhooks':
          await (on ? webhooks.reactivateWebhookSubscription : webhooks.deactivateWebhookSubscription)
            .call(webhooks, { id, ifMatch, reasonRequest });
          break;
        case 'customFields':
          await (on ? customFields.reactivateCustomField : customFields.deactivateCustomField)
            .call(customFields, { id, ifMatch, reasonRequest });
          break;
        case 'bins':
          await (on ? warehouse.reactivateBin : warehouse.deactivateBin)
            .call(warehouse, { warehouseId: parentId ?? '', binId: id, ifMatch, reasonRequest });
          break;
        case 'skus':
          await (on ? warehouse.reactivateSku : warehouse.deactivateSku).call(warehouse, { id, ifMatch, reasonRequest });
          break;
        case 'guideVendors':
          await (on ? sourcing.reactivateRoutingGuideVendor : sourcing.deactivateRoutingGuideVendor)
            .call(sourcing, { id: parentId ?? '', entryId: id, ifMatch, reasonRequest });
          break;
        case 'vendorInvoices':
          await payables.voidVendorInvoice({ id, ifMatch, reasonRequest });
          break;
      }
    }),
  };
}
