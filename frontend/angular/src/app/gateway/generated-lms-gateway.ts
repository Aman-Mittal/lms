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
  AllocationRule, AllocationRuleAction, AllocationRuleRequest, AllocationRuleRequestMaterialClassEnum,
  AllocationRulesService, AuthenticationService, ControlTowerService, CustomFieldDefinition, CustomFieldEntityType,
  ClaimStatus as ApiClaimStatus, ClaimType as ApiClaimType, CustomFieldsService, CustomFieldType,
  DeliveryAndClaimsService, DriversService, EventDeadLetterService, LTLRatingService, MilkRunsService, MultimodalService, OrdersService, PartnersService,
  PlanningService, PublicTrackingService, RegisterPartnerRequestPartnerTypeEnum, SettlementService, SourcingService,
  TelematicsService, Terminal, TerminalsService, TrackingLinksService, TripsService, VehiclesService,
  ParkedEvent, ParkedEventStatus, PublishLtlRateCardRequestRateBasisEnum,
} from '../api';
import {
  AllocationRuleView, AllocationSummary, ApiProblem, Choice, ClaimDraft, ClaimMove, ControlTowerSummary, Credentials, CustomFieldKind, CustomValues,
  DeliveryProofView, FieldChanges, FieldDefinition, FieldDraft, FieldType, IssuedTrackingLink, LmsGateway,
  OtpIssued, PartnerDraft, ProofDraft, PublicTrackingView, ResourceKey, Row, RuleAction, RuleDraft, RuleEvaluation,
  GeoPoint, MapFeature, Mapped, MappedDetail, SignedIn, TrackingLinkView, TripRoute,
  ParkedEventDetail, ParkedFilter, RateCardDraft,
} from './lms-gateway';

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

/** A parked event as a list row. */
function parkedRow(e: ParkedEvent): Row {
  return {
    id: text(e.id), parkedNo: text(e.parkedNo), eventType: text(e.eventType), listenerId: text(e.listenerId),
    status: text(e.status), attempts: text(e.attempts), lastFailedAt: text(e.lastFailedAt),
    lastError: text(e.lastError), parkedAt: text(e.parkedAt), resolvedAt: text(e.resolvedAt),
    resolutionNote: text(e.resolutionNote),
  };
}

function definitionOf(d: CustomFieldDefinition): FieldDefinition {
  return {
    id: d.id ?? '',
    kind: d.entityType as unknown as CustomFieldKind,
    key: d.key ?? '',
    label: d.label ?? '',
    type: d.fieldType as unknown as FieldType,
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
    materialClass: text(r.materialClass), action: (text(r.action) || 'PREFER') as RuleAction,
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
    materialClass: (draft.materialClass || undefined) as unknown as AllocationRuleRequestMaterialClassEnum | undefined,
    action: draft.action as unknown as AllocationRuleAction,
    vendorPartnerIds: draft.vendorIds,
  };
}

/** The adapter: the only file in the Angular app (besides app config) that knows the generated client. */
@Injectable()
export class GeneratedLmsGateway extends LmsGateway {
  private readonly auth = inject(AuthenticationService);
  private readonly controlTower = inject(ControlTowerService);
  private readonly partners = inject(PartnersService);
  private readonly vehicles = inject(VehiclesService);
  private readonly drivers = inject(DriversService);
  private readonly terminals = inject(TerminalsService);
  private readonly orders = inject(OrdersService);
  private readonly loads = inject(PlanningService);
  private readonly trips = inject(TripsService);
  private readonly freightBills = inject(SettlementService);
  private readonly customFields = inject(CustomFieldsService);
  private readonly delivery = inject(DeliveryAndClaimsService);
  private readonly links = inject(TrackingLinksService);
  private readonly publicRead = inject(PublicTrackingService);
  private readonly rules = inject(AllocationRulesService);
  private readonly sourcing = inject(SourcingService);
  private readonly telematics = inject(TelematicsService);
  private readonly multimodal = inject(MultimodalService);
  private readonly milkRuns = inject(MilkRunsService);
  private readonly ltl = inject(LTLRatingService);
  private readonly deadLetters = inject(EventDeadLetterService);

