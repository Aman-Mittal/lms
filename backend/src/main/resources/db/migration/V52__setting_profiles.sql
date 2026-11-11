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


-- Setting profiles: values of registered settings (the registry is code, in
-- each owning module), grouped per scope. A tenant's profile is for the whole
-- tenant (ORG_DEFAULT, no scope id), an organisational unit, a customer, a
-- vehicle, or one user's own preferences. scope_id is polymorphic, so it has
-- no foreign key: the application looks the record up under row-level
-- security before it accepts one, which a foreign key -- checked as the table
-- owner -- would not do anyway.
--
-- A PLATFORM profile belongs to no tenant (tenant_id NULL): the platform
-- operator's values for every tenant, globally or for one jurisdiction (a
-- country, optionally a state). This is the one deliberate exception to
-- "tenant_id NOT NULL": such a row is not any tenant's data. Every tenant may
-- read platform rows; they are written only in a transaction that has set
-- app.platform_write, which the application does only for a caller holding
-- PLATFORM_SETTINGS_MANAGE.
--
-- A key may hold several versions in one profile, each from its own day
-- (effective_from; NULL for an undated value, which applies since always).
-- The effective value of a key for a context and a day is: per profile, the
-- latest version in effect on that day; then the most specific profile,
-- PLATFORM (global < country < state) < ORG_DEFAULT < ORG_UNIT (deeper wins)
-- < VEHICLE < CUSTOMER < USER, each key allowing only the scope types its
-- definition lists. One profile per scope.

CREATE TABLE setting_profile
(
    id         UUID PRIMARY KEY,
    tenant_id  UUID REFERENCES tenant (id) ON DELETE CASCADE,
    scope_type TEXT        NOT NULL,
    scope_id   UUID,
    country    TEXT,
    state      TEXT,
    name       TEXT        NOT NULL,
    -- 0 is never stored: the aggregate's version is a primitive, 0 meaning new.
    version    BIGINT      NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT setting_profile_scope_type_valid
        CHECK (scope_type IN ('PLATFORM', 'ORG_DEFAULT', 'ORG_UNIT', 'VEHICLE', 'CUSTOMER', 'USER')),
    CONSTRAINT setting_profile_platform_has_no_tenant CHECK ((scope_type = 'PLATFORM') = (tenant_id IS NULL)),
    CONSTRAINT setting_profile_scope_id_shape
        CHECK ((scope_type IN ('PLATFORM', 'ORG_DEFAULT')) = (scope_id IS NULL)),
    CONSTRAINT setting_profile_jurisdiction_shape CHECK (
        (scope_type = 'PLATFORM' OR (country IS NULL AND state IS NULL))
        AND (country IS NULL OR country ~ '^[A-Z]{2}$')
        AND (state IS NULL OR (country IS NOT NULL AND state ~ '^[A-Z0-9]{1,3}$'))),
    CONSTRAINT setting_profile_name_length CHECK (length(name) BETWEEN 1 AND 100)
);

