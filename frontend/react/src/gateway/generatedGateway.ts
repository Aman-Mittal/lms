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
import { displayPreferences } from '../display';
import {
  AllocationRulesApi, AuthenticationApi, ControlTowerApi, Configuration, CustomFieldsApi, DeliveryAndClaimsApi,
  DriversApi, EventDeadLetterApi, LTLRatingApi, OrdersApi, MilkRunsApi, MultimodalApi, PartnersApi, PlanningApi,
  PublicTrackingApi, SettlementApi, TerminalsApi, SourcingApi, TelematicsApi, TrackingLinksApi, TripsApi,
  VehiclesApi, WebhooksApi, SettingsApi, DemoDataApi, ApprovalsApi, ClientBillingApi, WarehouseApi,
} from '../api';
import { API_URL } from '../config';
import { GEOMETRY_TTL_MS, cacheGet, cachePut, cacheTouch, clientCacheEnabled, isFresh } from '../client-cache';
import type { LmsGateway, ResourceKey, Row } from './lmsGateway';
import { GatewayDeps, text } from './gatewayShared';
import { adminGateway, warehouseVerbs } from './gatewayWarehouseAdmin';
import { clientBillingVerbs } from './gatewayClientBilling';
import { creditVerbs, gateVerbs } from './gatewayCreditGate';
import { deliveryVerbs } from './gatewayDelivery';
import { moneyVerbs, numberingVerbs } from './gatewayMoney';
import { networkVerbs } from './gatewayNetwork';
import { orderVerbs } from './gatewayOrders';
import { pickerVerbs, rateCardVerbs } from './gatewayCommercial';
import { settingsVerbs } from './gatewaySettings';
import { sourcingVerbs } from './gatewayTail';

