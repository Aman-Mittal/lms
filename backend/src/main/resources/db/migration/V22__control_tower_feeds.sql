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

-- The control tower hears from multimodal, milk-run and warehousing.
--
-- Until now every ticket was about a trip. A missed rail connection, a short
-- delivery counted in at a dock, or a pick the bins could not fill is not
-- always about one -- so a ticket may now name some other subject instead: an
-- itinerary, a circuit run, an ASN or an outbound order.
--
-- Every rule V14 stated still holds, and one is tightened: a ticket with no
-- trip has no trip's money to freeze, so it never suspends settlement,
-- whatever its severity.

-- ---------------------------------------------------------------------------
-- A ticket without a trip
-- ---------------------------------------------------------------------------

ALTER TABLE exception_ticket
    ALTER COLUMN trip_id DROP NOT NULL,
    ALTER COLUMN trip_no DROP NOT NULL;

-- What the ticket is about when a trip is not the whole answer. The label is
-- copied for the same reason trip_no is: it is what a person reads aloud off
-- the board, and the board should not join four modules' tables to show it.
ALTER TABLE exception_ticket
    ADD COLUMN subject_type  TEXT,
    ADD COLUMN subject_id    UUID,
    ADD COLUMN subject_label TEXT;

ALTER TABLE exception_ticket
    -- A trip number without its trip, or the reverse, is a ticket nobody can
    -- follow up.
    ADD CONSTRAINT exception_ticket_trip_whole CHECK ((trip_id IS NULL) = (trip_no IS NULL)),
    ADD CONSTRAINT exception_ticket_subject_type_valid CHECK (
        subject_type IN ('ITINERARY', 'CIRCUIT_RUN', 'ASN', 'OUTBOUND_ORDER')),
    ADD CONSTRAINT exception_ticket_subject_whole CHECK (
        (subject_type IS NULL) = (subject_id IS NULL)
            AND (subject_type IS NULL) = (subject_label IS NULL)),
    -- Every ticket is about something.
    ADD CONSTRAINT exception_ticket_has_subject CHECK (
        trip_id IS NOT NULL OR subject_type IS NOT NULL),
    -- A person raises against a trip, and the corridor detector watches one.
    ADD CONSTRAINT exception_ticket_trip_sources_have_trip CHECK (
        source NOT IN ('MANUAL', 'ROUTE_DEVIATION') OR trip_id IS NOT NULL),
    -- A detector without a source key could raise the same occurrence twice.
    ADD CONSTRAINT exception_ticket_detector_keyed CHECK (
        source = 'MANUAL' OR source_key IS NOT NULL);

-- ---------------------------------------------------------------------------
-- Categories, sources and the severity they carry
-- ---------------------------------------------------------------------------

ALTER TABLE exception_ticket
    DROP CONSTRAINT exception_ticket_category_valid,
    DROP CONSTRAINT exception_ticket_source_valid,
    DROP CONSTRAINT exception_ticket_hold_matches_severity;

ALTER TABLE exception_ticket
    ADD CONSTRAINT exception_ticket_category_valid CHECK (category IN (
        'ACCIDENT', 'THEFT', 'BREAKDOWN',
        'ROUTE_DEVIATION', 'EXTENDED_STOPPAGE', 'TEMPERATURE_EXCURSION',
        'ETA_DELAY', 'DOCUMENT_MISMATCH',
        'MISSED_CONNECTION', 'MILK_RUN_STOP_SKIPPED', 'MILK_RUN_STOP_LATE',
        'RECEIPT_VARIANCE', 'PICK_SHORTFALL')),
    ADD CONSTRAINT exception_ticket_source_valid CHECK (source IN (
        'MANUAL', 'ROUTE_DEVIATION',
        'MISSED_CONNECTION', 'MILK_RUN_STOP_SKIPPED', 'MILK_RUN_STOP_LATE',
        'RECEIPT_VARIANCE', 'PICK_SHORTFALL')),
    -- The severity belongs to the category and is never chosen. The service
    -- has always derived it; the database now says so as well.
    ADD CONSTRAINT exception_ticket_severity_follows_category CHECK (
        severity = CASE
                       WHEN category IN ('ACCIDENT', 'THEFT', 'BREAKDOWN') THEN 'P1'
                       WHEN category IN ('ROUTE_DEVIATION', 'EXTENDED_STOPPAGE',
                                         'TEMPERATURE_EXCURSION', 'MISSED_CONNECTION') THEN 'P2'
                       ELSE 'P3'
            END),
    -- The 3.8 rule, narrowed: a critical ticket holds up the money of its
    -- trip, so a ticket with no trip holds up nothing.
    ADD CONSTRAINT exception_ticket_hold_matches_severity CHECK (
        suspends_settlement = (severity IN ('P1', 'P2') AND trip_id IS NOT NULL));
