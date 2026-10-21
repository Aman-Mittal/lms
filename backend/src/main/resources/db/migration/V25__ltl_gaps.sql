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

-- Part load, the gaps V17 left open.
--
-- 1. A docket's journey does not end at the destination hub. The line haul
--    drops it there; a second, local load carries it to the consignee. That
--    needs three more docket states, a way to tell a delivery load from a
--    line haul, and a docket allowed on one load of each kind.
-- 2. The monthly fuel surcharge applies to what a customer pays for a docket,
--    as it already does to what a carrier is paid for a vehicle.
-- 3. The out-of-delivery-area charge can be priced per kilogram with a
--    minimum, not only as a flat amount.
--
-- Every existing row keeps its meaning: loads are line hauls, links are line
-- haul links, rate cards charge ODA flat, and receivables carry no fuel.

-- ---------------------------------------------------------------------------
-- Docket states for the delivery leg
-- ---------------------------------------------------------------------------

-- ARRIVED_AT_HUB: the line haul has delivered the docket to its destination hub.
-- ASSIGNED_TO_DELIVERY: on a local delivery load from that hub.
-- OUT_FOR_DELIVERY: that delivery load has been dispatched.
-- The full machine is documented on the Consignment aggregate.
ALTER TABLE consignment
    DROP CONSTRAINT consignment_status_valid,
    ADD CONSTRAINT consignment_status_valid CHECK (status IN
        ('PLANNED', 'ASSIGNED_TO_LOAD', 'IN_TRANSIT', 'ARRIVED_AT_HUB',
         'ASSIGNED_TO_DELIVERY', 'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED')),
    -- Only a docket has a hub to arrive at.
    ADD CONSTRAINT consignment_hub_states_are_ltl CHECK (
        status NOT IN ('ARRIVED_AT_HUB', 'ASSIGNED_TO_DELIVERY', 'OUT_FOR_DELIVERY')
            OR freight_mode = 'LTL');

-- The planner's work queue at a destination hub: dockets that have arrived and
-- are waiting for a delivery load, paged by docket number.
CREATE INDEX consignment_hub_queue_idx
    ON consignment (tenant_id, destination_hub_terminal_id, status, tracking_ref);

-- ---------------------------------------------------------------------------
-- Line haul or delivery
-- ---------------------------------------------------------------------------

-- A delivery load starts at the destination hub its dockets arrived at and
-- drops at their consignees. Only a part load has the distinction; a full
-- truckload is its own delivery.
ALTER TABLE load_unit
    ADD COLUMN ltl_leg TEXT NOT NULL DEFAULT 'LINE_HAUL',
    ADD CONSTRAINT load_unit_ltl_leg_valid CHECK (ltl_leg IN ('LINE_HAUL', 'DELIVERY')),
    ADD CONSTRAINT load_unit_delivery_is_ltl CHECK (ltl_leg = 'LINE_HAUL' OR freight_mode = 'LTL');

-- The link records which leg it is, copied from the load, so that the
-- double-booking guarantee can be "at most one load per leg" rather than "at
-- most one load ever". A docket keeps its line-haul link after it moves on to
-- delivery: that is the record of which vehicle carried it between cities.
ALTER TABLE load_consignment
    ADD COLUMN leg TEXT NOT NULL DEFAULT 'LINE_HAUL',
    ADD CONSTRAINT load_consignment_leg_valid CHECK (leg IN ('LINE_HAUL', 'DELIVERY')),
    DROP CONSTRAINT load_consignment_unique_assignment,
    ADD CONSTRAINT load_consignment_unique_assignment UNIQUE (consignment_id, leg);

-- ---------------------------------------------------------------------------
-- ODA per kilogram
-- ---------------------------------------------------------------------------

-- FLAT charges oda_surcharge, as before. PER_KG charges oda_rate_per_kg on the
-- docket's chargeable weight, never less than oda_minimum. Set when a version
-- is published; versions are immutable (V17's rules still refuse UPDATE), so
-- changing the basis is publishing a successor.
ALTER TABLE ltl_rate_card
    ADD COLUMN oda_basis       TEXT           NOT NULL DEFAULT 'FLAT',
    ADD COLUMN oda_rate_per_kg NUMERIC(12, 4) NOT NULL DEFAULT 0,
    ADD COLUMN oda_minimum     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ltl_rate_card_oda_basis_valid CHECK (oda_basis IN ('FLAT', 'PER_KG')),
    ADD CONSTRAINT ltl_rate_card_oda_amounts_sane CHECK (oda_rate_per_kg >= 0 AND oda_minimum >= 0);

-- ---------------------------------------------------------------------------
-- Fuel, and the ODA basis, on the receivable
-- ---------------------------------------------------------------------------

-- The percentage is recorded beside the amount so that a disputed docket shows
-- which month's index it was charged at; a later correction of that month
-- changes the next docket, not this one. The surcharge may be negative, as the
-- carrier-side one may: a contract that passes on a rise passes on a fall.
ALTER TABLE docket_receivable
    ADD COLUMN oda_basis          TEXT,
    ADD COLUMN fuel_surcharge_pct NUMERIC(6, 3),
    ADD COLUMN fuel_surcharge     NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD CONSTRAINT docket_receivable_oda_basis_valid
        CHECK (oda_basis IS NULL OR oda_basis IN ('FLAT', 'PER_KG')),
    DROP CONSTRAINT docket_receivable_adds_up,
    ADD CONSTRAINT docket_receivable_adds_up CHECK (
        total_amount = freight_amount + docket_charge + oda_surcharge + fuel_surcharge);
