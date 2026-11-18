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
 * What the trip desk and the load opening form know of the platform. Every value is already text (a missing one is
 * blank, a time is formatted for display); the adapters turn the generated client into these.
 */

/** A trip as the desk shows it. */
export interface TripCard {
  id: string;
  tripNo: string;
  status: string;
  loadId: string;
  loadNo: string;
  vendorName: string;
  vehicle: string;
  driver: string;
  origin: string;
  destination: string;
  plannedStartAt: string;
  gateInAt: string;
  dispatchedAt: string;
  arrivedAt: string;
  completedAt: string;
  cancelledAt: string;
  cancellationReason: string;
}

/** What raising a trip needs; `plannedStartAt` is a `datetime-local` value, or blank. */
export interface TripRaiseDraft {
  tripNo: string;
  loadId: string;
  plannedStartAt: string;
}

/**
 * The milestones a trip is moved through, one endpoint each. Gate-in and weighing belong to the gate desk, and the
 * stops to the stop table.
 */
export type TripStep = 'loaded' | 'dispatch' | 'in-transit' | 'arrival' | 'completion';

/** What opening a load needs. */
export interface LoadOpenDraft {
  loadNo: string;
  originTerminalId: string;
  vehicleId: string;
  /** FTL or LTL; blank leaves it to the platform (FTL). */
  freightMode: string;
}
