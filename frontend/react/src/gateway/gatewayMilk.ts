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
  MilkRunsApi, MultimodalApi, type CircuitRun, type CircuitRunStop, type Configuration,
  type PlanItineraryLegRequestModeEnum, type PlanItineraryLegRequestUnitTypeEnum,
  type PublishMilkRunRouteRequestDaysEnum, type RailMilestoneRequestMilestoneEnum,
} from '../api';
import type { LmsGateway } from './lmsGateway';
import type { CircuitRunCard, CircuitStopCard } from './milkItineraryTypes';
import { blankAsAbsent, day, decimalOrAbsent, guard, text } from './gatewayShared';

type MilkVerbs = Pick<LmsGateway, 'publishMilkRoute' | 'generateCircuitRuns' | 'circuitRunCards' | 'circuitRunCard'
  | 'circuitRunStops' | 'assignCircuitTrip' | 'recordCircuitStop' | 'completeCircuitRun' | 'planItinerary'
  | 'recordRailMilestone'>;

function runOf(r: CircuitRun): CircuitRunCard {
  return {
    id: text(r.id), routeCode: text(r.routeCode), runDate: text(r.runDate), status: text(r.status), tripNo: text(r.tripNo),
    startedAt: text(r.startedAt), completedAt: text(r.completedAt), stopsVisited: text(r.stopsVisited),
    stopsSkipped: text(r.stopsSkipped), chargeAmount: text(r.chargeAmount), totalPickedUpKg: text(r.totalPickedUpKg),
  };
}

function stopOf(s: CircuitRunStop): CircuitStopCard {
  return {
    sequence: s.sequence ?? 0, terminalCode: text(s.terminalCode), status: text(s.status), arrivedAt: text(s.arrivedAt),
    departedAt: text(s.departedAt), pickedUpKg: text(s.pickedUpKg), skipReason: text(s.skipReason),
  };
}

/** Publishing milk-run routes and moving their runs, planning itineraries and recording rail milestones. */
export function milkVerbs(config: Configuration): MilkVerbs {
  const milk = new MilkRunsApi(config);
  const multimodal = new MultimodalApi(config);

  return {
    publishMilkRoute: (d) =>
      guard(async () => text((await milk.publishMilkRunRoute({
        publishMilkRunRouteRequest: {
          code: d.code.trim(), name: blankAsAbsent(d.name), plantTerminalId: d.plantTerminalId.trim(),
          effectiveFrom: day(d.effectiveFrom), days: d.days as PublishMilkRunRouteRequestDaysEnum[],
          departureTime: d.departureTime, timeZone: blankAsAbsent(d.timeZone), runRate: Number(d.runRate),
          perStopFee: Number(d.perStopFee), currency: blankAsAbsent(d.currency),
          stops: d.stops.map((s) => ({
            terminalId: s.terminalId.trim(), windowOpenMin: Number(s.windowOpenMin),
            windowCloseMin: Number(s.windowCloseMin), plannedDwellMin: Number(s.plannedDwellMin),
          })),
        },
      })).id)),

    generateCircuitRuns: (fromDate, days) =>
      guard(async () => (await milk.generateCircuitRuns({
        generateCircuitRunsRequest: { fromDate: fromDate.trim() ? day(fromDate.trim()) : undefined, days: decimalOrAbsent(days) },
      })).generated ?? 0),

    circuitRunCards: (routeCode) =>
      guard(async () => (await milk.listCircuitRuns({ routeCode: blankAsAbsent(routeCode), limit: 50 })).items.map(runOf)),

    circuitRunCard: (id) => guard(async () => runOf(await milk.getCircuitRun({ id: id.trim() }))),

    circuitRunStops: (id) =>
      guard(async () => (await milk.getCircuitRunStops({ id })).map(stopOf).sort((a, b) => a.sequence - b.sequence)),

    assignCircuitTrip: (runId, tripId) =>
      guard(async () => { await milk.assignCircuitTrip({ id: runId, assignCircuitTripRequest: { tripId: tripId.trim() } }); }),

    recordCircuitStop: (runId, sequence, step, value) =>
      guard(async () => {
        const id = runId;
        const milestoneRequest = {};
        switch (step) {
          case 'arrival': await milk.recordCircuitStopArrival({ id, sequence, milestoneRequest }); break;
          case 'departure': await milk.recordCircuitStopDeparture({ id, sequence, milestoneRequest }); break;
          case 'pickup': await milk.recordCircuitStopPickup({ id, sequence, circuitPickupRequest: { quantityKg: Number(value) } }); break;
          case 'skip': await milk.skipCircuitStop({ id, sequence, circuitSkipRequest: { reason: value.trim() } }); break;
        }
      }),

    completeCircuitRun: (runId) =>
      guard(async () => { await milk.completeCircuitRun({ id: runId, milestoneRequest: {} }); }),

    planItinerary: (d) =>
      guard(async () => text((await multimodal.planItinerary({
        planItineraryRequest: {
          shipmentRef: d.shipmentRef.trim(), currency: blankAsAbsent(d.currency),
          legs: d.legs.map((l) => ({
            mode: l.mode as PlanItineraryLegRequestModeEnum, fromTerminalId: l.fromTerminalId.trim(),
            toTerminalId: l.toTerminalId.trim(), plannedDepartureAt: new Date(l.plannedDepartureAt),
            plannedArrivalAt: new Date(l.plannedArrivalAt), carrierPartnerId: l.carrierPartnerId.trim(),
            tripId: blankAsAbsent(l.tripId),
            unitType: l.unitType ? l.unitType as PlanItineraryLegRequestUnitTypeEnum : undefined,
            unitCount: decimalOrAbsent(l.unitCount), bookingRef: blankAsAbsent(l.bookingRef),
            trainNo: blankAsAbsent(l.trainNo), costAmount: decimalOrAbsent(l.costAmount),
          })),
        },
      })).id)),

    recordRailMilestone: (itineraryId, seq, milestone) =>
      guard(async () => {
        await multimodal.recordRailMilestone({
          id: itineraryId, seq, railMilestoneRequest: { milestone: milestone as RailMilestoneRequestMilestoneEnum },
        });
      }),
  };
}
