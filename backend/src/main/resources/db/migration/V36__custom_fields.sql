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

-- Tenant-defined fields on core records.

-- What a tenant chose to track. A definition is never deleted: retiring it
-- (active = false) stops it being validated and required but keeps what was
-- already stored, so a field switched off by mistake can be switched back on.
CREATE TABLE custom_field_definition
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    entity_type   TEXT        NOT NULL,
    field_key     TEXT        NOT NULL,
    label         TEXT        NOT NULL,
    field_type    TEXT        NOT NULL,
    required      BOOLEAN     NOT NULL DEFAULT FALSE,
    options       TEXT[],
    display_order INTEGER     NOT NULL DEFAULT 0,
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    version       BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT custom_field_definition_entity_valid CHECK (entity_type IN (
        'PARTNER', 'VEHICLE', 'DRIVER', 'TERMINAL', 'ORDER', 'LOAD', 'TRIP')),
    CONSTRAINT custom_field_definition_type_valid CHECK (field_type IN (
        'TEXT', 'NUMBER', 'DATE', 'BOOLEAN', 'SELECT')),
    CONSTRAINT custom_field_definition_key_shape CHECK (field_key ~ '^[a-z][a-z0-9_]{1,39}$'),
    -- A choice field with nothing to choose from could never be filled in,
    -- and any other kind carrying options is a client that misunderstood.
    CONSTRAINT custom_field_definition_options_match_type CHECK (
        (field_type = 'SELECT') = (options IS NOT NULL AND cardinality(options) > 0))
);

CREATE UNIQUE INDEX custom_field_definition_key_idx
    ON custom_field_definition (tenant_id, entity_type, field_key);

ALTER TABLE custom_field_definition ENABLE ROW LEVEL SECURITY;
ALTER TABLE custom_field_definition FORCE ROW LEVEL SECURITY;
CREATE POLICY custom_field_definition_isolation ON custom_field_definition
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- The values of one record, as one document. Kept in a table of their own
-- rather than a column on each record's table so that no other module's
-- schema changes, and so that a record with no custom values costs nothing.
CREATE TABLE custom_field_value
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    entity_type TEXT        NOT NULL,
    entity_id   UUID        NOT NULL,
    field_values JSONB      NOT NULL,
    version     BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT custom_field_value_entity_valid CHECK (entity_type IN (
        'PARTNER', 'VEHICLE', 'DRIVER', 'TERMINAL', 'ORDER', 'LOAD', 'TRIP')),
    CONSTRAINT custom_field_value_is_object CHECK (jsonb_typeof(field_values) = 'object')
);

CREATE UNIQUE INDEX custom_field_value_record_idx
    ON custom_field_value (tenant_id, entity_type, entity_id);

ALTER TABLE custom_field_value ENABLE ROW LEVEL SECURITY;
ALTER TABLE custom_field_value FORCE ROW LEVEL SECURITY;
CREATE POLICY custom_field_value_isolation ON custom_field_value
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- Defining a field and filling one in are different jobs: whoever records a
-- driver's licence class should not be able to change what the form asks.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'CUSTOM_FIELD_READ', 'CUSTOM_FIELD', 'READ'),
       (gen_random_uuid(), 'CUSTOM_FIELD_WRITE', 'CUSTOM_FIELD', 'UPDATE'),
       (gen_random_uuid(), 'CUSTOM_FIELD_MANAGE', 'CUSTOM_FIELD', 'CREATE');
