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
import { API_URL } from '../config';
import { GEOMETRY_TTL_MS, cacheGet, cachePut, cacheTouch, clientCacheEnabled, isFresh } from '../client-cache';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import {
  DemoDataService, ApprovalsService, ClientBillingService, AllocationRulesService, AuthenticationService,
  ControlTowerService, CustomFieldsService, DeliveryAndClaimsService, DriversService, EventDeadLetterService,
  LTLRatingService, MilkRunsService, MultimodalService, OrdersService, PartnersService, PlanningService,
  PublicTrackingService, SettlementService, SourcingService, TelematicsService, Terminal, TerminalsService,
  TrackingLinksService, TripsService, VehiclesService, WebhooksService, OrganisationUnitsService, SettingsService,
  WarehouseService, AccessPoliciesService, ComplianceService, UsersService, CreditService, NumberSeriesService,
  YardGateService, ChargesService, VendorAdvancesService, VendorPayablesService,
} from '../api';
import { ApiProblem, LmsGateway, ResourceKey, Row } from './lms-gateway';
import { text } from './generated-gateway-helpers';

/** The injected clients and the shared call and cached-read helpers; the verb groups extend it in turn. */
export abstract class GeneratedGatewayBase extends LmsGateway {
  protected readonly auth = inject(AuthenticationService);
  protected readonly controlTower = inject(ControlTowerService);
  protected readonly demo = inject(DemoDataService);
  protected readonly partners = inject(PartnersService);
  protected readonly vehicles = inject(VehiclesService);
  protected readonly drivers = inject(DriversService);
  protected readonly terminals = inject(TerminalsService);
  protected readonly http = inject(HttpClient);
  protected readonly orders = inject(OrdersService);
  protected readonly loads = inject(PlanningService);
  protected readonly trips = inject(TripsService);
  protected readonly freightBills = inject(SettlementService);
  protected readonly customFields = inject(CustomFieldsService);
  protected readonly delivery = inject(DeliveryAndClaimsService);
  protected readonly links = inject(TrackingLinksService);
  protected readonly publicRead = inject(PublicTrackingService);
  protected readonly rules = inject(AllocationRulesService);
  protected readonly sourcing = inject(SourcingService);
  protected readonly telematics = inject(TelematicsService);
  protected readonly multimodal = inject(MultimodalService);
  protected readonly milkRuns = inject(MilkRunsService);
  protected readonly ltl = inject(LTLRatingService);
  protected readonly deadLetters = inject(EventDeadLetterService);
  protected readonly approvalsApi = inject(ApprovalsService);
  protected readonly hooks = inject(WebhooksService);
  protected readonly clientBilling = inject(ClientBillingService);
  protected readonly warehouse = inject(WarehouseService);
  /** Awaits a generated call, maps its result, and turns any failure into an ApiProblem. */
  // Credit
  protected readonly credit = inject(CreditService);
  protected readonly gate = inject(YardGateService);
  protected readonly charges = inject(ChargesService);
  protected readonly payables = inject(VendorPayablesService);
  protected readonly advances = inject(VendorAdvancesService);
  protected readonly numbering = inject(NumberSeriesService);
  protected readonly settingsApi = inject(SettingsService);
  protected readonly orgUnitsApi = inject(OrganisationUnitsService);
  protected readonly users = inject(UsersService);
  protected readonly accessPolicyApi = inject(AccessPoliciesService);
  protected readonly compliance = inject(ComplianceService);

