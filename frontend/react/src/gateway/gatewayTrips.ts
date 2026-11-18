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

import { PlanningApi, TripsApi, type Configuration, type FreightMode, type Trip } from '../api';
import type { LmsGateway } from './lmsGateway';
import type { TripCard } from './tripDeskTypes';
import { blankAsAbsent, guard, instantOrAbsent, text } from './gatewayShared';

type TripVerbs = Pick<LmsGateway, 'openLoad' | 'planLoad' | 'tripCard' | 'raiseTrip' | 'assignTrip' | 'stepTrip'
  | 'cancelTrip'>;

function cardOf(t: Trip): TripCard {
  return {
    id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), loadId: text(t.loadId), loadNo: text(t.loadNo),
    vendorName: text(t.vendorName), vehicle: text(t.vehicleRegistrationNo), driver: text(t.driverName),
    origin: text(t.originTerminalCode), destination: text(t.destinationTerminalCode),
    plannedStartAt: text(t.plannedStartAt), gateInAt: text(t.gateInAt), dispatchedAt: text(t.dispatchedAt),
    arrivedAt: text(t.arrivedAt), completedAt: text(t.completedAt), cancelledAt: text(t.cancelledAt),
    cancellationReason: text(t.cancellationReason),
  };
}

/** Opening and planning a load, and raising and moving a trip. */
export function tripVerbs(config: Configuration): TripVerbs {
  const loads = new PlanningApi(config);
  const trips = new TripsApi(config);

  return {
    openLoad: (orgUnitId, draft) =>
      guard(async () => text((await loads.openLoad({
        openLoadRequest: {
          orgUnitId, loadNo: draft.loadNo.trim(), originTerminalId: draft.originTerminalId.trim(),
          vehicleId: draft.vehicleId.trim(), freightMode: draft.freightMode ? draft.freightMode as FreightMode : undefined,
        },
      })).id)),

    planLoad: (loadId) => guard(async () => { await loads.planLoad({ id: loadId }); }),

    tripCard: (id) => guard(async () => cardOf(await trips.getTrip({ id: id.trim() }))),

    raiseTrip: (draft) =>
      guard(async () => text((await trips.raiseTrip({
        raiseTripRequest: {
          tripNo: draft.tripNo.trim(), loadId: draft.loadId.trim(), plannedStartAt: instantOrAbsent(draft.plannedStartAt),
        },
      })).id)),

    assignTrip: (tripId, vehicleId, driverId) =>
      guard(async () => {
        await trips.assignTrip({
          id: tripId, assignTripRequest: { vehicleId: vehicleId.trim(), driverId: blankAsAbsent(driverId) },
        });
      }),

    stepTrip: (tripId, step) =>
      guard(async () => {
        const id = tripId;
        const milestoneRequest = {};
        switch (step) {
          case 'loaded': await trips.markLoaded({ id, milestoneRequest }); break;
          case 'dispatch': await trips.dispatchTrip({ id, milestoneRequest }); break;
          case 'in-transit': await trips.beginTransit({ id }); break;
          case 'arrival': await trips.recordArrival({ id, milestoneRequest }); break;
          case 'completion': await trips.completeTrip({ id, milestoneRequest }); break;
        }
      }),

    cancelTrip: (tripId, reason) =>
      guard(async () => { await trips.cancelTrip({ id: tripId, cancelTripRequest: { reason: reason.trim() } }); }),
  };
}
