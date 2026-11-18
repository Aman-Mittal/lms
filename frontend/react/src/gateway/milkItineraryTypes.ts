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

/*
 * What the milk-run desk and the itinerary desk know of the platform. Every value is already text (a missing one is
 * blank, a time is formatted for display); the adapters turn the generated client into these.
 */

/** The days a route may run on, Monday first. */
export const ROUTE_DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'] as const;

/** One supplier pickup of a route as it is typed: the windows are minutes after the route's departure. */
export interface RouteStopDraft {
  terminalId: string;
  windowOpenMin: string;
  windowCloseMin: string;
  plannedDwellMin: string;
}

/** What publishing a route needs; `effectiveFrom` is a `YYYY-MM-DD` day and `departureTime` an `HH:mm` time. */
export interface RoutePlanDraft {
  code: string;
  name: string;
  plantTerminalId: string;
  effectiveFrom: string;
  days: string[];
  departureTime: string;
  timeZone: string;
  runRate: string;
  perStopFee: string;
  currency: string;
  stops: RouteStopDraft[];
}

/** A circuit run as the desk shows it. */
export interface CircuitRunCard {
  id: string;
  routeCode: string;
  runDate: string;
  status: string;
  tripNo: string;
  startedAt: string;
  completedAt: string;
  stopsVisited: string;
  stopsSkipped: string;
  chargeAmount: string;
  totalPickedUpKg: string;
}

/** One stop of a run. */
export interface CircuitStopCard {
  sequence: number;
  terminalCode: string;
  status: string;
  arrivedAt: string;
  departedAt: string;
  pickedUpKg: string;
  skipReason: string;
}

/** The milestones of one stop; `pickup` needs the quantity and `skip` the reason, in `value`. */
export type CircuitStopStep = 'arrival' | 'departure' | 'pickup' | 'skip';

/** One leg of an itinerary as it is typed; times are `datetime-local` values. */
export interface ItineraryLegDraft {
  mode: string;
  fromTerminalId: string;
  toTerminalId: string;
  plannedDepartureAt: string;
  plannedArrivalAt: string;
  carrierPartnerId: string;
  tripId: string;
  unitType: string;
  unitCount: string;
  bookingRef: string;
  trainNo: string;
  costAmount: string;
}

/** What planning an itinerary needs. */
export interface ItineraryPlanDraft {
  shipmentRef: string;
  currency: string;
  legs: ItineraryLegDraft[];
}
