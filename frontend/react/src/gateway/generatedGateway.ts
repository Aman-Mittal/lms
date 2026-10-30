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

import { DemoDataApi } from '../api';
import type { DemoResetOutcome } from './lmsGateway';
import {
  AuthenticationApi, ControlTowerApi, Configuration, CustomFieldsApi, DeliveryAndClaimsApi, DriversApi,
  OrdersApi,
  PartnersApi, PlanningApi, PublicTrackingApi, ResponseError, SettlementApi, TerminalsApi, TrackingLinksApi, TripsApi, VehiclesApi,
  type ClaimStatus as ApiClaimStatus, type ClaimType as ApiClaimType, type CustomFieldDefinition,
  type CustomFieldEntityType, type CustomFieldType,
  type RegisterPartnerRequestPartnerTypeEnum,
} from '../api';
import { API_URL } from '../config';
import {
  ApiProblem, type ClaimDraft, type ClaimMove, type ControlTowerSummary, type CustomFieldKind, type CustomValues, type Credentials,
  type DeliveryProofView, type FieldChanges, type FieldDefinition, type FieldDraft, type FieldType, type LmsGateway,
  type IssuedTrackingLink, type OtpIssued, type PartnerDraft, type ProofDraft, type PublicTrackingView,
  type ResourceKey, type Row, type SignedIn, type TrackingLinkView,
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
  const delivery = new DeliveryAndClaimsApi(config);
  const links = new TrackingLinksApi(config);
  // The public read is made with no credentials at all: a customer has none, and
  // a console left signed in must not send its token to a route that ignores it.
  const publicTracking = new PublicTrackingApi(new Configuration({ basePath: API_URL }));

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
    claims: async () => (await delivery.listClaims({ limit: 50 })).items.map((c) => ({
      id: text(c.id), claimNo: text(c.claimNo), claimType: text(c.claimType), amount: text(c.amount),
      status: text(c.status), consignmentId: text(c.consignmentId),
      approvedAmount: text(c.approvedAmount), description: text(c.description),
      resolutionNote: text(c.resolutionNote) })),
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

    resetDemoData: (confirm: string): Promise<DemoResetOutcome> =>
      guard(async () => {
        const r = await demo.resetDemoData({ demoResetRequest: { confirm } });
        return { tenantCode: r.tenantCode, rowsDeleted: r.rowsDeleted, signInAgain: r.signInAgain };
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

    raiseClaim: (draft: ClaimDraft) =>
      guard(async () => {
        const made = await delivery.raiseClaim({
          raiseClaimRequest: {
            consignmentId: draft.consignmentId, claimType: draft.claimType as ApiClaimType,
            amount: draft.amount, description: draft.description,
          },
        });
        return made.id ?? '';
      }),

    transitionClaim: (id: string, move: ClaimMove) =>
      guard(async () => {
        await delivery.transitionClaim({
          id,
          transitionClaimRequest: {
            target: move.target as ApiClaimStatus, approvedAmount: move.approvedAmount,
            resolutionNote: move.resolutionNote,
          },
        });
      }),

    issueDeliveryOtp: (consignmentId: string): Promise<OtpIssued> =>
      guard(async () => {
        const issued = await delivery.issueDeliveryOtp({ consignmentId });
        return { expiresAt: text(issued.expiresAt), otp: issued.otp };
      }),

    recordDeliveryProof: (consignmentId: string, draft: ProofDraft) =>
      guard(async () => {
        await delivery.recordDeliveryProof({
          consignmentId,
          recordDeliveryProofRequest: {
            otp: draft.otp, recipientName: draft.recipientName, photoRef: draft.photoRef || undefined,
          },
        });
      }),

    deliveryProof: (consignmentId: string): Promise<DeliveryProofView | null> =>
      guard(async () => {
        try {
          const p = await delivery.getDeliveryProof({ consignmentId });
          return {
            consignmentId: p.consignmentId ?? consignmentId,
            status: p.status === 'CONFIRMED' ? 'CONFIRMED' : 'OTP_ISSUED',
            recipientName: text(p.recipientName), photoRef: text(p.photoRef),
            deliveredAt: text(p.deliveredAt), otpExpiresAt: text(p.otpExpiresAt),
          };
        } catch (e) {
          if (e instanceof ResponseError && e.response.status === 404) {
            return null;
          }
          throw e;
        }
      }),

    issueTrackingLink: (consignmentId: string, expiresInDays?: number): Promise<IssuedTrackingLink> =>
      guard(async () => {
        const issued = await links.issueTrackingLink({
          consignmentId, issueTrackingLinkRequest: expiresInDays ? { expiresInDays } : undefined,
        });
        return { id: issued.id, token: issued.token, expiresAt: text(issued.expiresAt) };
      }),

    trackingLinks: (consignmentId: string): Promise<TrackingLinkView[]> =>
      guard(async () => (await links.listTrackingLinks({ consignmentId })).items.map((l) => ({
        id: text(l.id), createdAt: text(l.createdAt), expiresAt: text(l.expiresAt),
        revokedAt: text(l.revokedAt), status: (l.status ?? 'ACTIVE') as TrackingLinkView['status'],
      }))),

    revokeTrackingLink: (id: string) =>
      guard(async () => {
        await links.revokeTrackingLink({ id });
      }),

    publicTracking: (token: string): Promise<PublicTrackingView | null> =>
      guard(async () => {
        try {
          const t = await publicTracking.getPublicTracking({ token });
          return {
            reference: text(t.reference), status: text(t.status), originName: text(t.originName),
            destinationName: text(t.destinationName), requestedDeliveryAt: text(t.requestedDeliveryAt),
            deliveredAt: text(t.deliveredAt), proofRecorded: t.proofRecorded ?? false,
            position: t.position
              ? { lat: text(t.position.lat), lon: text(t.position.lon), recordedAt: text(t.position.recordedAt) }
              : null,
          };
        } catch (e) {
          if (e instanceof ResponseError && e.response.status === 404) {
            return null;
          }
          throw e;
        }
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
