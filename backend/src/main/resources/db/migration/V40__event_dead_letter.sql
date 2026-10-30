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

-- Bounded retries for module events, then a parked list an operator can act on.
--
-- Spring Modulith's registry (event_publication, V1) keeps a listener's
-- publication incomplete for as long as the listener fails, and republishes it
-- on every restart without limit. Its shape belongs to Modulith and is not
-- changed here; these two tables sit beside it, keyed by the publication id.

-- What the registry does not keep: why a listener failed, how often and when.
-- One row per failing publication, written by the advisor around every
-- transactional listener and read by the retrier to pace its backoff. Removed
-- when the publication completes or is parked.
CREATE TABLE event_failure
(
    publication_id UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    listener_id    TEXT        NOT NULL,
    event_type     TEXT        NOT NULL,
    failures       INTEGER     NOT NULL,
    first_failed_at TIMESTAMPTZ NOT NULL,
    last_failed_at TIMESTAMPTZ NOT NULL,
    last_error     TEXT        NOT NULL,
    CONSTRAINT event_failure_failures_positive CHECK (failures > 0)
);

ALTER TABLE event_failure ENABLE ROW LEVEL SECURITY;
ALTER TABLE event_failure FORCE ROW LEVEL SECURITY;
CREATE POLICY event_failure_isolation ON event_failure
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- A publication that used up its attempts, taken out of the live registry so
-- it neither blocks nor re-fires on restart, and kept with everything needed to
-- replay it: the listener, the event type and the event exactly as serialized.
--
-- tenant_id is the tenant the event names. It is NULL only for an event that
-- names none -- every event in the platform carries one today, so that is a
-- defect in a future event rather than an expected case. Such a row is
-- invisible to every tenant through the API (the policy compares with NULL)
-- and is reported in the logs and the health detail; an operator handles it
-- in the database.
--
-- ENABLEd but, like tracking_link, deliberately not FORCEd: the two narrow
-- functions below run as the table owner -- parking an event with no tenant,
-- and counting parked events across tenants for the health check -- and FORCE
-- would filter them by a scope that does not exist. Every application
-- statement runs as lms_app (V3), to which the policy applies in full.
CREATE TABLE event_dead_letter
(
    id               UUID PRIMARY KEY,
    seq              BIGINT GENERATED ALWAYS AS IDENTITY,
    tenant_id        UUID REFERENCES tenant (id) ON DELETE CASCADE,
    listener_id      TEXT        NOT NULL,
    event_type       TEXT        NOT NULL,
    serialized_event TEXT        NOT NULL,
    publication_date TIMESTAMPTZ NOT NULL,
    attempts         INTEGER     NOT NULL,
    first_failed_at  TIMESTAMPTZ NOT NULL,
    last_failed_at   TIMESTAMPTZ NOT NULL,
    last_error       TEXT        NOT NULL,
    parked_at        TIMESTAMPTZ NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'PARKED',
    resolved_at      TIMESTAMPTZ,
    resolution_note  TEXT,
    version          BIGINT,
    CONSTRAINT event_dead_letter_status_valid CHECK (status IN ('PARKED', 'REPLAYED', 'DISCARDED')),
    CONSTRAINT event_dead_letter_attempts_positive CHECK (attempts > 0),
    CONSTRAINT event_dead_letter_discard_explained CHECK (
        status <> 'DISCARDED' OR resolution_note IS NOT NULL)
);

CREATE UNIQUE INDEX event_dead_letter_seq_idx ON event_dead_letter (seq);
CREATE INDEX event_dead_letter_listing_idx ON event_dead_letter (tenant_id, status, seq);

ALTER TABLE event_dead_letter ENABLE ROW LEVEL SECURITY;
CREATE POLICY event_dead_letter_isolation ON event_dead_letter
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- Parks an event that names no tenant. The only insert that bypasses the
-- policy, and it can only ever write a row no tenant can read.
CREATE OR REPLACE FUNCTION park_event_without_tenant(
    p_id UUID, p_listener_id TEXT, p_event_type TEXT, p_serialized_event TEXT,
    p_publication_date TIMESTAMPTZ, p_attempts INTEGER, p_last_error TEXT)
    RETURNS void
    LANGUAGE sql
    VOLATILE
    SECURITY DEFINER
    SET search_path = public, pg_temp
    AS $$
    INSERT INTO event_dead_letter
        (id, tenant_id, listener_id, event_type, serialized_event, publication_date,
         attempts, first_failed_at, last_failed_at, last_error, parked_at)
    VALUES (p_id, NULL, p_listener_id, p_event_type, p_serialized_event, p_publication_date,
            p_attempts, now(), now(), p_last_error, now())
    ON CONFLICT (id) DO NOTHING
$$;

-- How many events are parked, across tenants, for the health detail. A count,
-- never a row.
CREATE OR REPLACE FUNCTION count_parked_events()
    RETURNS bigint
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public, pg_temp
    AS $$
    SELECT count(*) FROM event_dead_letter WHERE status = 'PARKED'
$$;

REVOKE ALL ON FUNCTION park_event_without_tenant(UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, INTEGER, TEXT) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION park_event_without_tenant(UUID, TEXT, TEXT, TEXT, TIMESTAMPTZ, INTEGER, TEXT) TO lms_app;
REVOKE ALL ON FUNCTION count_parked_events() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION count_parked_events() TO lms_app;

-- Reading the parked list and acting on it are different jobs: replaying an
-- event re-runs a side effect, discarding one gives up on it.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'EVENT_DEAD_LETTER_READ', 'EVENT_DEAD_LETTER', 'READ'),
       (gen_random_uuid(), 'EVENT_DEAD_LETTER_MANAGE', 'EVENT_DEAD_LETTER', 'EXECUTE');
