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

-- Issue #229: the sales funnel in front of an order, and bulk intake of orders.
--
-- An enquiry is a customer's request for a price: lines (origin, destination,
-- load type, quantity, wanted dates), a rate suggested from the customer's
-- contract lanes where one matches, quotes issued per line, and an outcome per
-- line (won, partly won, lost). Won lines become one DRAFT order. The deadline
-- is fixed when the enquiry is created; it is not a sliding window.
--
-- Bulk intake keeps one row per idempotency key so that an item sent twice
-- (a retried batch, a file uploaded again) finds the order it already made.

-- ---------------------------------------------------------------------------
-- Enquiries
-- ---------------------------------------------------------------------------

CREATE TABLE enquiry
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id         UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    enquiry_no          TEXT        NOT NULL,
    customer_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    -- The customer's own reference, when they gave one.
    reference           TEXT,
    notes               TEXT,
    -- OPEN while any line is undecided; WON, PARTIAL or LOST once every line is;
    -- CONVERTED when its won lines became an order; CANCELLED.
    status              TEXT        NOT NULL,
    -- Fixed at creation from the tenant's deadline policy, never moved.
    deadline_at         TIMESTAMPTZ NOT NULL,
    cancel_reason       TEXT,
    cancelled_at        TIMESTAMPTZ,
    order_id            UUID REFERENCES sales_order (id) ON DELETE RESTRICT,
    converted_at        TIMESTAMPTZ,
    created_by          UUID,
    version             BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT enquiry_no_unique UNIQUE (tenant_id, enquiry_no),
    CONSTRAINT enquiry_status_valid CHECK (status IN ('OPEN', 'WON', 'PARTIAL', 'LOST', 'CONVERTED', 'CANCELLED')),
    CONSTRAINT enquiry_cancelled_whole CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)),
    CONSTRAINT enquiry_converted_whole CHECK (
        (status = 'CONVERTED') = (order_id IS NOT NULL AND converted_at IS NOT NULL)),
    CONSTRAINT enquiry_reference_bounded CHECK (reference IS NULL OR length(reference) <= 120),
    CONSTRAINT enquiry_notes_bounded CHECK (notes IS NULL OR length(notes) <= 1000)
);

-- The list, newest first (keyset on created_at, id), with or without a status.
CREATE INDEX enquiry_list_idx ON enquiry (tenant_id, created_at DESC, id DESC);
CREATE INDEX enquiry_status_idx ON enquiry (tenant_id, status, created_at DESC, id DESC);
CREATE INDEX enquiry_customer_idx ON enquiry (tenant_id, customer_partner_id, created_at DESC, id DESC);
-- What the deadline sweep reads: open enquiries by the day they fall due.
CREATE INDEX enquiry_open_deadline_idx ON enquiry (tenant_id, deadline_at, id) WHERE status = 'OPEN';
-- One order per enquiry, and an order knows at most one enquiry.
CREATE UNIQUE INDEX enquiry_order_idx ON enquiry (tenant_id, order_id) WHERE order_id IS NOT NULL;

