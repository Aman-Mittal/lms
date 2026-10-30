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
  AuthenticationApi, ControlTowerApi, Configuration, CustomFieldsApi, DriversApi, OrdersApi,
  PartnersApi, PlanningApi, ResponseError, SettlementApi, TerminalsApi, TripsApi, VehiclesApi,
  type CustomFieldDefinition, type CustomFieldEntityType, type CustomFieldType,
  type RegisterPartnerRequestPartnerTypeEnum,
} from '../api';
import { API_URL } from '../config';
import {
  ApiProblem, type ControlTowerSummary, type CustomFieldKind, type CustomValues, type Credentials,
  type FieldChanges, type FieldDefinition, type FieldDraft, type FieldType, type LmsGateway,
  type PartnerDraft, type ResourceKey, type Row, type SignedIn,
} from './lmsGateway';

/** A display cell: the contract's optional fields become an empty string. */
function text(v: unknown): string {
  if (v === null || v === undefined) {
    return '';
  }
  if (v instanceof Date) {
    const iso = v.toISOString();
    return iso.endsWith('T00:00:00.000Z') ? iso.slice(0, 10) : iso.slice(0, 16).replace('T', ' ');
  }
  return String(v);
}

function definitionOf(d: CustomFieldDefinition): FieldDefinition {
  return {
    id: d.id ?? '',
    kind: d.entityType as CustomFieldKind,
    key: d.key ?? '',
    label: d.label ?? '',
    type: d.fieldType as FieldType,
    required: d.required ?? false,
    options: d.options ?? [],
    active: d.active ?? true,
  };
}

/** The adapter: the only file in the React app that imports the generated client. */
export function createGeneratedGateway(getToken: () => string | null): LmsGateway {
  // The token is read per request (a function), so one gateway outlives sign-ins.
  const config = new Configuration({ basePath: API_URL, accessToken: () => getToken() ?? '' });
  const auth = new AuthenticationApi(config);
  const controlTower = new ControlTowerApi(config);
  const partners = new PartnersApi(config);
  const vehicles = new VehiclesApi(config);
  const drivers = new DriversApi(config);
  const terminals = new TerminalsApi(config);
  const orders = new OrdersApi(config);
  const loads = new PlanningApi(config);
  const trips = new TripsApi(config);
  const freightBills = new SettlementApi(config);
  const customFields = new CustomFieldsApi(config);

  const listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: async () => (await partners.listPartners({ limit: 50 })).items.map((p) => ({
      id: text(p.id), code: text(p.code), legalName: text(p.legalName),
      partnerType: text(p.partnerType), status: text(p.status) })),
    vehicles: async () => (await vehicles.listVehicles({ limit: 50 })).items.map((v) => ({
      id: text(v.id), registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType),
      status: text(v.status), payloadCapacityKg: text(v.payloadCapacityKg) })),
    drivers: async () => (await drivers.listDrivers({ limit: 50 })).items.map((d) => ({
      id: text(d.id), fullName: text(d.fullName), licenceNo: text(d.licenceNo),
      licenceExpiresOn: text(d.licenceExpiresOn), status: text(d.status) })),
    terminals: async () => (await terminals.listTerminals({ limit: 50 })).items.map((t) => ({
      id: text(t.id), code: text(t.code), name: text(t.name),
      functionalCategory: text(t.functionalCategory), dockCount: text(t.dockCount) })),
    orders: async () => (await orders.listOrders({ limit: 50 })).items.map((o) => ({
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status),
      customerName: text(o.customerName), originTerminalCode: text(o.originTerminalCode) })),
    loads: async () => (await loads.listLoads({ limit: 50 })).items.map((l) => ({
      id: text(l.id), loadNo: text(l.loadNo), status: text(l.status),
      vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) })),
    trips: async () => (await trips.listTrips({ limit: 50 })).items.map((t) => ({
      id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) })),
    'freight-bills': async () => (await freightBills.listFreightBills({ limit: 50 })).items.map((b) => ({
      id: text(b.id), billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) })),
    exceptions: async () => (await controlTower.listExceptions({ limit: 50 })).items.map((e) => ({
      id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
      category: text(e.category), description: text(e.description) })),
  };

  return {
    signIn: (credentials: Credentials): Promise<SignedIn> =>
      guard(async () => {
        const r = await auth.login({ loginRequest: credentials });
        return { token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissions: r.permissions ?? [] };
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

    listFields: (kind?: CustomFieldKind) =>
      guard(async () => {
        const slice = await customFields.listCustomFieldDefinitions({
          entityType: kind as CustomFieldEntityType | undefined,
        });
        return slice.items.map(definitionOf);
      }),

    defineField: (draft: FieldDraft) =>
      guard(async () => {
        await customFields.defineCustomField({
          defineCustomFieldRequest: {
            entityType: draft.kind as CustomFieldEntityType, key: draft.key, label: draft.label,
            fieldType: draft.type as CustomFieldType, required: draft.required,
            options: draft.options,
          },
        });
      }),

    reviseField: (id: string, changes: FieldChanges) =>
      guard(async () => {
        await customFields.reviseCustomField({
          id,
          reviseCustomFieldRequest: {
            label: changes.label, required: changes.required, options: changes.options,
            active: changes.active,
          },
        });
      }),

    customValues: (kind: CustomFieldKind, recordId: string) =>
      guard(async () => {
        const got = await customFields.getCustomFieldValues({
          entityType: kind as CustomFieldEntityType, entityId: recordId,
        });
        return got.values as CustomValues;
      }),

    saveCustomValues: (kind: CustomFieldKind, recordId: string, values: CustomValues) =>
      guard(async () => {
        const saved = await customFields.setCustomFieldValues({
          entityType: kind as CustomFieldEntityType, entityId: recordId, customFieldValues: { values },
        });
        return saved.values as CustomValues;
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
