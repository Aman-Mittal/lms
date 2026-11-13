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
import { syncTrack } from '../track-sync';
import {
  ApprovalScope as ApiApprovalScope, ApprovalStatus as ApiApprovalStatus, GoodsCondition as ApiGoodsCondition,
  CustomFieldEntityType, ClaimStatus as ApiClaimStatus, ClaimType as ApiClaimType, CustomFieldType,
  RegisterPartnerRequestPartnerTypeEnum, ParkedEventStatus, PublishLtlRateCardRequestRateBasisEnum,
} from '../api';
import {
  type ApprovalDetail, type ApprovalFilter, type ApprovalPending, type DemoResetOutcome, AllocationRuleView,
  AllocationSummary, ApiProblem, Choice, ClaimDraft, ClaimMove, ConsignmentCard, ConsignmentParticularsDraft,
  ControlTowerSummary, Credentials, CustomFieldKind, CustomValues, DeliveryProofView, PodDocument, PodDocumentType,
  PodLineDraft, PodPaperDraft, PodSubmitDraft, PodTimings, FieldChanges, FieldDefinition, FieldDraft,
  IssuedTrackingLink, OtpIssued, PartnerDraft, ProofDraft, PublicTrackingView, ResourceKey, Row, RuleAction,
  RuleDraft, RuleEvaluation, GeoPoint, MapFeature, Mapped, MappedDetail, SignedIn, SplitPart, SplitPartDraft,
  TrackingLinkView, TripRoute, ParkedEventDetail, ParkedFilter, RateCardDraft,
} from './lms-gateway';
import { GeneratedGatewayBase } from './generated-gateway-base';
import {
  anchorOf, approvalRow, blankAsAbsent, consignmentCard, definitionOf, instant, instantOf, keyValues, parkedRow,
  particularsRequest, proofStatus, quantityOrAbsent, ruleOf, ruleRequest, terminalFeature, text,
} from './generated-gateway-helpers';

/** The sign-in, claims, proof of delivery, consignments, tracking, planning, the network map, parked events and approvals. */
export abstract class GeneratedGatewayDelivery extends GeneratedGatewayBase {
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

  resetDemoData(confirm: string): Promise<DemoResetOutcome> {
    return this.call(this.demo.resetDemoData({ demoResetRequest: { confirm } }), (r) => ({
      tenantCode: r.tenantCode, rowsDeleted: r.rowsDeleted, signInAgain: r.signInAgain }));
  }

  list(resource: ResourceKey): Promise<Row[]> {
    return this.listers[resource]();
  }

