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

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom, Observable } from 'rxjs';
import {
  AuthenticationService, ControlTowerService, OrdersService, PartnersService, PlanningService,
  RegisterPartnerRequestPartnerTypeEnum, SettlementService, TripsService, VehiclesService,
} from '../api';
import {
  ApiProblem, ControlTowerSummary, Credentials, LmsGateway, PartnerDraft, ResourceKey, Row, SignedIn,
} from './lms-gateway';

/** A display cell: the contract's optional fields become an empty string. */
const text = (v: unknown): string => (v === null || v === undefined ? '' : String(v));

/** The adapter: the only file in the Angular app (besides app config) that knows the generated client. */
@Injectable()
export class GeneratedLmsGateway extends LmsGateway {
  private readonly auth = inject(AuthenticationService);
  private readonly controlTower = inject(ControlTowerService);
  private readonly partners = inject(PartnersService);
  private readonly vehicles = inject(VehiclesService);
  private readonly orders = inject(OrdersService);
  private readonly loads = inject(PlanningService);
  private readonly trips = inject(TripsService);
  private readonly freightBills = inject(SettlementService);

  private readonly listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: () => this.call(this.partners.listPartners({ limit: 50 }), (s) => s.items.map((p) => ({
      code: text(p.code), legalName: text(p.legalName), partnerType: text(p.partnerType), status: text(p.status) }))),
    vehicles: () => this.call(this.vehicles.listVehicles({ limit: 50 }), (s) => s.items.map((v) => ({
      registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType), status: text(v.status),
      payloadCapacityKg: text(v.payloadCapacityKg) }))),
    orders: () => this.call(this.orders.listOrders({ limit: 50 }), (s) => s.items.map((o) => ({
      orderNo: text(o.orderNo), status: text(o.status), customerName: text(o.customerName),
      originTerminalCode: text(o.originTerminalCode) }))),
    loads: () => this.call(this.loads.listLoads({ limit: 50 }), (s) => s.items.map((l) => ({
      loadNo: text(l.loadNo), status: text(l.status), vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) }))),
    trips: () => this.call(this.trips.listTrips({ limit: 50 }), (s) => s.items.map((t) => ({
      tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) }))),
    'freight-bills': () => this.call(this.freightBills.listFreightBills({ limit: 50 }), (s) => s.items.map((b) => ({
      billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) }))),
  };

  signIn(credentials: Credentials): Promise<SignedIn> {
    return this.call(this.auth.login({ loginRequest: credentials }), (r) => ({
      token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissionCount: r.permissions?.length ?? 0 }));
  }

  summary(): Promise<ControlTowerSummary> {
    return this.call(this.controlTower.summariseExceptions(), (s) => ({
      openP1: s.openP1 ?? 0, openP2: s.openP2 ?? 0, openP3: s.openP3 ?? 0,
      unassigned: s.unassigned ?? 0, settlementHolds: s.settlementHolds ?? 0,
      openWithoutTrip: s.openWithoutTrip ?? 0 }));
  }

  list(resource: ResourceKey): Promise<Row[]> {
    return this.listers[resource]();
  }

  registerPartner(orgUnitId: string, draft: PartnerDraft): Promise<void> {
    return this.call(this.partners.registerPartner({
      registerPartnerRequest: {
        orgUnitId, code: draft.code, legalName: draft.legalName,
        partnerType: draft.partnerType as RegisterPartnerRequestPartnerTypeEnum,
      },
    }), () => undefined);
  }

  /** Awaits a generated call, maps its result, and turns any failure into an ApiProblem. */
  private async call<T, R>(request: Observable<T>, map: (value: T) => R): Promise<R> {
    try {
      return map(await firstValueFrom(request));
    } catch (e) {
      if (e instanceof HttpErrorResponse) {
        const body = e.error as { detail?: string; errors?: Record<string, string> } | null;
        if (body?.errors) {
          throw new ApiProblem(Object.entries(body.errors).map(([k, v]) => `${k}: ${v}`).join('; '), e.status);
        }
        throw new ApiProblem(body?.detail ?? (e.status ? `${e.status} ${e.statusText}` : 'Could not reach the API. Try again.'), e.status);
      }
      throw e;
    }
  }
}