/** The adapter: the only file in the React app that imports the generated client. */
export function createGeneratedGateway(getToken: () => string | null): LmsGateway {
  // The token is read per request (a function), so one gateway outlives sign-ins.
  const config = new Configuration({ basePath: API_URL, accessToken: () => getToken() ?? '' });
  const auth = new AuthenticationApi(config);
  const controlTower = new ControlTowerApi(config);
  const demo = new DemoDataApi(config);
  const partners = new PartnersApi(config);
  const vehicles = new VehiclesApi(config);
  const drivers = new DriversApi(config);
  const terminals = new TerminalsApi(config);
  const orders = new OrdersApi(config);
  const loads = new PlanningApi(config);
  const trips = new TripsApi(config);
  const freightBills = new SettlementApi(config);
  const customFields = new CustomFieldsApi(config);
  const telematics = new TelematicsApi(config);
  const multimodal = new MultimodalApi(config);
  const milkRuns = new MilkRunsApi(config);
  const ltl = new LTLRatingApi(config);
  const deadLetters = new EventDeadLetterApi(config);
  const approvalsApi = new ApprovalsApi(config);
  const webhooks = new WebhooksApi(config);
  const delivery = new DeliveryAndClaimsApi(config);
  const links = new TrackingLinksApi(config);
  const rules = new AllocationRulesApi(config);
  const sourcing = new SourcingApi(config);
  const clientBilling = new ClientBillingApi(config);
  // The public read is made with no credentials at all: a customer has none, and
  // a console left signed in must not send its token to a route that ignores it.
  const publicTracking = new PublicTrackingApi(new Configuration({ basePath: API_URL }));

  /**
   * A reference read kept in the browser (DOCS/adr/0007) and revalidated with its ETag: inside the
   * fresh window it costs no request, after it a conditional one, and a 304 costs no body. Anything
   * unexpected, or the cache being off, is the ordinary call.
   */
  const cachedGet = async <T>(key: string, path: string, load: () => Promise<T>): Promise<T> => {
    if (!clientCacheEnabled()) {
      return load();
    }
    const held = await cacheGet<T>(key);
    if (held && isFresh(held)) {
      return held.value;
    }
    try {
      const headers: Record<string, string> = { Authorization: `Bearer ${getToken() ?? ''}` };
      if (held?.version) {
        headers['If-None-Match'] = held.version;
      }
      const response = await fetch(`${API_URL}${path}`, { headers });
      if (response.status === 304 && held) {
        await cacheTouch(key, GEOMETRY_TTL_MS);
        return held.value;
      }
      if (response.ok) {
        const body = (await response.json()) as T;
        await cachePut(key, body, GEOMETRY_TTL_MS, response.headers.get('ETag'));
        return body;
      }
    } catch { /* the ordinary call below says what went wrong */ }
    return load();
  };

  const listClaims = async (consignmentId?: string) =>
    (await delivery.listClaims({ consignmentId: consignmentId || undefined, limit: 50 })).items.map((c) => ({
      id: text(c.id), claimNo: text(c.claimNo), claimType: text(c.claimType), amount: text(c.amount),
      status: text(c.status), consignmentId: text(c.consignmentId),
      approvedAmount: text(c.approvedAmount), description: text(c.description),
      resolutionNote: text(c.resolutionNote) }));

  const listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: async () => (await partners.listPartners({ limit: displayPreferences().pageSize })).items.map((p) => ({
      id: text(p.id), code: text(p.code), legalName: text(p.legalName),
      partnerType: text(p.partnerType), status: text(p.status) })),
    vehicles: async () => (await vehicles.listVehicles({ limit: displayPreferences().pageSize })).items.map((v) => ({
      id: text(v.id), registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType),
      status: text(v.status), payloadCapacityKg: text(v.payloadCapacityKg) })),
    drivers: async () => (await drivers.listDrivers({ limit: displayPreferences().pageSize })).items.map((d) => ({
      id: text(d.id), fullName: text(d.fullName), licenceNo: text(d.licenceNo),
      licenceExpiresOn: text(d.licenceExpiresOn), status: text(d.status) })),
    terminals: async () => (await terminals.listTerminals({ limit: displayPreferences().pageSize })).items.map((t) => ({
      id: text(t.id), code: text(t.code), name: text(t.name),
      functionalCategory: text(t.functionalCategory), dockCount: text(t.dockCount) })),
    orders: async () => (await orders.listOrders({ limit: displayPreferences().pageSize })).items.map((o) => ({
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status),
      customerName: text(o.customerName), originTerminalCode: text(o.originTerminalCode) })),
    loads: async () => (await loads.listLoads({ limit: displayPreferences().pageSize })).items.map((l) => ({
      id: text(l.id), loadNo: text(l.loadNo), status: text(l.status),
      vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) })),
    trips: async () => (await trips.listTrips({ limit: displayPreferences().pageSize })).items.map((t) => ({
      id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) })),
    'freight-bills': async () => (await freightBills.listFreightBills({ limit: displayPreferences().pageSize })).items.map((b) => ({
      id: text(b.id), billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) })),
    exceptions: async () => (await controlTower.listExceptions({ limit: displayPreferences().pageSize })).items.map((e) => ({
      id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
      category: text(e.category), description: text(e.description) })),
    claims: () => listClaims(),
  };

  const deps: GatewayDeps = {
    getToken, config, auth, controlTower, demo, partners, vehicles, drivers, terminals, orders, loads, trips,
    freightBills, customFields, telematics, multimodal, milkRuns, ltl, deadLetters, approvalsApi, webhooks,
    delivery, links, rules, sourcing, clientBilling, publicTracking, cachedGet, listClaims, listers,
  };

  return {
    ...deliveryVerbs(deps),
    ...networkVerbs(deps),
    ...orderVerbs(deps),
    ...clientBillingVerbs(deps),

    // Warehouse operations
    ...warehouseVerbs(new WarehouseApi(config), terminals),
    // Administration
    ...adminGateway(config),
    // Rate cards and rail tariffs
    ...rateCardVerbs(config),
    // Credit
    ...creditVerbs(config),
    // Charges, payables and advances
    ...moneyVerbs(config, freightBills),
    // Number series
    ...numberingVerbs(config),
    // Yard gate and trip stops
    ...gateVerbs(config, trips),
    ...settingsVerbs(config),
    // Pickers
    ...pickerVerbs({
      partners, terminals, vehicles, drivers, warehouse: new WarehouseApi(config), orders, loads, trips,
      controlTower, sourcing, freightBills, settings: new SettingsApi(config),
    }),

    ...sourcingVerbs(deps),
  };
}
