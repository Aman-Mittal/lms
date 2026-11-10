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


-- The guard that keeps a split consignment off every load (V43) ran once per
-- row, so boarding four hundred consignments in one request ran four hundred
-- lookups. It now runs once per statement, over the rows the statement wrote.
-- Transition tables allow one event per trigger, so inserts and updates each
-- get their own.

DROP TRIGGER load_consignment_not_split ON load_consignment;
DROP FUNCTION load_consignment_refuse_split();

CREATE FUNCTION load_consignment_refuse_split() RETURNS trigger
    LANGUAGE plpgsql
AS
$$
DECLARE
    split_id uuid;
BEGIN
    SELECT c.id
      INTO split_id
      FROM written w
      JOIN consignment c ON c.id = w.consignment_id
     WHERE c.status = 'SPLIT'
     LIMIT 1;
    IF split_id IS NOT NULL THEN
        RAISE EXCEPTION 'consignment % has been split; load its parts instead', split_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER load_consignment_insert_not_split
    AFTER INSERT
    ON load_consignment
    REFERENCING NEW TABLE AS written
    FOR EACH STATEMENT
EXECUTE FUNCTION load_consignment_refuse_split();

CREATE TRIGGER load_consignment_update_not_split
    AFTER UPDATE
    ON load_consignment
    REFERENCING NEW TABLE AS written
    FOR EACH STATEMENT
EXECUTE FUNCTION load_consignment_refuse_split();