  claims(consignmentId?: string): Promise<Row[]> {
    return this.call(this.delivery.listClaims({ consignmentId: consignmentId || undefined, limit: 50 }),
      (s) => s.items.map((c) => ({
        id: text(c.id), claimNo: text(c.claimNo), claimType: text(c.claimType), amount: text(c.amount),
        status: text(c.status), consignmentId: text(c.consignmentId),
        approvedAmount: text(c.approvedAmount), description: text(c.description),
        resolutionNote: text(c.resolutionNote) })));
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
        goodsCondition: draft.goodsCondition as unknown as ApiGoodsCondition | undefined, remark: draft.remark || undefined,
      },
    }), () => undefined);
  }

  async deliveryProof(consignmentId: string): Promise<DeliveryProofView | null> {
    try {
      return await this.call(this.delivery.getDeliveryProof({ consignmentId }), (p) => ({
        consignmentId: p.consignmentId ?? consignmentId,
        status: proofStatus(p.status as unknown as string),
        recipientName: text(p.recipientName), photoRef: text(p.photoRef),
        deliveredAt: text(p.deliveredAt), otpExpiresAt: text(p.otpExpiresAt),
        goodsCondition: text(p.goodsCondition) || 'CLEAN', remark: text(p.remark),
        paper: !!p.paper, keyedBy: text(p.keyedBy), reportedAt: instant(p.reportedAt as unknown as string),
        unloadingStartedAt: instant(p.unloadingStartedAt as unknown as string),
        unloadingEndedAt: instant(p.unloadingEndedAt as unknown as string),
        lines: (p.lines ?? []).map((l) => ({
          orderLineId: l.orderLineId, lineNo: l.lineNo, materialDescription: text(l.materialDescription),
          uom: text(l.uom), grossQty: text(l.grossQty), receivedQty: text(l.receivedQty), shortQty: text(l.shortQty),
          damagedQty: text(l.damagedQty), excessQty: text(l.excessQty),
        })),
        documents: (p.documents ?? []).map((d) => ({ type: d.type as unknown as PodDocumentType, reference: d.reference })),
      }));
    } catch (e) {
      if (e instanceof ApiProblem && e.status === 404) {
        return null;
      }
      throw e;
    }
  }

  ensureProof(consignmentId: string): Promise<void> {
    return this.call(this.delivery.ensureProofOfDelivery({ consignmentId }), () => undefined);
  }

  feedProofLines(consignmentId: string, lines: PodLineDraft[]): Promise<void> {
    return this.call(this.delivery.feedProofLines({
      consignmentId,
      feedProofLinesRequest: {
        lines: lines.map((l) => ({
          orderLineId: l.orderLineId, receivedQty: quantityOrAbsent(l.receivedQty), shortQty: quantityOrAbsent(l.shortQty),
          damagedQty: quantityOrAbsent(l.damagedQty), excessQty: quantityOrAbsent(l.excessQty),
        })),
      },
    }), () => undefined);
  }

  setProofDocuments(consignmentId: string, documents: PodDocument[]): Promise<void> {
    return this.call(this.delivery.setProofDocuments({
      consignmentId,
      setProofDocumentsRequest: { documents: documents.map((d) => ({ type: d.type as never, reference: d.reference })) },
    }), () => undefined);
  }

  setProofTimings(consignmentId: string, t: PodTimings): Promise<void> {
    return this.call(this.delivery.setProofTimings({
      consignmentId,
      setProofTimingsRequest: {
        reportedAt: t.reportedAt ? instantOf(t.reportedAt) : undefined,
        unloadingStartedAt: t.unloadingStartedAt ? instantOf(t.unloadingStartedAt) : undefined,
        unloadingEndedAt: t.unloadingEndedAt ? instantOf(t.unloadingEndedAt) : undefined,
      },
    }), () => undefined);
  }

  submitProof(consignmentId: string, d: PodSubmitDraft): Promise<void> {
    return this.call(this.delivery.submitProofOfDelivery({
      consignmentId,
      submitProofRequest: {
        otp: d.otp, recipientName: d.recipientName,
        goodsCondition: d.goodsCondition as unknown as ApiGoodsCondition | undefined, remark: d.remark || undefined,
      },
    }), () => undefined);
  }

  keyPaperProof(consignmentId: string, d: PodPaperDraft): Promise<void> {
    return this.call(this.delivery.keyPaperProof({
      consignmentId,
      keyPaperProofRequest: {
        recipientName: d.recipientName,
        goodsCondition: d.goodsCondition as unknown as ApiGoodsCondition | undefined, remark: d.remark || undefined,
      },
    }), () => undefined);
  }

  markProofNotReceived(consignmentId: string, remark: string): Promise<void> {
    return this.call(this.delivery.markProofNotReceived({ consignmentId, notReceivedRequest: { remark } }),
      () => undefined);
  }

  consignmentByRef(trackingRef: string): Promise<ConsignmentCard> {
    return this.call(this.loads.trackConsignment({ trackingRef }), consignmentCard);
  }

  consignment(id: string): Promise<ConsignmentCard> {
    return this.call(this.loads.getConsignment({ id }), consignmentCard);
  }

  async consignmentParts(id: string): Promise<ConsignmentCard[]> {
    // At most 500 parts, so at most three pages: read them all.
    const parts: ConsignmentCard[] = [];
    let cursor: string | undefined;
    do {
      const page = await this.call(this.loads.listConsignmentParts({ id, cursor, limit: 200 }), (p) => p);
      parts.push(...page.items.map(consignmentCard));
      cursor = page.hasMore ? page.nextCursor ?? undefined : undefined;
    } while (cursor);
    return parts;
  }

  splitConsignment(id: string, parts: SplitPartDraft[]): Promise<SplitPart[]> {
    return this.call(this.loads.splitConsignment({
      id,
      splitConsignmentRequest: {
        parts: parts.map((p) => ({ deadWeightKg: Number(p.deadWeightKg), volumeM3: Number(p.volumeM3) })),
      },
    }), (made) => made.map((p) => ({ id: p.id, trackingRef: p.trackingRef })));
  }

  amendConsignment(id: string, version: string, particulars: ConsignmentParticularsDraft): Promise<void> {
    return this.call(this.loads.amendConsignment({
      id, ifMatch: version, consignmentAmendmentRequest: particularsRequest(particulars),
    }), () => undefined);
  }

  cancelConsignment(id: string, reason: string): Promise<void> {
    return this.call(this.loads.cancelConsignment({
      id, cancelConsignmentRequest: reason.trim() ? { reason: reason.trim() } : undefined,
    }), () => undefined);
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
        partsTotal: t.partsTotal ?? null, partsDelivered: t.partsDelivered ?? null,
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
      this.sitesForMap(),
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
      // Cached points plus the new end of the track; long tracks are thinned in a worker before drawing.
      syncTrack(tripId,
        (afterSeq) => this.call(this.telematics.getTrack({ tripId, afterSeq }), (x) => x),
        () => this.call(this.telematics.getTrack({ tripId }), (x) => x))
        .then(async (points) => (await import('../display-worker')).simplifyTrack(points)),
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

  approvals(filter: ApprovalFilter): Promise<Row[]> {
    return this.call(this.approvalsApi.listApprovals({
      status: filter.status as unknown as ApiApprovalStatus | undefined,
      scope: filter.scope as unknown as ApiApprovalScope | undefined,
      subjectType: filter.subjectType || undefined, subjectId: filter.subjectId || undefined, limit: 50,
    }), (s) => s.items.map(approvalRow));
  }

  approval(id: string): Promise<ApprovalDetail> {
    return this.call(this.approvalsApi.getApproval({ id }), (d) => ({
      summary: approvalRow(d.approval), version: d.approval.version,
      open: (d.approval.status as unknown as string) === 'REQUESTED',
      mineToDecide: d.approval.mineToDecide, requestedByMe: d.approval.requestedByMe,
      payload: keyValues(d.approval.payload), policy: keyValues(d.policy).replace(/\n/g, '; '),
      trail: d.trail.map((t) => ({
        id: text(t.id), step: text(t.step), decision: text(t.decision), permission: text(t.permission),
        decidedBy: text(t.decidedBy) || 'platform', decidedAt: instant(t.decidedAt), note: text(t.note),
      })),
    }));
  }

  approveApproval(id: string, version: number, note: string): Promise<void> {
    return this.call(this.approvalsApi.approveApproval({
      id, ifMatch: String(version), approveRequest: { note: blankAsAbsent(note) },
    }), () => undefined);
  }

  rejectApproval(id: string, version: number, reason: string): Promise<void> {
    return this.call(this.approvalsApi.rejectApproval({
      id, ifMatch: String(version), rejectApprovalRequest: { reason },
    }), () => undefined);
  }

  cancelApproval(id: string, version: number, reason: string): Promise<void> {
    return this.call(this.approvalsApi.cancelApproval({
      id, ifMatch: String(version), cancelApprovalRequest: { reason: blankAsAbsent(reason) },
    }), () => undefined);
  }

  requestProofReversal(consignmentId: string, reason: string): Promise<ApprovalPending> {
    return this.call(this.delivery.requestProofReversal({ consignmentId, proofReversalRequest: { reason } }),
      (t) => ({ id: text(t?.id), status: text(t?.status) }));
  }

  discardParkedEvent(id: string, reason: string): Promise<void> {
    return this.call(this.deadLetters.discardParkedEvent({ id, discardParkedEventRequest: { reason } }),
      () => undefined);
  }
}
