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

-- What the application layer of 3.3 and 3.4 needs that the tables of V6 did
-- not anticipate. A new migration rather than an edit to V6: Flyway records a
-- checksum per applied script, so editing one that has already run turns every
-- existing database into a failed startup.

-- ---------------------------------------------------------------------------
-- Which demand is still waiting to be planned
-- ---------------------------------------------------------------------------

-- Consignment membership lives in `consignment_line`, which belongs to the
-- planning module. Orders must not read it -- that would invert the dependency
-- and let planning's schema changes break order. So the order module keeps its
-- own answer to "is this line spoken for", and planning tells it.
ALTER TABLE order_line
    ADD COLUMN planned BOOLEAN NOT NULL DEFAULT false;

-- V6 left `order_line` without a version column on the reasoning that a line is
-- written once and only ever flipped to planned, so there is no lost update to
-- lose. That reasoning is about concurrency and it is correct; it is also
-- beside the point.
--
-- Spring Data JDBC decides insert-versus-update by asking whether the entity
-- looks new. With a client-assigned @Id and no @Version, every save of a
-- record with an id set looks like an existing row, so the insert becomes an
-- UPDATE that matches nothing -- and reports success. Order lines silently did
-- not persist, and validation then rejected every order for having no lines.
-- The version column is what makes the entity answer "new" correctly.
ALTER TABLE order_line
    ADD COLUMN version BIGINT;

-- Partial index: consignment generation only ever asks for the unplanned
-- lines, and on a mature order almost every line is planned. Indexing only the
-- false rows keeps the index small enough to stay resident on a 512 MB
-- instance long after the table itself has stopped fitting.
CREATE INDEX order_line_unplanned_idx ON order_line (tenant_id, order_id)
    WHERE planned = false;

-- ---------------------------------------------------------------------------
-- What a consignment contains, for the compatibility check at load time
-- ---------------------------------------------------------------------------

-- 3.3 forbids incompatible materials sharing a transport unit. That check runs
-- twice: once inside a consignment when it is generated, and again when two
-- consignments are put on the same load. The second check needs to know what
-- each consignment holds without joining back through every order line, so the
-- classes are denormalised here as a sorted comma-separated set.
--
-- Denormalised on purpose, and safe to denormalise: a consignment's contents
-- are fixed at generation and never edited afterwards.
ALTER TABLE consignment
    ADD COLUMN material_classes TEXT NOT NULL DEFAULT '';

-- ---------------------------------------------------------------------------
-- The vehicle a load is built against
-- ---------------------------------------------------------------------------

-- V6 stored only `vehicle_type`, which is enough to size a load but not enough
-- to answer "may this vehicle carry dangerous goods". Certification is a
-- property of the individual vehicle, not of its type.
ALTER TABLE load_unit
    ADD COLUMN vehicle_id UUID REFERENCES vehicle (id) ON DELETE RESTRICT;

-- Copied from the vehicle when the load is opened rather than joined at check
-- time, for the same reason the capacities are: a load must keep being judged
-- against the fleet definition it was planned against. If a certificate is
-- revoked, that must surface as a dispatch block on a re-check, not as a
-- silent retrospective rewrite of a plan somebody already signed off.
ALTER TABLE load_unit
    ADD COLUMN vehicle_hazmat_certified BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX load_unit_vehicle_idx ON load_unit (tenant_id, vehicle_id, status);
