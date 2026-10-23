--
-- Copyright 2026 Aman Mittal
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- Settling rail carriers through freight bills.
--
-- Until now a freight bill was always about a trip. A rail leg has no trip and
-- no load, so the bill's subject becomes one of two: a trip (as before, with
-- its load, and possibly a milk-run circuit) or a rail leg of an itinerary.
-- One table, not a parallel one: the tolerance match, the dispute path, the
-- approval limit, the line breakdown and the settlement queue are all written
-- against freight_bill, and a second table would need all of them again.
--
-- No new table, so no new row-level security: freight_bill is already enabled
-- and forced (V13).

ALTER TABLE freight_bill
    ALTER COLUMN trip_id DROP NOT NULL,
    ALTER COLUMN load_id DROP NOT NULL;

-- The rail leg a bill pays for. Null for a bill raised from a trip.
ALTER TABLE freight_bill
    ADD COLUMN itinerary_leg_id UUID REFERENCES itinerary_leg (id) ON DELETE RESTRICT;

-- Exactly one subject, and a load travels with its trip: a bill with neither
-- would pay for nothing, a bill with both would pay for the same movement
-- twice.
ALTER TABLE freight_bill
    ADD CONSTRAINT freight_bill_subject_one CHECK ((trip_id IS NULL) <> (itinerary_leg_id IS NULL)),
    ADD CONSTRAINT freight_bill_load_with_trip CHECK ((load_id IS NULL) = (trip_id IS NULL));

-- One payable per rail leg, however often its completion is delivered. Not
-- partial on status, unlike the trip index of V23: nothing supersedes a rail
-- bill, so a cancelled one must not let a second be raised for the same leg.
CREATE UNIQUE INDEX freight_bill_itinerary_leg_idx
    ON freight_bill (tenant_id, itinerary_leg_id)
    WHERE itinerary_leg_id IS NOT NULL;
