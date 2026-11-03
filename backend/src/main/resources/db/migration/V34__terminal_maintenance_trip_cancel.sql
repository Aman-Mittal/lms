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

-- Terminal maintenance and trip cancellation.
--
-- 1. Permissions to change and to remove a terminal.
-- 2. A cancelled trip records when and why, and no longer holds its load: the
--    one-live-trip-per-load rule applies to trips that are not cancelled.
-- 3. An index behind every foreign key to terminal that had none, so that
--    asking "is this terminal still used" is an index probe per referencing
--    table rather than a scan of it. The database's own RESTRICT check on
--    delete needs the same indexes, and is the backstop if the service's
--    check is ever bypassed.

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'TERMINAL_UPDATE', 'TERMINAL', 'UPDATE'),
       (gen_random_uuid(), 'TERMINAL_DELETE', 'TERMINAL', 'DELETE');

-- ---------------------------------------------------------------------------
-- Cancelled trips
-- ---------------------------------------------------------------------------

ALTER TABLE trip
    ADD COLUMN cancelled_at         TIMESTAMPTZ,
    ADD COLUMN cancellation_reason  TEXT;

-- V10 made (tenant_id, load_id) unique outright, so a cancelled trip kept its
-- load for ever and a replacement trip could never be raised. What the rule
-- protects against is two lorries dispatched for one set of freight, and a
-- cancelled trip never dispatches.
ALTER TABLE trip DROP CONSTRAINT trip_load_unique;
CREATE UNIQUE INDEX trip_live_load_unique ON trip (tenant_id, load_id)
    WHERE status <> 'CANCELLED';

-- ---------------------------------------------------------------------------
-- Indexes behind the foreign keys to terminal
-- ---------------------------------------------------------------------------

CREATE INDEX circuit_run_plant_terminal_ref_idx
    ON circuit_run (tenant_id, plant_terminal_id);

CREATE INDEX circuit_run_stop_terminal_ref_idx
    ON circuit_run_stop (tenant_id, terminal_id);

CREATE INDEX consignment_destination_terminal_ref_idx
    ON consignment (tenant_id, destination_terminal_id);

CREATE INDEX consignment_origin_hub_terminal_ref_idx
    ON consignment (tenant_id, origin_hub_terminal_id)
    WHERE origin_hub_terminal_id IS NOT NULL;

CREATE INDEX gate_event_terminal_ref_idx
    ON gate_event (tenant_id, terminal_id);

CREATE INDEX geofence_event_terminal_ref_idx
    ON geofence_event (tenant_id, terminal_id);

CREATE INDEX handling_unit_destination_terminal_ref_idx
    ON handling_unit (tenant_id, destination_terminal_id)
    WHERE destination_terminal_id IS NOT NULL;

CREATE INDEX itinerary_leg_from_terminal_ref_idx
    ON itinerary_leg (tenant_id, from_terminal_id);

CREATE INDEX itinerary_leg_to_terminal_ref_idx
    ON itinerary_leg (tenant_id, to_terminal_id);

CREATE INDEX milk_run_route_plant_terminal_ref_idx
    ON milk_run_route (tenant_id, plant_terminal_id);

CREATE INDEX milk_run_route_stop_terminal_ref_idx
    ON milk_run_route_stop (tenant_id, terminal_id);

CREATE INDEX order_line_destination_terminal_ref_idx
    ON order_line (tenant_id, destination_terminal_id);

CREATE INDEX rail_tariff_origin_terminal_ref_idx
    ON rail_tariff (tenant_id, origin_terminal_id);

CREATE INDEX rail_tariff_destination_terminal_ref_idx
    ON rail_tariff (tenant_id, destination_terminal_id);

CREATE INDEX routing_guide_destination_terminal_ref_idx
    ON routing_guide (tenant_id, destination_terminal_id);

CREATE INDEX sales_order_origin_terminal_ref_idx
    ON sales_order (tenant_id, origin_terminal_id)
    WHERE origin_terminal_id IS NOT NULL;

CREATE INDEX sales_order_origin_hub_terminal_ref_idx
    ON sales_order (tenant_id, origin_hub_terminal_id)
    WHERE origin_hub_terminal_id IS NOT NULL;

CREATE INDEX sales_order_destination_hub_terminal_ref_idx
    ON sales_order (tenant_id, destination_hub_terminal_id)
    WHERE destination_hub_terminal_id IS NOT NULL;

CREATE INDEX stock_movement_warehouse_ref_idx
    ON stock_movement (tenant_id, warehouse_id);

CREATE INDEX tariff_origin_terminal_ref_idx
    ON tariff (tenant_id, origin_terminal_id);

CREATE INDEX tariff_destination_terminal_ref_idx
    ON tariff (tenant_id, destination_terminal_id);

CREATE INDEX trip_origin_terminal_ref_idx
    ON trip (tenant_id, origin_terminal_id);

CREATE INDEX trip_destination_terminal_ref_idx
    ON trip (tenant_id, destination_terminal_id)
    WHERE destination_terminal_id IS NOT NULL;

CREATE INDEX weighbridge_reading_terminal_ref_idx
    ON weighbridge_reading (tenant_id, terminal_id);
