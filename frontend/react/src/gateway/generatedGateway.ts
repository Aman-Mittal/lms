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
  AuthenticationApi, ControlTowerApi, Configuration, OrdersApi, PartnersApi, PlanningApi,
  RegisterPartnerRequestPartnerTypeEnum, ResponseError, SettlementApi, TripsApi, VehiclesApi,
} from '../api';
import { API_URL } from '../config';
import {
  ApiProblem, type ControlTowerSummary, type Credentials, type LmsGateway, type PartnerDraft,
  type ResourceKey, type Row, type SignedIn,
} from './lmsGateway';

/** A display cell: the contract's optional fields become an empty string. */
const text = (v: unknown): string => (v === null || v === undefined ? '' : String(v));

/** The adapter: the only file in the React app that imports the generated client. */
export function createGeneratedGateway(getToken: () => string | null): LmsGateway {
  // The token is read per request (a function), so one gateway outlives sign-ins.
  const config = new Configuration({ basePath: API_URL, accessToken: () => getToken() ?? '' });
  const auth = new AuthenticationApi(config);
  const controlTower = new ControlTowerApi(config);
  const partners = new PartnersApi(config);
  const vehicles = new VehiclesApi(config);
  const orders = new OrdersApi(config);
  const loads = new PlanningApi(config);
  const trips = new TripsApi(config);
  const freightBills = new SettlementApi(config);

  const listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: async () => (await partners.listPartners({ limit: 50 })).items.map((p) => ({
      code: text(p.code), legalName: text(p.legalName), partnerType: text(p.partnerType), status: text(p.status) })),
    vehicles: async () => (await vehicles.listVehicles({ limit: 50 })).items.map((v) => ({
      registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType), status: text(v.status),
      payloadCapacityKg: text(v.payloadCapacityKg) })),
    orders: async () => (await orders.listOrders({ limit: 50 })).items.map((o) => ({
      orderNo: text(o.orderNo), status: text(o.status), customerName: text(o.customerName),
      originTerminalCode: text(o.originTerminalCode) })),
    loads: async () => (await loads.listLoads({ limit: 50 })).items.map((l) => ({
      loadNo: text(l.loadNo), status: text(l.status), vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) })),
    trips: async () => (await trips.listTrips({ limit: 50 })).items.map((t) => ({
      tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) })),
    'freight-bills': async () => (await freightBills.listFreightBills({ limit: 50 })).items.map((b) => ({
      billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) })),
  };

  return {
    signIn: (credentials: Credentials): Promise<SignedIn> =>
      guard(async () => {
        const r = await auth.login({ loginRequest: credentials });
        return { token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissionCount: r.permissions?.length ?? 0 };
      }),

    summary: (): Promise<ControlTowerSummary> =>
      guard(async () => {
        const s = await controlTower.summariseExceptions();
        return {
          openP1: s.openP1 ?? 0, openP2: s.openP2 ?? 0, openP3: s.openP3 ?? 0,
          unassigned: s.unassigned ?? 0, settlementHolds: s.settlementHolds ?? 0,
          openWithoutTrip: s.openWithoutTrip ?? 0,
        };
      }),

    list: (resource) => guard(listers[resource]),

    registerPartner: (orgUnitId: string, draft: PartnerDraft) =>
      guard(async () => {
        await partners.registerPartner({
          registerPartnerRequest: {
            orgUnitId, code: draft.code, legalName: draft.legalName,
            partnerType: draft.partnerType as RegisterPartnerRequestPartnerTypeEnum,
          },
        });
      }),
  };
}

/** Runs a call and turns whatever it throws into an ApiProblem. */
async function guard<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call();
  } catch (e) {
    if (e instanceof ResponseError) {
      const status = e.response.status;
      try {
        const body = (await e.response.clone().json()) as { detail?: string; errors?: Record<string, string> };
        if (body.errors) {
          throw new ApiProblem(Object.entries(body.errors).map(([k, v]) => `${k}: ${v}`).join('; '), status);
        }
        throw new ApiProblem(body.detail ?? `${status} ${e.response.statusText}`, status);
      } catch (inner) {
        if (inner instanceof ApiProblem) {
          throw inner;
        }
        throw new ApiProblem(`${status} ${e.response.statusText}`, status);
      }
    }
    throw new ApiProblem('Could not reach the API. Try again.');
  }
}
