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
  PlanItineraryLegRequestModeEnum, PlanItineraryLegRequestUnitTypeEnum, PublishMilkRunRouteRequestDaysEnum,
  RailMilestoneRequestMilestoneEnum,
} from '../api';
import type { CircuitRun, CircuitRunStop } from '../api';
import type {
  CircuitRunCard, CircuitStopCard, CircuitStopStep, ItineraryPlanDraft, RoutePlanDraft,
} from './milk-itinerary-types';
import { GeneratedGatewayTrips } from './generated-gateway-trips';
import { blankAsAbsent, instant, instantOf, numberOrAbsent, text } from './generated-gateway-helpers';

function runOf(r: CircuitRun): CircuitRunCard {
  return {
    id: text(r.id), routeCode: text(r.routeCode), runDate: text(r.runDate), status: text(r.status), tripNo: text(r.tripNo),
    startedAt: instant(r.startedAt), completedAt: instant(r.completedAt), stopsVisited: text(r.stopsVisited),
    stopsSkipped: text(r.stopsSkipped), chargeAmount: text(r.chargeAmount), totalPickedUpKg: text(r.totalPickedUpKg),
  };
}

function stopOf(s: CircuitRunStop): CircuitStopCard {
  return {
    sequence: s.sequence ?? 0, terminalCode: text(s.terminalCode), status: text(s.status), arrivedAt: instant(s.arrivedAt),
    departedAt: instant(s.departedAt), pickedUpKg: text(s.pickedUpKg), skipReason: text(s.skipReason),
  };
}

/** Publishing milk-run routes and moving their runs, planning itineraries and recording rail milestones. */
export abstract class GeneratedGatewayMilk extends GeneratedGatewayTrips {
  publishMilkRoute(d: RoutePlanDraft): Promise<string> {
    return this.call(this.milkRuns.publishMilkRunRoute({
      publishMilkRunRouteRequest: {
        code: d.code.trim(), name: blankAsAbsent(d.name), plantTerminalId: d.plantTerminalId.trim(),
        effectiveFrom: d.effectiveFrom, days: d.days as PublishMilkRunRouteRequestDaysEnum[],
        departureTime: d.departureTime, timeZone: blankAsAbsent(d.timeZone), runRate: Number(d.runRate),
        perStopFee: Number(d.perStopFee), currency: blankAsAbsent(d.currency),
        stops: d.stops.map((s) => ({
          terminalId: s.terminalId.trim(), windowOpenMin: Number(s.windowOpenMin),
          windowCloseMin: Number(s.windowCloseMin), plannedDwellMin: Number(s.plannedDwellMin),
        })),
      },
    }), (r) => text(r.id));
  }

  generateCircuitRuns(fromDate: string, days: string): Promise<number> {
    return this.call(this.milkRuns.generateCircuitRuns({
      generateCircuitRunsRequest: { fromDate: blankAsAbsent(fromDate), days: numberOrAbsent(days) },
    }), (r) => r.generated ?? 0);
  }

  circuitRunCards(routeCode: string): Promise<CircuitRunCard[]> {
    return this.call(this.milkRuns.listCircuitRuns({ routeCode: blankAsAbsent(routeCode), limit: 50 }),
      (s) => s.items.map(runOf));
  }

  circuitRunCard(id: string): Promise<CircuitRunCard> {
    return this.call(this.milkRuns.getCircuitRun({ id: id.trim() }), runOf);
  }

  circuitRunStops(id: string): Promise<CircuitStopCard[]> {
    return this.call(this.milkRuns.getCircuitRunStops({ id }), (s) => [...s].map(stopOf)
      .sort((a, b) => a.sequence - b.sequence));
  }

  assignCircuitTrip(runId: string, tripId: string): Promise<void> {
    return this.call(this.milkRuns.assignCircuitTrip({
      id: runId, assignCircuitTripRequest: { tripId: tripId.trim() },
    }), () => undefined);
  }

  recordCircuitStop(runId: string, sequence: number, step: CircuitStopStep, value: string): Promise<void> {
    const id = runId;
    const milestoneRequest = {};
    switch (step) {
      case 'arrival': return this.call(this.milkRuns.recordCircuitStopArrival({ id, sequence, milestoneRequest }), () => undefined);
      case 'departure': return this.call(this.milkRuns.recordCircuitStopDeparture({ id, sequence, milestoneRequest }), () => undefined);
      case 'pickup': return this.call(this.milkRuns.recordCircuitStopPickup({
        id, sequence, circuitPickupRequest: { quantityKg: Number(value) } }), () => undefined);
      case 'skip': return this.call(this.milkRuns.skipCircuitStop({
        id, sequence, circuitSkipRequest: { reason: value.trim() } }), () => undefined);
    }
  }

  completeCircuitRun(runId: string): Promise<void> {
    return this.call(this.milkRuns.completeCircuitRun({ id: runId, milestoneRequest: {} }), () => undefined);
  }

  planItinerary(d: ItineraryPlanDraft): Promise<string> {
    return this.call(this.multimodal.planItinerary({
      planItineraryRequest: {
        shipmentRef: d.shipmentRef.trim(), currency: blankAsAbsent(d.currency),
        legs: d.legs.map((l) => ({
          mode: l.mode as PlanItineraryLegRequestModeEnum, fromTerminalId: l.fromTerminalId.trim(),
          toTerminalId: l.toTerminalId.trim(), plannedDepartureAt: instantOf(l.plannedDepartureAt),
          plannedArrivalAt: instantOf(l.plannedArrivalAt), carrierPartnerId: l.carrierPartnerId.trim(),
          tripId: blankAsAbsent(l.tripId), unitType: l.unitType ? l.unitType as PlanItineraryLegRequestUnitTypeEnum : undefined,
          unitCount: numberOrAbsent(l.unitCount), bookingRef: blankAsAbsent(l.bookingRef), trainNo: blankAsAbsent(l.trainNo),
          costAmount: numberOrAbsent(l.costAmount),
        })),
      },
    }), (r) => text(r.id));
  }

  recordRailMilestone(itineraryId: string, seq: number, milestone: string): Promise<void> {
    return this.call(this.multimodal.recordRailMilestone({
      id: itineraryId, seq, railMilestoneRequest: { milestone: milestone as RailMilestoneRequestMilestoneEnum },
    }), () => undefined);
  }
}