  private readonly listers: Record<ResourceKey, () => Promise<Row[]>> = {
    partners: () => this.call(this.partners.listPartners({ limit: 50 }), (s) => s.items.map((p) => ({
      id: text(p.id), code: text(p.code), legalName: text(p.legalName),
      partnerType: text(p.partnerType), status: text(p.status) }))),
    vehicles: () => this.call(this.vehicles.listVehicles({ limit: 50 }), (s) => s.items.map((v) => ({
      id: text(v.id), registrationNo: text(v.registrationNo), vehicleType: text(v.vehicleType),
      status: text(v.status), payloadCapacityKg: text(v.payloadCapacityKg) }))),
    drivers: () => this.call(this.drivers.listDrivers({ limit: 50 }), (s) => s.items.map((d) => ({
      id: text(d.id), fullName: text(d.fullName), licenceNo: text(d.licenceNo),
      licenceExpiresOn: text(d.licenceExpiresOn), status: text(d.status) }))),
    terminals: () => this.call(this.terminals.listTerminals({ limit: 50 }), (s) => s.items.map((t) => ({
      id: text(t.id), code: text(t.code), name: text(t.name),
      functionalCategory: text(t.functionalCategory), dockCount: text(t.dockCount) }))),
    orders: () => this.call(this.orders.listOrders({ limit: 50 }), (s) => s.items.map((o) => ({
      id: text(o.id), orderNo: text(o.orderNo), status: text(o.status),
      customerName: text(o.customerName), originTerminalCode: text(o.originTerminalCode) }))),
    loads: () => this.call(this.loads.listLoads({ limit: 50 }), (s) => s.items.map((l) => ({
      id: text(l.id), loadNo: text(l.loadNo), status: text(l.status),
      vehicleRegistrationNo: text(l.vehicleRegistrationNo),
      weightUtilisationPct: text(l.weightUtilisationPct) }))),
    trips: () => this.call(this.trips.listTrips({ limit: 50 }), (s) => s.items.map((t) => ({
      id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), vendorName: text(t.vendorName),
      driverName: text(t.driverName) }))),
    'freight-bills': () => this.call(this.freightBills.listFreightBills({ limit: 50 }), (s) => s.items.map((b) => ({
      id: text(b.id), billNo: text(b.billNo), status: text(b.status), vendorName: text(b.vendorName),
      claimedAmount: text(b.claimedAmount), varianceAmount: text(b.varianceAmount) }))),
    exceptions: () => this.call(this.controlTower.listExceptions({ limit: 50 }), (s) => s.items.map((e) => ({
      id: text(e.id), ticketNo: text(e.ticketNo), severity: text(e.severity), status: text(e.status),
      category: text(e.category), description: text(e.description) }))),
    claims: () => this.call(this.delivery.listClaims({ limit: 50 }), (s) => s.items.map((c) => ({
      id: text(c.id), claimNo: text(c.claimNo), claimType: text(c.claimType), amount: text(c.amount),
      status: text(c.status), consignmentId: text(c.consignmentId),
      approvedAmount: text(c.approvedAmount), description: text(c.description),
      resolutionNote: text(c.resolutionNote) }))),
  };

  signIn(credentials: Credentials): Promise<SignedIn> {
    return this.call(this.auth.login({ loginRequest: credentials }), (r) => ({
      token: r.accessToken, orgUnitId: r.orgUnitId ?? '', permissions: r.permissions ?? [] }));
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

  listFields(kind?: CustomFieldKind): Promise<FieldDefinition[]> {
    return this.call(
      this.customFields.listCustomFieldDefinitions({
        entityType: kind as unknown as CustomFieldEntityType | undefined,
      }),
      (slice) => slice.items.map(definitionOf));
  }

  defineField(draft: FieldDraft): Promise<void> {
    return this.call(this.customFields.defineCustomField({
      defineCustomFieldRequest: {
        entityType: draft.kind as unknown as CustomFieldEntityType, key: draft.key,
        label: draft.label, fieldType: draft.type as unknown as CustomFieldType,
        required: draft.required, options: draft.options,
      },
    }), () => undefined);
  }

  reviseField(id: string, changes: FieldChanges): Promise<void> {
    return this.call(this.customFields.reviseCustomField({
      id,
      reviseCustomFieldRequest: {
        label: changes.label, required: changes.required, options: changes.options,
        active: changes.active,
      },
    }), () => undefined);
  }

  customValues(kind: CustomFieldKind, recordId: string): Promise<CustomValues> {
    return this.call(this.customFields.getCustomFieldValues({
      entityType: kind as unknown as CustomFieldEntityType, entityId: recordId,
    }), (v) => v.values as CustomValues);
  }

  saveCustomValues(kind: CustomFieldKind, recordId: string, values: CustomValues): Promise<CustomValues> {
    return this.call(this.customFields.setCustomFieldValues({
      entityType: kind as unknown as CustomFieldEntityType, entityId: recordId,
      customFieldValues: { values },
    }), (v) => v.values as CustomValues);
  }

  raiseClaim(draft: ClaimDraft): Promise<string> {
    return this.call(this.delivery.raiseClaim({
      raiseClaimRequest: {
        consignmentId: draft.consignmentId, claimType: draft.claimType as unknown as ApiClaimType,
        amount: draft.amount, description: draft.description,
      },
    }), (made) => made.id ?? '');
  }

  transitionClaim(id: string, move: ClaimMove): Promise<void> {
    return this.call(this.delivery.transitionClaim({
      id,
      transitionClaimRequest: {
        target: move.target as unknown as ApiClaimStatus, approvedAmount: move.approvedAmount,
        resolutionNote: move.resolutionNote,
      },
    }), () => undefined);
  }

  issueDeliveryOtp(consignmentId: string): Promise<OtpIssued> {
    return this.call(this.delivery.issueDeliveryOtp({ consignmentId }),
      (issued) => ({ expiresAt: text(issued.expiresAt), otp: issued.otp }));
  }

  recordDeliveryProof(consignmentId: string, draft: ProofDraft): Promise<void> {
    return this.call(this.delivery.recordDeliveryProof({
      consignmentId,
      recordDeliveryProofRequest: {
        otp: draft.otp, recipientName: draft.recipientName, photoRef: draft.photoRef || undefined,
      },
    }), () => undefined);
  }

  async deliveryProof(consignmentId: string): Promise<DeliveryProofView | null> {
    try {
      return await this.call(this.delivery.getDeliveryProof({ consignmentId }), (p) => ({
        consignmentId: p.consignmentId ?? consignmentId,
        status: (p.status as unknown as string) === 'CONFIRMED' ? 'CONFIRMED' as const : 'OTP_ISSUED' as const,
        recipientName: text(p.recipientName), photoRef: text(p.photoRef),
        deliveredAt: text(p.deliveredAt), otpExpiresAt: text(p.otpExpiresAt),
      }));
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  issueTrackingLink(consignmentId: string, expiresInDays?: number): Promise<IssuedTrackingLink> {
    return this.call(this.links.issueTrackingLink({
      consignmentId, issueTrackingLinkRequest: expiresInDays ? { expiresInDays } : undefined,
    }), (issued) => ({ id: issued.id, token: issued.token, expiresAt: text(issued.expiresAt) }));
  }

  trackingLinks(consignmentId: string): Promise<TrackingLinkView[]> {
    return this.call(this.links.listTrackingLinks({ consignmentId }), (list) => list.items.map((l) => ({
      id: text(l.id), createdAt: text(l.createdAt), expiresAt: text(l.expiresAt),
      revokedAt: text(l.revokedAt), status: ((l.status as unknown as string) ?? 'ACTIVE') as TrackingLinkView['status'],
    })));
  }

  revokeTrackingLink(id: string): Promise<void> {
    return this.call(this.links.revokeTrackingLink({ id }), () => undefined);
  }

  async publicTracking(token: string): Promise<PublicTrackingView | null> {
    try {
      return await this.call(this.publicRead.getPublicTracking({ token }), (t) => ({
        reference: text(t.reference), status: text(t.status), originName: text(t.originName),
        destinationName: text(t.destinationName), requestedDeliveryAt: text(t.requestedDeliveryAt),
        deliveredAt: text(t.deliveredAt), proofRecorded: t.proofRecorded ?? false,
        position: t.position
          ? { lat: text(t.position.lat), lon: text(t.position.lon), recordedAt: text(t.position.recordedAt) }
          : null,
      }));
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  allocationRules(): Promise<AllocationRuleView[]> {
    return this.call(this.rules.listAllocationRules({ limit: 200 }), (s) => s.items.map(ruleOf));
  }

  createAllocationRule(draft: RuleDraft): Promise<string> {
    return this.call(this.rules.createAllocationRule({ allocationRuleRequest: ruleRequest(draft) }), (made) => made.id ?? '');
  }

  reviseAllocationRule(id: string, draft: RuleDraft): Promise<void> {
    return this.call(this.rules.reviseAllocationRule({ id, allocationRuleRequest: ruleRequest(draft) }), () => undefined);
  }

  evaluateAllocation(loadId: string): Promise<RuleEvaluation> {
    return this.call(this.rules.evaluateAllocationRules({ allocationEvaluationRequest: { loadId } }), (e) => ({
      loadNo: text(e.loadNo), ruleName: text(e.matchedRuleName),
      action: (e.matchedRuleId ? text(e.action) : '') as RuleAction | '',
      nextVendor: text(e.nextVendor?.vendorName),
      candidates: (e.candidateOrder ?? []).map((v) => ({ id: text(v.vendorPartnerId), label: text(v.vendorName) })),
    }));
  }

  allocation(id: string): Promise<AllocationSummary> {
    return this.call(this.sourcing.getAllocation({ id }), (a) => ({
      id: text(a.id), loadNo: text(a.loadNo), status: text(a.status),
      appliedRuleName: text(a.appliedRuleName),
      appliedRuleAction: (a.appliedRuleId ? text(a.appliedRuleAction) : '') as RuleAction | '',
    }));
  }

  activeVendors(): Promise<Choice[]> {
    return this.call(this.partners.listPartners({ partnerType: 'VENDOR', status: 'ACTIVE', limit: 200 }),
      (s) => s.items.map((p) => ({ id: text(p.id), label: `${text(p.code)} · ${text(p.legalName)}` })));
  }

  terminalChoices(): Promise<Choice[]> {
    return this.call(this.terminals.listTerminals({ limit: 200 }),
      (s) => s.items.map((t) => ({ id: text(t.id), label: `${text(t.code)} · ${text(t.name)}` })));
  }

  async networkMap(): Promise<Mapped> {
    const [sites, positions] = await Promise.all([
      this.call(this.terminals.listTerminals({ limit: 200 }), (x) => x),
      this.call(this.telematics.listPositions(), (x) => x),
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
  }

  async tripRoute(tripId: string): Promise<TripRoute> {
    const trip = await this.call(this.trips.getTrip({ id: tripId }), (x) => x);
    const [track, progress] = await Promise.all([
      this.call(this.telematics.getTrack({ tripId }), (x) => x),
      this.call(this.telematics.getProgress({ tripId }), (x) => x).catch(() => null),
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
  }

  itineraries(): Promise<Row[]> {
    return this.call(this.multimodal.listItineraries({ limit: 50 }), (s) => s.items.map((i) => ({
      id: text(i.id), itineraryNo: text(i.itineraryNo), shipmentRef: text(i.shipmentRef), status: text(i.status),
      legCount: text(i.legCount), totalCost: text(i.totalCost), connectionMissed: i.connectionMissed ? 'Missed' : '',
    })));
  }

  async itinerary(id: string): Promise<MappedDetail> {
    const i = await this.call(this.multimodal.getItinerary({ id }), (x) => x);
    const legs = [...(i.legs ?? [])].sort((a, b) => (a.seq ?? 0) - (b.seq ?? 0));
    const sites = await this.terminalsById(legs.flatMap((l) => [l.fromTerminalId, l.toTerminalId]));
    const features: MapFeature[] = [];
    for (const l of legs) {
      const from = anchorOf(sites.get(l.fromTerminalId ?? ''));
      const to = anchorOf(sites.get(l.toTerminalId ?? ''));
      if (from && to) {
        features.push({
          id: text(l.id), kind: text(l.mode) === 'RAIL' ? 'leg-RAIL' : 'leg-ROAD',
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
  }

  milkRunRoutes(): Promise<Row[]> {
    return this.call(this.milkRuns.listMilkRunRoutes({ limit: 50 }), (s) => s.items.map((r) => ({
      id: text(r.id), code: text(r.code), name: text(r.name), plantCode: text(r.plantCode),
      daysOfWeek: text(r.daysOfWeek), departureTime: text(r.departureTime), effectiveFrom: text(r.effectiveFrom),
    })));
  }

  async milkRunRoute(id: string): Promise<MappedDetail> {
    const [route, stops] = await Promise.all([
      this.call(this.milkRuns.getMilkRunRoute({ id }), (x) => x),
      this.call(this.milkRuns.getMilkRunRouteStops({ id }), (x) => x),
    ]);
    const ordered = [...stops].sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0));
    const sites = await this.terminalsById([route.plantTerminalId, ...ordered.map((s) => s.terminalId)]);
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
      const at = anchorOf(sites.get(s.terminalId ?? ''));
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
  }

  circuitRuns(routeCode: string): Promise<Row[]> {
    return this.call(this.milkRuns.listCircuitRuns({ routeCode, limit: 50 }), (s) => s.items.map((r) => ({
      id: text(r.id), runDate: text(r.runDate), status: text(r.status), tripNo: text(r.tripNo),
      stopsVisited: text(r.stopsVisited), stopsSkipped: text(r.stopsSkipped), chargeAmount: text(r.chargeAmount),
    })));
  }

  ltlReceivables(): Promise<Row[]> {
    return this.call(this.ltl.listDocketReceivables({ limit: 50 }), (s) => s.items.map((r) => ({
      id: text(r.id), docketNo: text(r.docketNo), status: text(r.status), customerName: text(r.customerName),
      lane: `${text(r.originZone)} → ${text(r.destinationZone)}`, rateCardCode: text(r.rateCardCode),
      chargeableWeightKg: text(r.chargeableWeightKg), totalAmount: text(r.totalAmount), currency: text(r.currency),
    })));
  }

  assignLtlZone(terminalId: string, zoneCode: string, outOfDeliveryArea: boolean): Promise<void> {
    return this.call(this.ltl.assignLtlZone({ terminalId, assignLtlZoneRequest: { zoneCode, outOfDeliveryArea } }),
      () => undefined);
  }

  publishLtlRateCard(draft: RateCardDraft): Promise<string> {
    return this.call(this.ltl.publishLtlRateCard({
      publishLtlRateCardRequest: {
        code: draft.code, originZone: draft.originZone, destinationZone: draft.destinationZone,
        effectiveFrom: draft.effectiveFrom,
        rateBasis: draft.rateBasis as unknown as PublishLtlRateCardRequestRateBasisEnum, rateAmount: draft.rateAmount,
        kgPerCft: draft.kgPerCft, minimumChargeableWeightKg: draft.minimumChargeableWeightKg,
        docketCharge: draft.docketCharge,
      },
    }), (made) => made.id ?? '');
  }

  parkedEvents(filter: ParkedFilter): Promise<Row[]> {
    return this.call(this.deadLetters.listParkedEvents({
      status: filter.status as unknown as ParkedEventStatus | undefined, eventType: filter.eventType || undefined,
      listenerId: filter.listenerId || undefined, limit: 50,
    }), (s) => s.items.map(parkedRow));
  }

  parkedEvent(id: string): Promise<ParkedEventDetail> {
    return this.call(this.deadLetters.getParkedEvent({ id }),
      (d) => ({ summary: parkedRow(d.summary), payload: text(d.payload) }));
  }

  replayParkedEvent(id: string): Promise<{ outcome: string; attempts: number }> {
    return this.call(this.deadLetters.replayParkedEvent({ id }),
      (r) => ({ outcome: text(r.outcome), attempts: r.attempts ?? 0 }));
  }

  discardParkedEvent(id: string, reason: string): Promise<void> {
    return this.call(this.deadLetters.discardParkedEvent({ id, discardParkedEventRequest: { reason } }),
      () => undefined);
  }

  /** Each distinct terminal named, read once. */
  private async terminalsById(ids: Array<string | null | undefined>): Promise<Map<string, Terminal>> {
    const distinct = [...new Set(ids.filter((id): id is string => !!id))];
    const read = await Promise.all(distinct.map((id) => this.call(this.terminals.getTerminal({ id }), (t) => t)));
    return new Map(read.map((t) => [text(t.id), t]));
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
