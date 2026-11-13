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
import type {
  ListPartnersPartnerTypeEnum, ParkedEventStatus, PublishLtlRateCardRequestRateBasisEnum,
  ApprovalScope as ApiApprovalScope, ApprovalStatus as ApiApprovalStatus,
} from '../api';
import { syncTrack } from '../track-sync';
import type {
  ApprovalDetail, ApprovalFilter, ApprovalPending, AllocationRuleView, AllocationSummary, Choice, LmsGateway,
  RuleAction, RuleDraft, RuleEvaluation, GeoPoint, MapFeature, Mapped, MappedDetail, TripRoute, ParkedEventDetail,
  ParkedFilter, RateCardDraft,
} from './lmsGateway';
import {
  GatewayDeps, anchorOf, approvalRow, blankAsAbsent, guard, keyValues, parkedRow, ruleOf, ruleRequest,
  terminalFeature, text,
} from './gatewayShared';

/** The allocation rules, the network map, itineraries, milk runs, LTL, parked events and approvals. */
export function networkVerbs(d: GatewayDeps): Pick<LmsGateway,
  | 'allocationRules' | 'createAllocationRule' | 'reviseAllocationRule' | 'evaluateAllocation'
  | 'allocation' | 'activeVendors' | 'terminalChoices' | 'networkMap' | 'tripRoute' | 'itineraries'
  | 'itinerary' | 'milkRunRoutes' | 'milkRunRoute' | 'circuitRuns' | 'ltlReceivables' | 'assignLtlZone'
  | 'publishLtlRateCard' | 'parkedEvents' | 'parkedEvent' | 'replayParkedEvent' | 'approvals'
  | 'approval' | 'approveApproval' | 'rejectApproval' | 'cancelApproval' | 'requestProofReversal'
  | 'discardParkedEvent'> {
  const { partners, terminals, trips, telematics, multimodal, milkRuns, ltl, deadLetters, approvalsApi, delivery, rules, sourcing, cachedGet } = d;

  return {
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
          cachedGet('terminals:map', '/api/v1/terminals?limit=200', () => terminals.listTerminals({ limit: 200 })),
          telematics.listPositions(),
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
          // Cached points plus the new end of the track; long tracks are thinned in a worker before drawing.
          syncTrack(tripId, (afterSeq) => telematics.getTrack({ tripId, afterSeq }), () => telematics.getTrack({ tripId }))
            .then(async (points) => (await import('../display-worker')).simplifyTrack(points)),
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
        const sites = new Map((await Promise.all(ids.map((t) => cachedGet(`terminal:${t}`, `/api/v1/terminals/${t}`, () => terminals.getTerminal({ id: t }))))).map((t) => [t.id, t]));
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
        const sites = new Map((await Promise.all(ids.map((t) => cachedGet(`terminal:${t}`, `/api/v1/terminals/${t}`, () => terminals.getTerminal({ id: t }))))).map((t) => [t.id, t]));
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

    ltlReceivables: () =>
      guard(async () => (await ltl.listDocketReceivables({ limit: 50 })).items.map((r) => ({
        id: text(r.id), docketNo: text(r.docketNo), status: text(r.status), customerName: text(r.customerName),
        lane: `${text(r.originZone)} → ${text(r.destinationZone)}`, rateCardCode: text(r.rateCardCode),
        chargeableWeightKg: text(r.chargeableWeightKg), totalAmount: text(r.totalAmount), currency: text(r.currency),
      }))),

    assignLtlZone: (terminalId: string, zoneCode: string, outOfDeliveryArea: boolean) =>
      guard(async () => {
        await ltl.assignLtlZone({ terminalId, assignLtlZoneRequest: { zoneCode, outOfDeliveryArea } });
      }),

    publishLtlRateCard: (draft: RateCardDraft) =>
      guard(async () => (await ltl.publishLtlRateCard({
        publishLtlRateCardRequest: {
          code: draft.code, originZone: draft.originZone, destinationZone: draft.destinationZone,
          effectiveFrom: new Date(`${draft.effectiveFrom}T00:00:00Z`),
          rateBasis: draft.rateBasis as PublishLtlRateCardRequestRateBasisEnum, rateAmount: draft.rateAmount,
          kgPerCft: draft.kgPerCft, minimumChargeableWeightKg: draft.minimumChargeableWeightKg,
          docketCharge: draft.docketCharge,
        },
      })).id ?? ''),

    parkedEvents: (filter: ParkedFilter) =>
      guard(async () => (await deadLetters.listParkedEvents({
        status: filter.status as ParkedEventStatus | undefined, eventType: filter.eventType || undefined,
        listenerId: filter.listenerId || undefined, limit: 50,
      })).items.map(parkedRow)),

    parkedEvent: (id: string): Promise<ParkedEventDetail> =>
      guard(async () => {
        const d = await deadLetters.getParkedEvent({ id });
        return { summary: parkedRow(d.summary), payload: text(d.payload) };
      }),

    replayParkedEvent: (id: string) =>
      guard(async () => {
        const r = await deadLetters.replayParkedEvent({ id });
        return { outcome: text(r.outcome), attempts: r.attempts ?? 0 };
      }),

    approvals: (filter: ApprovalFilter) =>
      guard(async () => (await approvalsApi.listApprovals({
        status: filter.status as ApiApprovalStatus | undefined, scope: filter.scope as ApiApprovalScope | undefined,
        subjectType: filter.subjectType || undefined, subjectId: filter.subjectId || undefined, limit: 50,
      })).items.map(approvalRow)),

    approval: (id: string): Promise<ApprovalDetail> =>
      guard(async () => {
        const d = await approvalsApi.getApproval({ id });
        return {
          summary: approvalRow(d.approval), version: d.approval.version, open: d.approval.status === 'REQUESTED',
          mineToDecide: d.approval.mineToDecide, requestedByMe: d.approval.requestedByMe,
          payload: keyValues(d.approval.payload), policy: keyValues(d.policy).replace(/\n/g, '; '),
          trail: d.trail.map((t) => ({
            id: text(t.id), step: text(t.step), decision: text(t.decision), permission: text(t.permission),
            decidedBy: text(t.decidedBy) || 'platform', decidedAt: text(t.decidedAt), note: text(t.note),
          })),
        };
      }),

    approveApproval: (id: string, version: number, note: string) =>
      guard(async () => {
        await approvalsApi.approveApproval({ id, ifMatch: String(version), approveRequest: { note: blankAsAbsent(note) } });
      }),

    rejectApproval: (id: string, version: number, reason: string) =>
      guard(async () => {
        await approvalsApi.rejectApproval({ id, ifMatch: String(version), rejectApprovalRequest: { reason } });
      }),

    cancelApproval: (id: string, version: number, reason: string) =>
      guard(async () => {
        await approvalsApi.cancelApproval({
          id, ifMatch: String(version), cancelApprovalRequest: { reason: blankAsAbsent(reason) },
        });
      }),

    requestProofReversal: (consignmentId: string, reason: string): Promise<ApprovalPending> =>
      guard(async () => {
        const t = await delivery.requestProofReversal({ consignmentId, proofReversalRequest: { reason } });
        return { id: text(t?.id), status: text(t?.status) };
      }),

    discardParkedEvent: (id: string, reason: string) =>
      guard(async () => {
        await deadLetters.discardParkedEvent({ id, discardParkedEventRequest: { reason } });
      }),
  };
}