  protected readonly listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: () => this.call(this.partners.listPartners({ limit: displayPreferences().pageSize }), (s) => s.items.map((p) => ({
      id: text(p.id), code: text(p.code), legalName: text(p.legalName),
      partnerType: text(p.partnerType), status: text(p.status) }))),
    vehicles: () => this.call(this.vehicles.listVehicles({ limit: displayPreferences().pageSize }), (s) => s.items.map((v) => ({
      id: text(v.id), registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType),
      status: text(v.status), payloadCapacityKg: text(v.payloadCapacityKg) }))),
    drivers: () => this.call(this.drivers.listDrivers({ limit: displayPreferences().pageSize }), (s) => s.items.map((d) => ({
      id: text(d.id), fullName: text(d.fullName), licenceNo: text(d.licenceNo),
      licenceExpiresOn: text(d.licenceExpiresOn), status: text(d.status) }))),
    terminals: () => this.call(this.terminals.listTerminals({ limit: displayPreferences().pageSize }), (s) => s.items.map((t) => ({
      id: text(t.id), code: text(t.code), name: text(t.name),
      functionalCategory: text(t.functionalCategory), dockCount: text(t.dockCount) }))),
    orders: () => this.call(this.orders.listOrders({ limit: displayPreferences().pageSize }), (s) => s.items.map((o) => ({
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status),
      customerName: text(o.customerName), originTerminalCode: text(o.originTerminalCode) }))),
    loads: () => this.call(this.loads.listLoads({ limit: displayPreferences().pageSize }), (s) => s.items.map((l) => ({
      id: text(l.id), loadNo: text(l.loadNo), status: text(l.status),
      vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) }))),
    trips: () => this.call(this.trips.listTrips({ limit: displayPreferences().pageSize }), (s) => s.items.map((t) => ({
      id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) }))),
    'freight-bills': () => this.call(this.freightBills.listFreightBills({ limit: displayPreferences().pageSize }), (s) => s.items.map((b) => ({
      id: text(b.id), billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) }))),
    exceptions: () => this.call(this.controlTower.listExceptions({ limit: displayPreferences().pageSize }), (s) => s.items.map((e) => ({
      id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
      category: text(e.category), description: text(e.description) }))),
    claims: () => this.claims(),
  };

  /** Each distinct terminal named, read once. */
  protected async terminalsById(ids: Array<string | null | undefined>): Promise<Map<string, Terminal>> {
    const distinct = [...new Set(ids.filter((id): id is string => !!id))];
    const read = await Promise.all(distinct.map((id) => this.cachedGet<Terminal>(`terminal:${id}`, `/api/v1/terminals/${id}`,
      () => this.call(this.terminals.getTerminal({ id }), (t) => t))));
    return new Map(read.map((t) => [text(t.id), t]));
  }

  /** Every site with its geofence, from the browser's copy while the server says it has not changed. */
  protected sitesForMap(): Promise<{ items: Terminal[] }> {
    return this.cachedGet('terminals:map', '/api/v1/terminals?limit=200',
      () => this.call(this.terminals.listTerminals({ limit: 200 }), (x) => x));
  }

  /**
   * A reference read kept in the browser (DOCS/adr/0007) and revalidated with its ETag: inside the
   * fresh window it costs no request, after it a conditional one, and a 304 costs no body. Anything
   * unexpected, or the cache being off, is the ordinary call.
   */
  protected async cachedGet<T>(key: string, path: string, load: () => Promise<T>): Promise<T> {
    if (!clientCacheEnabled()) {
      return load();
    }
    const held = await cacheGet<T>(key);
    if (held && isFresh(held)) {
      return held.value;
    }
    try {
      const headers: Record<string, string> = held?.version ? { 'If-None-Match': held.version } : {};
      const response = await firstValueFrom(this.http.get<T>(`${API_URL}${path}`, { observe: 'response', headers }));
      if (response.body !== null) {
        await cachePut(key, response.body, GEOMETRY_TTL_MS, response.headers.get('ETag'));
        return response.body;
      }
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 304 && held) {
        await cacheTouch(key, GEOMETRY_TTL_MS);
        return held.value;
      }
    }
    return load();
  }

  protected async call<T, R>(request: Observable<T>, map: (value: T) => R): Promise<R> {
    try {
      return map(await firstValueFrom(request));
    } catch (e) {
      if (e instanceof HttpErrorResponse) {
        const body = e.error as { detail?: string; code?: string; errors?: Record<string, string> } | null;
        if (body?.errors && e.status !== 409) {
          throw new ApiProblem(Object.entries(body.errors).map(([k, v]) => `${k}: ${v}`).join('; '), e.status, body.errors,
            body.code ?? '');
        }
        throw new ApiProblem(body?.detail ?? (e.status ? `${e.status} ${e.statusText}` : 'Could not reach the API. Try again.'),
          e.status, body?.errors ?? {}, body?.code ?? '');
      }
      throw e;
    }
  }
}
