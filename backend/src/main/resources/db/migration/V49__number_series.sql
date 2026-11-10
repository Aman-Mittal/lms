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

-- Document number series: a tenant decides how its documents are numbered.
--
-- A series belongs to one document type and one scope: the whole tenant
-- (org_unit_id NULL) or one organisational unit and everything beneath it.
-- When a document is numbered, the active series of its type whose unit is
-- the deepest ancestor of (or equal to) the document's unit wins; the tenant
-- default answers when no unit series covers it. A number is
--   prefix [separator year-code] separator zero-padded-counter
-- and the counter restarts each financial year, which begins on the first of
-- year_start_month in time_zone. A series without a year code never restarts:
-- its counter is kept under financial year 0.

CREATE TABLE number_series
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    code              TEXT        NOT NULL,
    document_type     TEXT        NOT NULL,
    org_unit_id       UUID REFERENCES org_unit (id) ON DELETE RESTRICT,
    prefix            TEXT        NOT NULL,
    year_code         TEXT        NOT NULL,
    separator         TEXT        NOT NULL,
    counter_length    INTEGER     NOT NULL,
    year_start_month  INTEGER     NOT NULL DEFAULT 4,
    time_zone         TEXT        NOT NULL DEFAULT 'UTC',
    reuse_released    BOOLEAN     NOT NULL DEFAULT FALSE,
    active            BOOLEAN     NOT NULL DEFAULT TRUE,
    description       TEXT,
    version           BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT number_series_code_unique UNIQUE (tenant_id, code),
    -- Two series of one type with the same prefix could hand out the same
    -- number, and a number could not be traced back to one series.
    CONSTRAINT number_series_prefix_unique UNIQUE (tenant_id, document_type, prefix),
    CONSTRAINT number_series_type_valid CHECK (document_type IN
        ('ORDER', 'CONSIGNMENT_NOTE', 'DOCKET', 'TRIP', 'GATE_PASS', 'LOAD', 'INVOICE')),
    CONSTRAINT number_series_year_code_valid CHECK (year_code IN ('NONE', 'START_YEAR', 'SPAN')),
    CONSTRAINT number_series_separator_valid CHECK (separator IN ('', '-', '/', '_', '.')),
    CONSTRAINT number_series_prefix_safe CHECK (prefix ~ '^[A-Z0-9][A-Z0-9/-]{0,15}$'),
    CONSTRAINT number_series_counter_length_valid CHECK (counter_length BETWEEN 1 AND 12),
    CONSTRAINT number_series_month_valid CHECK (year_start_month BETWEEN 1 AND 12)
);

-- At most one active series per type and scope, so "the most specific active
-- series" always names one. The all-zero uuid stands for the tenant scope.
CREATE UNIQUE INDEX number_series_active_scope_idx
    ON number_series (tenant_id, document_type,
                      coalesce(org_unit_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE active;

-- Listing by type in code order.
CREATE INDEX number_series_type_code_idx ON number_series (tenant_id, document_type, code);

-- The counter of one series in one financial year. Advanced with
-- INSERT ... ON CONFLICT DO UPDATE ... RETURNING in the transaction that
-- creates the document: the row lock serialises concurrent draws, and a
-- rolled-back document gives its number back.
CREATE TABLE number_series_counter
(
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    series_id      UUID        NOT NULL REFERENCES number_series (id) ON DELETE CASCADE,
    financial_year INTEGER     NOT NULL,
    last_value     BIGINT      NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, series_id, financial_year),
    CONSTRAINT number_series_counter_positive CHECK (last_value > 0)
);

-- Numbers given back when their document was cancelled. Kept whether or not
-- the series reuses them: the row is the record of the release. A series that
-- reuses takes the lowest unused one before advancing its counter, and stamps
-- reused_at; releasing that number again clears the stamp.
CREATE TABLE number_series_release
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    series_id      UUID        NOT NULL REFERENCES number_series (id) ON DELETE CASCADE,
    financial_year INTEGER     NOT NULL,
    counter_value  BIGINT      NOT NULL,
    number         TEXT        NOT NULL,
    reason         TEXT        NOT NULL,
    released_by    UUID,
    released_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reused_at      TIMESTAMPTZ,
    CONSTRAINT number_series_release_unique
        UNIQUE (tenant_id, series_id, financial_year, counter_value)
);

-- The pool: unused releases of a series and year, lowest first.
CREATE INDEX number_series_release_pool_idx
    ON number_series_release (tenant_id, series_id, financial_year, counter_value)
    WHERE reused_at IS NULL;

-- The release log of one series, newest first.
CREATE INDEX number_series_release_log_idx
    ON number_series_release (tenant_id, series_id, released_at DESC, id DESC);

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['number_series', 'number_series_counter', 'number_series_release']
            LOOP
                EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
                EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
                EXECUTE format(
                        'CREATE POLICY %I ON %I USING (tenant_id = app_current_tenant()) '
                            || 'WITH CHECK (tenant_id = app_current_tenant())',
                        t || '_isolation', t);
            END LOOP;
    END
$$;

-- Administering series is one right: whoever sets the format also decides
-- when it applies and may give numbers back.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'NUMBER_SERIES_MANAGE', 'NUMBER_SERIES', 'UPDATE');
