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

-- Freight modes: full truckload (FTL) and part load (LTL).
--
-- Until now every load was implicitly FTL: one lorry, one lane, priced per
-- lorry. Part-load freight works differently. Each shipment is booked on its
-- own docket from an origin hub to a destination hub, many customers' dockets
-- are consolidated onto one vehicle, and each docket is billed to its customer
-- on its own chargeable weight, by zone. The vehicle is still paid for as a
-- vehicle -- so the customer receivable and the carrier payable are separate
-- documents and must stay that way.
--
-- Every existing row is FTL, which is what the defaults say. Nothing about an
-- FTL order, load or bill changes.

-- ---------------------------------------------------------------------------
-- The mode, on the order and on what planning builds from it
-- ---------------------------------------------------------------------------

-- RAIL and MILK_RUN are accepted by the constraint and refused by the service.
-- They are reserved vocabulary: a row written by a later release must not be
-- unreadable by this one's constraint.
ALTER TABLE sales_order
    ADD COLUMN freight_mode                TEXT NOT NULL DEFAULT 'FTL',
    ADD COLUMN origin_hub_terminal_id      UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    ADD COLUMN destination_hub_terminal_id UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    ADD CONSTRAINT sales_order_freight_mode_valid
        CHECK (freight_mode IN ('FTL', 'LTL', 'RAIL', 'MILK_RUN')),
    -- A part load with no hub pair cannot be consolidated or rated.
    ADD CONSTRAINT sales_order_ltl_names_hubs
        CHECK (freight_mode <> 'LTL'
            OR (origin_hub_terminal_id IS NOT NULL AND destination_hub_terminal_id IS NOT NULL));

-- An LTL consignment is a docket. It carries its customer and its hub pair so
-- that consolidation and rating can be decided from the docket alone, without
-- joining back through an order that may belong to somebody else's account.
ALTER TABLE consignment
    ADD COLUMN freight_mode                TEXT NOT NULL DEFAULT 'FTL',
    ADD COLUMN customer_partner_id         UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    ADD COLUMN origin_hub_terminal_id      UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    ADD COLUMN destination_hub_terminal_id UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    ADD CONSTRAINT consignment_freight_mode_valid
        CHECK (freight_mode IN ('FTL', 'LTL', 'RAIL', 'MILK_RUN')),
    ADD CONSTRAINT consignment_docket_names_hubs
        CHECK (freight_mode <> 'LTL'
            OR (origin_hub_terminal_id IS NOT NULL AND destination_hub_terminal_id IS NOT NULL
                AND customer_partner_id IS NOT NULL));

ALTER TABLE load_unit
    ADD COLUMN freight_mode TEXT NOT NULL DEFAULT 'FTL',
    ADD CONSTRAINT load_unit_freight_mode_valid
        CHECK (freight_mode IN ('FTL', 'LTL', 'RAIL', 'MILK_RUN'));

-- ---------------------------------------------------------------------------
-- LTL zones
-- ---------------------------------------------------------------------------

-- Which rating zone a terminal belongs to, and whether delivering there is
-- out of the delivery area (ODA). A hub is zoned so that a docket's hub pair
-- resolves to a zone pair; a customer site is listed when it is ODA, which is
-- what earns the surcharge.
--
-- Mutable master data, deliberately, and safe to be: a receivable records the
-- zones and the surcharge it was priced with, so re-zoning a terminal changes
-- what the next docket costs and nothing already billed.
CREATE TABLE ltl_zone_terminal
(
    tenant_id           UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    terminal_id         UUID        NOT NULL REFERENCES terminal (id) ON DELETE CASCADE,
    zone_code           TEXT        NOT NULL,
    out_of_delivery_area BOOLEAN    NOT NULL DEFAULT false,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, terminal_id),
    CONSTRAINT ltl_zone_code_present CHECK (btrim(zone_code) <> '')
);

-- ---------------------------------------------------------------------------
-- LTL rate cards
-- ---------------------------------------------------------------------------

