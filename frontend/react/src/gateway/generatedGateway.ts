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
  AllocationRulesApi, AuthenticationApi, ControlTowerApi, Configuration, CustomFieldsApi, DeliveryAndClaimsApi, DriversApi,
  OrdersApi,
  MilkRunsApi, MultimodalApi, PartnersApi, PlanningApi, PublicTrackingApi, ResponseError, SettlementApi, TerminalsApi, SourcingApi, TelematicsApi, TrackingLinksApi, TripsApi, VehiclesApi,
  type ClaimStatus as ApiClaimStatus, type ClaimType as ApiClaimType, type CustomFieldDefinition,
  type CustomFieldEntityType, type CustomFieldType,
  type RegisterPartnerRequestPartnerTypeEnum,
  type AllocationRule, type AllocationRuleAction, type AllocationRuleRequest,
  type AllocationRuleRequestMaterialClassEnum, type ListPartnersPartnerTypeEnum, type Terminal,
} from '../api';
import { API_URL } from '../config';
import { DemoDataApi } from '../api';
import type { DemoResetOutcome } from './lmsGateway';
import {
  ApiProblem, type AllocationRuleView, type AllocationSummary, type Choice, type ClaimDraft, type ClaimMove, type ControlTowerSummary, type CustomFieldKind, type CustomValues, type Credentials,
  type DeliveryProofView, type FieldChanges, type FieldDefinition, type FieldDraft, type FieldType, type LmsGateway,
  type IssuedTrackingLink, type OtpIssued, type PartnerDraft, type ProofDraft, type PublicTrackingView,
  type ResourceKey, type Row, type RuleAction, type RuleDraft, type RuleEvaluation, type SignedIn,
  type GeoPoint, type MapFeature, type Mapped, type MappedDetail, type TrackingLinkView, type TripRoute,
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

function ruleOf(r: AllocationRule): AllocationRuleView {
  return {
    id: text(r.id), name: text(r.name), priority: r.priority ?? 0, active: r.active ?? true,
    originTerminalId: text(r.originTerminalId), destinationTerminalId: text(r.destinationTerminalId),
    minWeightKg: text(r.minWeightKg), maxWeightKg: text(r.maxWeightKg),
    materialClass: text(r.materialClass), action: (r.action ?? 'PREFER') as RuleAction,
    vendors: (r.vendors ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
  };
}

/** The contract's request: a condition left out is sent as absent, not as an empty string. */
function ruleRequest(draft: RuleDraft): AllocationRuleRequest {
  return {
    name: draft.name, priority: draft.priority, active: draft.active,
    originTerminalId: draft.originTerminalId || undefined,
    destinationTerminalId: draft.destinationTerminalId || undefined,
    minWeightKg: draft.minWeightKg, maxWeightKg: draft.maxWeightKg,
    materialClass: (draft.materialClass || undefined) as AllocationRuleRequestMaterialClassEnum | undefined,
    action: draft.action as unknown as AllocationRuleAction,
    vendorPartnerIds: draft.vendorIds,
  };
}

/** A terminal as the map draws it: its polygon when it has one, else its centre and radius. */
function terminalFeature(t: Terminal, kind: 'site' | 'stop' | 'plant' = 'site'): MapFeature | null {
  const label = `${text(t.code)} · ${text(t.name)}`;
  const ring = polygonRing(t.polygon);
  if (ring && kind === 'site') {
    return { id: text(t.id), kind: 'area', label, points: ring };
  }
  if (t.centreLat === undefined || t.centreLat === null || t.centreLon === undefined || t.centreLon === null) {
    const centre = ring ? centroid(ring) : null;
    return centre ? { id: text(t.id), kind, label, points: [centre] } : null;
  }
  return {
    id: text(t.id), kind, label, points: [{ lat: t.centreLat, lon: t.centreLon }],
    radiusM: kind === 'site' && t.radiusM ? t.radiusM : undefined,
  };
}

/** The outer ring of a GeoJSON polygon, as map points; null when there is none. */
function polygonRing(geoJson: string | null | undefined): GeoPoint[] | null {
  if (!geoJson) {
    return null;
  }
  try {
    const shape = JSON.parse(geoJson) as { coordinates?: number[][][] };
    const ring = shape.coordinates?.[0];
    return ring && ring.length >= 3 ? ring.map(([lon, lat]) => ({ lat, lon })) : null;
  } catch {
    return null;
  }
}

function centroid(points: GeoPoint[]): GeoPoint {
  const lat = points.reduce((sum, p) => sum + p.lat, 0) / points.length;
  const lon = points.reduce((sum, p) => sum + p.lon, 0) / points.length;
  return { lat, lon };
}

/** A point on a terminal for drawing a line to it: its centre, or the middle of its polygon. */
function anchorOf(t: Terminal | undefined): GeoPoint | null {
  if (!t) {
    return null;
  }
  if (t.centreLat !== undefined && t.centreLat !== null && t.centreLon !== undefined && t.centreLon !== null) {
    return { lat: t.centreLat, lon: t.centreLon };
  }
  const ring = polygonRing(t.polygon);
  return ring ? centroid(ring) : null;
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
  const telematics = new TelematicsApi(config);
  const multimodal = new MultimodalApi(config);
  const milkRuns = new MilkRunsApi(config);
  const delivery = new DeliveryAndClaimsApi(config);
  const links = new TrackingLinksApi(config);
  const rules = new AllocationRulesApi(config);
  const sourcing = new SourcingApi(config);
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

    allocationRules: (): Promise<AllocationRuleView[]> =>
      guard(async () => (await rules.listAllocationRules({ limit: 200 })).items.map(ruleOf)),

    createAllocationRule: (draft: RuleDraft) =>
      guard(async () => (await rules.createAllocationRule({ allocationRuleRequest: ruleRequest(draft) })).id ?? ''),

    reviseAllocationRule: (id: string, draft: RuleDraft) =>
      guard(async () => {
        await rules.reviseAllocationRule({ id, allocationRuleRequest: ruleRequest(draft) });
      }),

    evaluateAllocation: (loadId: string): Promise<RuleEvaluation> =>
      guard(async () => {
        const e = await rules.evaluateAllocationRules({ allocationEvaluationRequest: { loadId } });
        return {
          loadNo: text(e.loadNo), ruleName: text(e.matchedRuleName),
          action: (e.matchedRuleId ? text(e.action) : '') as RuleAction | '',
          nextVendor: text(e.nextVendor?.vendorName),
          candidates: (e.candidateOrder ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
        };
      }),

    allocation: (id: string): Promise<AllocationSummary> =>
      guard(async () => {
        const a = await sourcing.getAllocation({ id });
        return {
          id: text(a.id), loadNo: text(a.loadNo), status: text(a.status),
          appliedRuleName: text(a.appliedRuleName),
          appliedRuleAction: (a.appliedRuleId ? text(a.appliedRuleAction) : '') as RuleAction | '',
        };
      }),

    activeVendors: (): Promise<Choice[]> =>
      guard(async () => (await partners.listPartners({
        partnerType: 'VENDOR' as unknown as ListPartnersPartnerTypeEnum, status: 'ACTIVE', limit: 200,
      })).items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` }))),

    terminalChoices: (): Promise<Choice[]> =>
      guard(async () => (await terminals.listTerminals({ limit: 200 })).items.map((t) => ({
        id: text(t.id), label: `${text(t.code)} · ${text(t.name)}`,
      }))),

    networkMap: (): Promise<Mapped> =>
      guard(async () => {
        const [sites, positions] = await Promise.all([
          terminals.listTerminals({ limit: 200 }), telematics.listPositions(),
        ]);
        const rows = sites.items.map((t) => ({
          id: text(t.id), code: text(t.code), name: text(t.name), functionalCategory: text(t.functionalCategory),
          geofenceType: text(t.geofenceType),
        }));
        const features: MapFeature[] = sites.items.map((t) => terminalFeature(t)).filter((f): f is MapFeature => f !== null);
        for (const v of positions) {
          if (v.lat !== undefined && v.lon !== undefined) {
            features.push({
              id: text(v.vehicleId), kind: 'vehicle', label: `${text(v.registrationNo)}${v.tripNo ? ` · ${v.tripNo}` : ''}`,
              points: [{ lat: v.lat, lon: v.lon }],
            });
          }
        }
        return { rows, features };
      }),

    tripRoute: (tripId: string): Promise<TripRoute> =>
      guard(async () => {
        const trip = await trips.getTrip({ id: tripId });
        const [track, progress] = await Promise.all([
          telematics.getTrack({ tripId }),
          telematics.getProgress({ tripId }).catch(() => null),
        ]);
        const features: MapFeature[] = [];
        if (track.length >= 2) {
          features.push({
            id: `${tripId}-track`, kind: 'track', label: `Track of ${text(trip.tripNo)}`,
            points: track.map(([lon, lat]) => ({ lat, lon })),
          });
        }
        if (progress && progress.lat !== undefined && progress.lon !== undefined) {
          features.push({
            id: `${tripId}-position`, kind: 'position', label: `${text(trip.tripNo)} now`,
            points: [{ lat: progress.lat, lon: progress.lon }],
          });
        }
        return {
          tripNo: text(trip.tripNo), status: text(trip.status), originCode: text(trip.originTerminalCode),
          destinationCode: text(trip.destinationTerminalCode), lastFixAt: text(progress?.lastFixAt),
          completionPct: text(progress?.completionPct), features,
        };
      }),

    itineraries: () =>
      guard(async () => (await multimodal.listItineraries({ limit: 50 })).items.map((i) => ({
        id: text(i.id), itineraryNo: text(i.itineraryNo), shipmentRef: text(i.shipmentRef), status: text(i.status),
        legCount: text(i.legCount), totalCost: text(i.totalCost),
        connectionMissed: i.connectionMissed ? 'Missed' : '',
      }))),

    itinerary: (id: string): Promise<MappedDetail> =>
      guard(async () => {
        const i = await multimodal.getItinerary({ id });
        const legs = [...(i.legs ?? [])].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
        const ids = [...new Set(legs.flatMap((l) => [l.fromTerminalId, l.toTerminalId]).filter(Boolean) as string[])];
        const sites = new Map((await Promise.all(ids.map((t) => terminals.getTerminal({ id: t })))).map((t) => [t.id, t]));
        const features: MapFeature[] = [];
        for (const l of legs) {
          const from = anchorOf(sites.get(l.fromTerminalId ?? ''));
          const to = anchorOf(sites.get(l.toTerminalId ?? ''));
          if (from && to) {
            features.push({
              id: text(l.id), kind: l.mode === 'RAIL' ? 'leg-RAIL' : 'leg-ROAD',
              label: `Leg ${text(l.seq)}: ${text(l.fromTerminalCode)} → ${text(l.toTerminalCode)} (${text(l.mode)})`,
              points: [from, to],
            });
          }
        }
        for (const t of sites.values()) {
          const f = terminalFeature(t, 'stop');
          if (f) {
            features.push(f);
          }
        }
        return {
          header: {
            id: text(i.id), itineraryNo: text(i.itineraryNo), shipmentRef: text(i.shipmentRef), status: text(i.status),
            currency: text(i.currency), totalCost: text(i.totalCost), connectionMissed: i.connectionMissed ? 'Missed' : '',
          },
          parts: legs.map((l) => ({
            id: text(l.id), seq: text(l.seq), mode: text(l.mode), status: text(l.status),
            from: text(l.fromTerminalCode), to: text(l.toTerminalCode),
            plannedDepartureAt: text(l.plannedDepartureAt), plannedArrivalAt: text(l.plannedArrivalAt),
            carrier: text(l.carrierCode), reference: text(l.trainNo || l.bookingRef || l.tripNo),
            departedAt: text(l.departedAt), arrivedAt: text(l.arrivedAt),
          })),
          features,
        };
      }),

    milkRunRoutes: () =>
      guard(async () => (await milkRuns.listMilkRunRoutes({ limit: 50 })).items.map((r) => ({
        id: text(r.id), code: text(r.code), name: text(r.name), plantCode: text(r.plantCode),
        daysOfWeek: text(r.daysOfWeek), departureTime: text(r.departureTime), effectiveFrom: text(r.effectiveFrom),
      }))),

    milkRunRoute: (id: string): Promise<MappedDetail> =>
      guard(async () => {
        const [route, stops] = await Promise.all([
          milkRuns.getMilkRunRoute({ id }), milkRuns.getMilkRunRouteStops({ id }),
        ]);
        const ordered = [...stops].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
        const ids = [...new Set([route.plantTerminalId, ...ordered.map((s) => s.terminalId)].filter(Boolean) as string[])];
        const sites = new Map((await Promise.all(ids.map((t) => terminals.getTerminal({ id: t })))).map((t) => [t.id, t]));
        const features: MapFeature[] = [];
        const plant = sites.get(route.plantTerminalId ?? '');
        const plantFeature = plant ? terminalFeature(plant, 'plant') : null;
        if (plantFeature) {
          features.push(plantFeature);
        }
        const circuit: GeoPoint[] = [];
        const plantAnchor = anchorOf(plant);
        if (plantAnchor) {
          circuit.push(plantAnchor);
        }
        for (const s of ordered) {
          const t = sites.get(s.terminalId ?? '');
          const at = anchorOf(t);
          if (at) {
            circuit.push(at);
            features.push({
              id: `${text(s.terminalId)}-${text(s.sequence)}`, kind: 'stop',
              label: `Stop ${text(s.sequence)}: ${text(s.terminalCode)}`, points: [at],
            });
          }
        }
        if (plantAnchor && circuit.length > 1) {
          circuit.push(plantAnchor);
        }
        if (circuit.length >= 2) {
          features.push({ id: `${id}-circuit`, kind: 'leg-ROAD', label: `Circuit ${text(route.code)}`, points: circuit });
        }
        return {
          header: {
            id: text(route.id), code: text(route.code), name: text(route.name), plantCode: text(route.plantCode),
            daysOfWeek: text(route.daysOfWeek), departureTime: text(route.departureTime), timeZone: text(route.timeZone),
            runRate: text(route.runRate), perStopFee: text(route.perStopFee), currency: text(route.currency),
          },
          parts: ordered.map((s) => ({
            id: `${text(s.terminalId)}-${text(s.sequence)}`, sequence: text(s.sequence), terminalCode: text(s.terminalCode),
            terminalName: text(s.terminalName), windowOpenMin: text(s.windowOpenMin), windowCloseMin: text(s.windowCloseMin),
            plannedDwellMin: text(s.plannedDwellMin),
          })),
          features,
        };
      }),

    circuitRuns: (routeCode: string) =>
      guard(async () => (await milkRuns.listCircuitRuns({ routeCode, limit: 50 })).items.map((r) => ({
        id: text(r.id), runDate: text(r.runDate), status: text(r.status), tripNo: text(r.tripNo),
        stopsVisited: text(r.stopsVisited), stopsSkipped: text(r.stopsSkipped), chargeAmount: text(r.chargeAmount),
      }))),
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
