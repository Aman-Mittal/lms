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
 * What the client billing desk's four forms (portal access, service usage, storage computation, margins) know of the
 * platform. Every value is already text; the adapters turn the generated client into these.
 */

/** The service types a person may record by hand: transport is captured from completed trips. */
export const HAND_RECORDED_SERVICES = ['STORAGE', 'HANDLING_IN', 'HANDLING_OUT', 'VALUE_ADDED'] as const;
export type HandRecordedService = (typeof HAND_RECORDED_SERVICES)[number];

/** One usage of a service, as typed; `serviceCode` is only for VALUE_ADDED. */
export interface ServiceUsageDraft {
  serviceType: HandRecordedService;
  serviceCode: string;
  quantity: string;
  occurredOn: string;
  reference: string;
  /** The caller's own id for this usage; a second call with the same key returns the first charge. */
  sourceKey: string;
}

/** One day's closing stock and the pallets it was charged as. */
export interface StorageDayRow {
  heldOn: string;
  unitsHeld: string;
  unitsPerPallet: string;
  pallets: string;
  charged: boolean;
}

/** A client's storage for one month; `computed` is false until the month has been computed. */
export interface StorageMonthView {
  periodStart: string;
  computed: boolean;
  unitDays: string;
  palletDays: string;
  days: StorageDayRow[];
}

/** One page of the margin report: the rows (every value text) and the cursor of the next page, blank at the end. */
export interface MarginPage {
  rows: Array<Record<string, string>>;
  nextCursor: string;
}
