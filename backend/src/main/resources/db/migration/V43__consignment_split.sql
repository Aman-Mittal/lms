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


-- One consignment carried by many vehicles.
--
-- A consignment is cut per consignee and destination, so bulk freight -- two
-- thousand tonnes of one product for one consignee -- arrives as a single
-- consignment no vehicle can carry, and a consignment sits on one load per
-- leg. Splitting replaces it with parts, each an ordinary full-truckload
-- consignment with its own lorry receipt that goes on its own load. The
-- original stays as the record the customer quoted, with status SPLIT: it is
-- never loaded, delivered, billed or cancelled again, and its progress is
-- read from its parts.
ALTER TABLE consignment
    ADD COLUMN parent_consignment_id UUID REFERENCES consignment (id),
    DROP CONSTRAINT consignment_status_valid,
    ADD CONSTRAINT consignment_status_valid CHECK (status IN
        ('PLANNED', 'ASSIGNED_TO_LOAD', 'IN_TRANSIT', 'ARRIVED_AT_HUB',
         'ASSIGNED_TO_DELIVERY', 'OUT_FOR_DELIVERY', 'DELIVERED', 'CANCELLED', 'SPLIT')),
    -- A docket is consolidated with other customers' dockets and billed as
    -- one; only a full truckload is split.
    ADD CONSTRAINT consignment_split_is_ftl CHECK (status <> 'SPLIT' OR freight_mode = 'FTL'),
    -- One level: a part is never itself split, and nothing is its own part.
    ADD CONSTRAINT consignment_part_not_split CHECK (
        parent_consignment_id IS NULL OR (status <> 'SPLIT' AND parent_consignment_id <> id));

-- A split consignment's parts, in receipt order. Partial: almost every
-- consignment is not a part.
CREATE INDEX consignment_parent_idx
    ON consignment (tenant_id, parent_consignment_id, tracking_ref)
    WHERE parent_consignment_id IS NOT NULL;

-- The split consignment is off every load. It can be split only while
-- PLANNED, which already means it is on none; this keeps it that way.
CREATE FUNCTION load_consignment_refuse_split() RETURNS trigger
    LANGUAGE plpgsql
AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM consignment WHERE id = NEW.consignment_id AND status = 'SPLIT') THEN
        RAISE EXCEPTION 'consignment % has been split; load its parts instead', NEW.consignment_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER load_consignment_not_split
    BEFORE INSERT OR UPDATE
    ON load_consignment
    FOR EACH ROW
EXECUTE FUNCTION load_consignment_refuse_split();