CREATE TABLE enquiry_line
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    enquiry_id              UUID           NOT NULL REFERENCES enquiry (id) ON DELETE CASCADE,
    line_no                 INT            NOT NULL,
    origin_terminal_id      UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    destination_terminal_id UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    load_type               TEXT,
    material                TEXT,
    quantity                NUMERIC(14, 3) NOT NULL,
    uom                     TEXT           NOT NULL,
    weight_kg               NUMERIC(14, 3),
    wanted_pickup_on        DATE,
    wanted_delivery_on      DATE,
    -- OPEN until decided; the rest are final. A line is cancelled only while open.
    status                  TEXT           NOT NULL DEFAULT 'OPEN',
    won_quantity            NUMERIC(14, 3),
    lost_reason             TEXT,
    outcome_at              TIMESTAMPTZ,
    -- Cost discovery: the contract lane and rate in force on the wanted pickup day.
    suggested_contract_id   UUID,
    suggested_lane_code     TEXT,
    suggested_rate_basis    TEXT,
    suggested_rate          NUMERIC(14, 4),
    suggested_amount        NUMERIC(14, 2),
    currency                TEXT           NOT NULL DEFAULT 'INR',
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT enquiry_line_no_unique UNIQUE (enquiry_id, line_no),
    CONSTRAINT enquiry_line_status_valid CHECK (status IN ('OPEN', 'WON', 'PARTIAL', 'LOST', 'CANCELLED')),
    CONSTRAINT enquiry_line_quantity_positive CHECK (quantity > 0 AND (weight_kg IS NULL OR weight_kg > 0)),
    CONSTRAINT enquiry_line_won_valid CHECK (
        (status = 'WON' AND won_quantity = quantity)
            OR (status = 'PARTIAL' AND won_quantity > 0 AND won_quantity < quantity)
            OR (status NOT IN ('WON', 'PARTIAL') AND won_quantity IS NULL)),
    CONSTRAINT enquiry_line_lost_reason CHECK ((status = 'LOST') = (lost_reason IS NOT NULL)),
    CONSTRAINT enquiry_line_dates_ordered CHECK (
        wanted_pickup_on IS NULL OR wanted_delivery_on IS NULL OR wanted_delivery_on >= wanted_pickup_on)
);

CREATE INDEX enquiry_line_enquiry_idx ON enquiry_line (tenant_id, enquiry_id, line_no);

CREATE TABLE enquiry_quote
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    enquiry_id  UUID           NOT NULL REFERENCES enquiry (id) ON DELETE CASCADE,
    line_id     UUID           NOT NULL REFERENCES enquiry_line (id) ON DELETE CASCADE,
    -- 1 for the first quote on the line; a revision is the next number.
    revision    INT            NOT NULL,
    amount      NUMERIC(14, 2) NOT NULL,
    currency    TEXT           NOT NULL DEFAULT 'INR',
    valid_until TIMESTAMPTZ    NOT NULL,
    note        TEXT,
    -- ISSUED is the one a customer holds; a revision supersedes it; revoked is withdrawn.
    status      TEXT           NOT NULL,
    revoked_at  TIMESTAMPTZ,
    revoke_reason TEXT,
    issued_by   UUID,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT enquiry_quote_revision_unique UNIQUE (line_id, revision),
    CONSTRAINT enquiry_quote_status_valid CHECK (status IN ('ISSUED', 'SUPERSEDED', 'REVOKED')),
    CONSTRAINT enquiry_quote_amount_positive CHECK (amount > 0),
    CONSTRAINT enquiry_quote_revoked_whole CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)),
    CONSTRAINT enquiry_quote_note_bounded CHECK (note IS NULL OR length(note) <= 500)
);

-- At most one quote a customer holds per line.
CREATE UNIQUE INDEX enquiry_quote_live_idx ON enquiry_quote (tenant_id, line_id) WHERE status = 'ISSUED';
CREATE INDEX enquiry_quote_enquiry_idx ON enquiry_quote (tenant_id, enquiry_id, line_id, revision);

-- ---------------------------------------------------------------------------
-- Bulk intake: one row per idempotency key
-- ---------------------------------------------------------------------------

CREATE TABLE bulk_intake_key
(
    tenant_id       UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    idempotency_key TEXT        NOT NULL,
    -- A digest of the item as sent: the same key with other content is a client defect.
    request_hash    TEXT        NOT NULL,
    order_id        UUID        NOT NULL REFERENCES sales_order (id) ON DELETE RESTRICT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, idempotency_key),
    CONSTRAINT bulk_intake_key_bounded CHECK (btrim(idempotency_key) <> '' AND length(idempotency_key) <= 255)
);

CREATE INDEX bulk_intake_key_order_idx ON bulk_intake_key (tenant_id, order_id);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['enquiry', 'enquiry_line', 'enquiry_quote', 'bulk_intake_key']
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