-- One profile per scope of a tenant; the all-zero uuid stands for the tenant
-- scope. Also what a resolution reads a tenant's profiles through.
CREATE UNIQUE INDEX setting_profile_scope_idx
    ON setting_profile (tenant_id, scope_type,
                        coalesce(scope_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE tenant_id IS NOT NULL;

-- One platform profile per jurisdiction.
CREATE UNIQUE INDEX setting_profile_platform_idx
    ON setting_profile (coalesce(country, ''), coalesce(state, ''))
    WHERE tenant_id IS NULL;

-- The administrative listing: keyset on (scope_type, created_at, id).
CREATE INDEX setting_profile_list_idx ON setting_profile (tenant_id, scope_type, created_at, id);

CREATE TABLE setting_value
(
    tenant_id      UUID REFERENCES tenant (id) ON DELETE CASCADE,
    profile_id     UUID  NOT NULL REFERENCES setting_profile (id) ON DELETE CASCADE,
    setting_key    TEXT  NOT NULL,
    effective_from DATE,
    value          JSONB NOT NULL,
    CONSTRAINT setting_value_key_shape CHECK (setting_key ~ '^[a-z][a-zA-Z0-9]*(\.[a-z][a-zA-Z0-9]*)+$'
        AND length(setting_key) <= 100)
);

-- One version per key and day in a profile; an undated version at most once.
-- Led by profile_id rather than tenant_id because a platform profile's rows
-- have no tenant, and a profile id is one tenant's (or the platform's) alone.
CREATE UNIQUE INDEX setting_value_version_idx
    ON setting_value (profile_id, setting_key, coalesce(effective_from, DATE '0001-01-01'));

-- Whether the current transaction writes platform rows. Set only by the
-- settings module, after checking PLATFORM_SETTINGS_MANAGE; SET LOCAL ends it
-- with the transaction.
CREATE FUNCTION app_platform_write() RETURNS BOOLEAN
    LANGUAGE sql
    STABLE AS
$$
SELECT coalesce(current_setting('app.platform_write', true), '') = 'on'
$$;

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['setting_profile', 'setting_value']
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format(
                        'CREATE POLICY %I ON %I USING (tenant_id = app_current_tenant()'
                            || ' OR (tenant_id IS NULL AND app_platform_write()))'
                            || ' WITH CHECK (tenant_id = app_current_tenant()'
                            || ' OR (tenant_id IS NULL AND app_platform_write()))',
                        t || '_isolation', t);
                EXECUTE format('CREATE POLICY %I ON %I FOR SELECT USING (tenant_id IS NULL)',
                               t || '_platform_read', t);
            END LOOP;
    END
$$;

-- Reading profiles and effective values, and administering a tenant's. A
-- user's own preferences need neither. PLATFORM_SETTINGS_MANAGE is the
-- platform operator's: values for every tenant. No role is granted it here;
-- the only role that holds it is the bootstrap (demo) administrator, whom the
-- seeder grants every permission. A deployment that creates tenant roles must
-- not grant it to one.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'SETTINGS_READ', 'SETTINGS', 'READ'),
       (gen_random_uuid(), 'SETTINGS_MANAGE', 'SETTINGS', 'UPDATE'),
       (gen_random_uuid(), 'PLATFORM_SETTINGS_MANAGE', 'PLATFORM_SETTINGS', 'UPDATE');

-- The two one-off settings tables become organisation-default values. Their
-- rows are copied, not moved: order_close_setting (V50) and detector_setting
-- (V35) are read by nothing from here on and are left for a later migration
-- to drop once no deployment can roll back to code that reads them.
INSERT INTO setting_profile (id, tenant_id, scope_type, scope_id, name, version, created_at, updated_at)
SELECT gen_random_uuid(), t.tenant_id, 'ORG_DEFAULT', NULL, 'Organisation default', 1, now(), now()
  FROM (SELECT tenant_id FROM order_close_setting
        UNION
        SELECT tenant_id FROM detector_setting) t;

INSERT INTO setting_value (tenant_id, profile_id, setting_key, value)
SELECT s.tenant_id, p.id, k.setting_key, k.value
  FROM order_close_setting s
  JOIN setting_profile p ON p.tenant_id = s.tenant_id AND p.scope_type = 'ORG_DEFAULT'
 CROSS JOIN LATERAL (VALUES ('order.close.tolerancePercent', to_jsonb(trim_scale(s.tolerance_percent))),
                            ('order.close.autoFulfil', to_jsonb(s.auto_fulfil))) AS k (setting_key, value);

-- Minutes as the ISO-8601 duration Java writes for them: PT1H, PT15M, PT1H30M.
INSERT INTO setting_value (tenant_id, profile_id, setting_key, value)
SELECT s.tenant_id, p.id, k.setting_key,
       to_jsonb('PT' || CASE WHEN k.minutes / 60 > 0 THEN (k.minutes / 60) || 'H' ELSE '' END
                    || CASE WHEN k.minutes % 60 > 0 THEN (k.minutes % 60) || 'M' ELSE '' END)
  FROM detector_setting s
  JOIN setting_profile p ON p.tenant_id = s.tenant_id AND p.scope_type = 'ORG_DEFAULT'
 CROSS JOIN LATERAL (VALUES ('controltower.detector.etaSlipTolerance', s.eta_slip_minutes),
                            ('controltower.detector.temperatureExcursionTolerance',
                             s.temperature_excursion_minutes)) AS k (setting_key, minutes);