-- One version of the price between two zones. Versioned by effective date and
-- immutable once written, for the reason tariff is (V13): a docket priced in
-- January is answerable with January's card. The version in force on a day is
-- the latest one that had started by then, so publishing a successor needs no
-- update to its predecessor at all.
CREATE TABLE ltl_rate_card
(
    id                           UUID PRIMARY KEY,
    tenant_id                    UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    code                         TEXT           NOT NULL,
    origin_zone                  TEXT           NOT NULL,
    destination_zone             TEXT           NOT NULL,
    currency                     TEXT           NOT NULL DEFAULT 'INR',
    effective_from               DATE           NOT NULL,
    -- PER_KG multiplies the chargeable weight; PER_CFT multiplies the
    -- chargeable weight expressed in cubic feet at kg_per_cft.
    rate_basis                   TEXT           NOT NULL,
    rate_amount                  NUMERIC(12, 4) NOT NULL,
    minimum_chargeable_weight_kg NUMERIC(14, 3) NOT NULL DEFAULT 0,
    -- The volumetric conversion: one cubic foot is billed as this many kg.
    kg_per_cft                   NUMERIC(8, 3)  NOT NULL,
    docket_charge                NUMERIC(12, 2) NOT NULL DEFAULT 0,
    oda_surcharge                NUMERIC(12, 2) NOT NULL DEFAULT 0,
    version                      BIGINT,
    created_at                   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ltl_rate_card_basis_valid CHECK (rate_basis IN ('PER_KG', 'PER_CFT')),
    CONSTRAINT ltl_rate_card_amounts_sane CHECK (
        rate_amount >= 0 AND minimum_chargeable_weight_kg >= 0 AND kg_per_cft > 0
            AND docket_charge >= 0 AND oda_surcharge >= 0)
);

-- The lookup ("the card in force between these zones on this day") and the
-- guarantee that one lane never has two versions starting on the same day.
-- Descending so the plan stops at the first row.
CREATE UNIQUE INDEX ltl_rate_card_lookup_idx
    ON ltl_rate_card (tenant_id, origin_zone, destination_zone, effective_from DESC);

CREATE RULE ltl_rate_card_no_update AS ON UPDATE TO ltl_rate_card DO INSTEAD NOTHING;
CREATE RULE ltl_rate_card_no_delete AS ON DELETE TO ltl_rate_card DO INSTEAD NOTHING;

-- ---------------------------------------------------------------------------
-- The customer receivable, one per docket
-- ---------------------------------------------------------------------------

-- What a docket is billed to its customer. Separate from freight_bill, which
-- is what the vehicle is paid to its carrier: one lorry, many receivables, one
-- payable. Everything the price was computed from is stored on the row, so a
-- dispute is argued from what was charged rather than from today's zones.
CREATE TABLE docket_receivable
(
    id                    UUID PRIMARY KEY,
    tenant_id             UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    docket_id             UUID           NOT NULL REFERENCES consignment (id) ON DELETE RESTRICT,
    docket_no             TEXT           NOT NULL,
    order_id              UUID           NOT NULL REFERENCES sales_order (id) ON DELETE RESTRICT,
    customer_partner_id   UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    status                TEXT           NOT NULL,
    currency              TEXT           NOT NULL DEFAULT 'INR',
    rate_card_id          UUID REFERENCES ltl_rate_card (id) ON DELETE RESTRICT,
    priced_on             DATE           NOT NULL,
    origin_zone           TEXT,
    destination_zone      TEXT,
    dead_weight_kg        NUMERIC(14, 3) NOT NULL,
    volume_cft            NUMERIC(14, 3),
    volumetric_weight_kg  NUMERIC(14, 3),
    chargeable_weight_kg  NUMERIC(14, 3),
    minimum_applied       BOOLEAN        NOT NULL DEFAULT false,
    out_of_delivery_area  BOOLEAN        NOT NULL DEFAULT false,
    rate_basis            TEXT,
    rate_amount           NUMERIC(12, 4),
    freight_amount        NUMERIC(14, 2) NOT NULL DEFAULT 0,
    docket_charge         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    oda_surcharge         NUMERIC(12, 2) NOT NULL DEFAULT 0,
    total_amount          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    reason                TEXT,
    version               BIGINT,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- One receivable per docket: billing a shipment twice is the bug this
    -- table must make impossible, whatever the event delivery does.
    CONSTRAINT docket_receivable_one_per_docket UNIQUE (tenant_id, docket_id),
    CONSTRAINT docket_receivable_no_unique UNIQUE (tenant_id, docket_no),
    CONSTRAINT docket_receivable_status_valid CHECK (status IN ('PRICED', 'UNPRICED')),
    CONSTRAINT docket_receivable_unpriced_explained CHECK (status = 'PRICED' OR reason IS NOT NULL),
    CONSTRAINT docket_receivable_adds_up CHECK (
        total_amount = freight_amount + docket_charge + oda_surcharge)
);

-- A customer's statement, paged by docket number.
CREATE INDEX docket_receivable_customer_idx
    ON docket_receivable (tenant_id, customer_partner_id, docket_no);

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'ltl_zone_terminal', 'ltl_rate_card', 'docket_receivable'
            ]
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
