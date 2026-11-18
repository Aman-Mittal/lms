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

import { FreightMode as ApiFreightMode } from '../api';
import type { Trip } from '../api';
import type { LoadOpenDraft, TripCard, TripRaiseDraft, TripStep } from './trip-desk-types';
import { GeneratedGatewayConfig } from './generated-gateway-config';
import { blankAsAbsent, instant, instantOf, text } from './generated-gateway-helpers';

function cardOf(t: Trip): TripCard {
  return {
    id: text(t.id), tripNo: text(t.tripNo), status: text(t.status), loadId: text(t.loadId), loadNo: text(t.loadNo),
    vendorName: text(t.vendorName), vehicle: text(t.vehicleRegistrationNo), driver: text(t.driverName),
    origin: text(t.originTerminalCode), destination: text(t.destinationTerminalCode),
    plannedStartAt: instant(t.plannedStartAt), gateInAt: instant(t.gateInAt), dispatchedAt: instant(t.dispatchedAt),
    arrivedAt: instant(t.arrivedAt), completedAt: instant(t.completedAt), cancelledAt: instant(t.cancelledAt),
    cancellationReason: text(t.cancellationReason),
  };
}

/** Opening and planning a load, and raising and moving a trip. */
export abstract class GeneratedGatewayTrips extends GeneratedGatewayConfig {
  openLoad(orgUnitId: string, draft: LoadOpenDraft): Promise<string> {
    return this.call(this.loads.openLoad({
      openLoadRequest: {
        orgUnitId, loadNo: draft.loadNo.trim(), originTerminalId: draft.originTerminalId.trim(),
        vehicleId: draft.vehicleId.trim(), freightMode: draft.freightMode ? draft.freightMode as ApiFreightMode : undefined,
      },
    }), (r) => text(r.id));
  }

  planLoad(loadId: string): Promise<void> {
    return this.call(this.loads.planLoad({ id: loadId }), () => undefined);
  }

  tripCard(id: string): Promise<TripCard> {
    return this.call(this.trips.getTrip({ id: id.trim() }), cardOf);
  }

  raiseTrip(draft: TripRaiseDraft): Promise<string> {
    return this.call(this.trips.raiseTrip({
      raiseTripRequest: {
        tripNo: draft.tripNo.trim(), loadId: draft.loadId.trim(),
        plannedStartAt: draft.plannedStartAt ? instantOf(draft.plannedStartAt) : undefined,
      },
    }), (r) => text(r.id));
  }

  assignTrip(tripId: string, vehicleId: string, driverId: string): Promise<void> {
    return this.call(this.trips.assignTrip({
      id: tripId, assignTripRequest: { vehicleId: vehicleId.trim(), driverId: blankAsAbsent(driverId) },
    }), () => undefined);
  }

  stepTrip(tripId: string, step: TripStep): Promise<void> {
    const id = tripId;
    const milestoneRequest = {};
    switch (step) {
      case 'loaded': return this.call(this.trips.markLoaded({ id, milestoneRequest }), () => undefined);
      case 'dispatch': return this.call(this.trips.dispatchTrip({ id, milestoneRequest }), () => undefined);
      case 'in-transit': return this.call(this.trips.beginTransit({ id }), () => undefined);
      case 'arrival': return this.call(this.trips.recordArrival({ id, milestoneRequest }), () => undefined);
      case 'completion': return this.call(this.trips.completeTrip({ id, milestoneRequest }), () => undefined);
    }
  }

  cancelTrip(tripId: string, reason: string): Promise<void> {
    return this.call(this.trips.cancelTrip({ id: tripId, cancelTripRequest: { reason: reason.trim() } }), () => undefined);
  }
}
