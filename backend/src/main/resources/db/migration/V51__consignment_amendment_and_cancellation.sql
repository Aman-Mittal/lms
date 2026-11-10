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

-- A consignment's particulars, and the cancellation of one consignment or one
-- order line.
--
-- What a consignment carries -- its lines, weight, volume and material -- is
-- fixed when it is generated. What is said about it is not: who to call at the
-- consignee and their reference, when the consignee wants it, the client's own
-- reference for it, and remarks. Those may be corrected while the consignment
-- is planned and not yet on a load.

ALTER TABLE consignment
    ADD COLUMN contact_name         TEXT,
    ADD COLUMN contact_phone        TEXT,
    ADD COLUMN consignee_reference  TEXT,
    ADD COLUMN client_reference     TEXT,
    ADD COLUMN delivery_window_from TIMESTAMPTZ,
    ADD COLUMN delivery_window_to   TIMESTAMPTZ,
    ADD COLUMN remarks              TEXT,
    ADD CONSTRAINT consignment_contact_name_bounded CHECK (contact_name IS NULL OR length(contact_name) <= 120),
    ADD CONSTRAINT consignment_contact_phone_bounded CHECK (contact_phone IS NULL OR length(contact_phone) <= 40),
    ADD CONSTRAINT consignment_consignee_reference_bounded
        CHECK (consignee_reference IS NULL OR length(consignee_reference) <= 80),
    ADD CONSTRAINT consignment_client_reference_bounded
        CHECK (client_reference IS NULL OR length(client_reference) BETWEEN 1 AND 80),
    ADD CONSTRAINT consignment_remarks_bounded CHECK (remarks IS NULL OR length(remarks) <= 500),
    ADD CONSTRAINT consignment_delivery_window_ordered CHECK (
        delivery_window_from IS NULL OR delivery_window_to IS NULL
            OR delivery_window_from <= delivery_window_to);

-- The client's reference names one consignment within the tenant, whatever
-- state it is in: a cancelled consignment keeps it, as a tracking reference is
-- never reused. Partial: most consignments carry none. Also what a lookup by
-- the reference reads.
CREATE UNIQUE INDEX consignment_client_reference_unique
    ON consignment (tenant_id, client_reference)
    WHERE client_reference IS NOT NULL;

-- One line of a validated order can be cancelled on its own. A cancelled line
-- is no longer demand: it is never planned again, it no longer counts towards
-- the order's coverage or its customer's credit exposure, and it stays on the
-- order as the record of what was asked for.
ALTER TABLE order_line
    ADD COLUMN cancelled BOOLEAN NOT NULL DEFAULT false,
    ADD CONSTRAINT order_line_cancelled_not_planned CHECK (NOT (cancelled AND planned));
