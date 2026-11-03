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
--

-- Two cross-module flows that until now stopped at an event nobody heard.
--
--   * Finance raises the carrier payable for a completed milk-run circuit.
--   * Planning turns a warehouse handling unit handed to transport into a
--     consignment, ready to be put on a load.
--
-- No new tables: both flows land in the tables that already hold freight bills
-- and consignments, so they are listed, matched and loaded by the code that
-- already does so. Row-level security on every table touched here is already
-- enabled and forced (V2, V6, V13, V20).

-- ---------------------------------------------------------------------------
-- Finance: the circuit payable
-- ---------------------------------------------------------------------------

-- The circuit a bill pays for. Null for a bill raised from a completed trip.
ALTER TABLE freight_bill
    ADD COLUMN circuit_run_id UUID REFERENCES circuit_run (id) ON DELETE RESTRICT;

-- One payable per circuit, however often its completion is delivered.
CREATE UNIQUE INDEX freight_bill_circuit_run_idx
    ON freight_bill (tenant_id, circuit_run_id)
    WHERE circuit_run_id IS NOT NULL;

-- A circuit rides an ordinary trip, and that trip may complete and be billed
-- on a lane tariff too. The circuit's bill supersedes an unsettled trip bill by
-- cancelling it, so "one bill per trip" becomes "one LIVE bill per trip": the
-- invariant that matters -- the same lorry is never payable twice -- is kept,
-- and the cancelled bill stays on record with the reason.
ALTER TABLE freight_bill DROP CONSTRAINT freight_bill_trip_once;

CREATE UNIQUE INDEX freight_bill_trip_live_idx
    ON freight_bill (tenant_id, trip_id)
    WHERE status <> 'CANCELLED';

-- Every bill a trip ever had, cancelled ones included: the redelivery check
-- has to see a superseded trip bill, or it would raise it again.
CREATE INDEX freight_bill_trip_idx ON freight_bill (tenant_id, trip_id);

-- A supplier collected from on a circuit, at the route's per-stop fee.
ALTER TABLE freight_bill_line DROP CONSTRAINT freight_bill_line_type_valid;
ALTER TABLE freight_bill_line
    ADD CONSTRAINT freight_bill_line_type_valid CHECK (charge_type IN
        ('BASE_FREIGHT', 'MINIMUM_CHARGE_UPLIFT', 'DETENTION',
         'ADDITIONAL_DROP', 'FUEL_SURCHARGE', 'COLLECTION_STOP'));

-- ---------------------------------------------------------------------------
-- Warehouse: where a dispatched unit is going
-- ---------------------------------------------------------------------------

-- Recorded at dispatch when the unit is handed to the platform's own
-- transport. All of destination, consignee and weight, or none: a unit with
-- half an address cannot be planned.
ALTER TABLE handling_unit
    ADD COLUMN destination_terminal_id UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    ADD COLUMN consignee_partner_id    UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    ADD COLUMN gross_weight_kg         NUMERIC(14, 3),
    ADD COLUMN volume_m3               NUMERIC(14, 3),
    ADD CONSTRAINT handling_unit_hand_off_whole CHECK (
        (destination_terminal_id IS NULL) = (consignee_partner_id IS NULL)
        AND (destination_terminal_id IS NULL) = (gross_weight_kg IS NULL)
        AND (volume_m3 IS NULL OR destination_terminal_id IS NOT NULL)),
    ADD CONSTRAINT handling_unit_hand_off_measures_sane CHECK (
        (gross_weight_kg IS NULL OR gross_weight_kg > 0)
        AND (volume_m3 IS NULL OR volume_m3 >= 0)),
    ADD CONSTRAINT handling_unit_hand_off_on_dispatch CHECK (
        destination_terminal_id IS NULL OR status = 'DISPATCHED');

-- ---------------------------------------------------------------------------
-- Planning: a consignment with no order behind it
-- ---------------------------------------------------------------------------

-- A consignment received from the warehouse carries a handling unit instead of
-- an order. Exactly one of the two.
ALTER TABLE consignment
    ALTER COLUMN order_id DROP NOT NULL,
    ADD COLUMN handling_unit_id UUID REFERENCES handling_unit (id) ON DELETE RESTRICT,
    ADD CONSTRAINT consignment_has_one_source CHECK (
        (order_id IS NULL) <> (handling_unit_id IS NULL));

-- One consignment per unit, however often the dispatch is delivered.
CREATE UNIQUE INDEX consignment_handling_unit_idx
    ON consignment (tenant_id, handling_unit_id)
    WHERE handling_unit_id IS NOT NULL;
