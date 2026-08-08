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

-- Rating and settlement (vision document 3.9).
--
-- The one context where being wrong costs money directly. Everything here is
-- built around a single rule from 3.9.1: a rate that has been used to price a
-- movement is never edited. A tariff row is a *version*, superseded by writing
-- a new one, and a trip is priced against the version that was in force on the
-- day it was dispatched -- not the version in force when the invoice is looked
-- at, which may be months later and several rate revisions along.

-- ---------------------------------------------------------------------------
-- 3.9.1 Versioned rate cards
-- ---------------------------------------------------------------------------

CREATE TABLE tariff
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id             UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    code                    TEXT        NOT NULL,
    -- Whose rate this is. A rate card is a bilateral agreement; two vendors on
    -- the same lane are two cards, and pricing a trip against the wrong
    -- vendor's card is the most expensive mistake this table can make.
    vendor_partner_id       UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    origin_terminal_id      UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    destination_terminal_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    vehicle_type            TEXT        NOT NULL,
    currency                TEXT        NOT NULL DEFAULT 'INR',

    -- Dates, not timestamps. A rate change takes effect on a day, everywhere,
    -- regardless of the hour a lorry happened to leave -- and a timestamp would
    -- invite two trips dispatched the same morning onto different rate cards.
    effective_from          DATE        NOT NULL,
    -- Inclusive. NULL means open-ended: the version currently in force.
    effective_to            DATE,

    -- Accessorials (3.9.1) live on the card rather than in a separate table.
    -- They are negotiated in the same conversation as the base rate and change
    -- with it, so splitting them out would create a second thing to version and
    -- a second chance for the two to drift apart.
    detention_free_hours    NUMERIC(6, 2)  NOT NULL DEFAULT 0,
    detention_hourly_rate   NUMERIC(14, 2) NOT NULL DEFAULT 0,
    additional_drop_fee     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    -- The floor below which a movement is not worth running. A 40-tonne lorry
    -- carrying 300 kg still costs a full lorry to move.
    minimum_charge          NUMERIC(14, 2) NOT NULL DEFAULT 0,

    version                 BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tariff_period_sane CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT tariff_rates_non_negative CHECK (
        detention_free_hours >= 0 AND detention_hourly_rate >= 0
            AND additional_drop_fee >= 0 AND minimum_charge >= 0)
);

-- At most one open-ended version per vendor, lane and vehicle type.
--
-- This is the invariant that makes "the rate in force on a date" a well-defined
-- question. Closed versions are prevented from overlapping by the publishing
-- service, which closes the incumbent as it opens the successor; this index is
-- what stops two open versions existing at all, which no amount of application
-- care can guarantee under concurrency.
CREATE UNIQUE INDEX tariff_open_version_idx
    ON tariff (tenant_id, vendor_partner_id, origin_terminal_id,
               destination_terminal_id, vehicle_type)
    WHERE effective_to IS NULL;

-- The pricing lookup: one lane, one vehicle type, one date. effective_from
-- descending puts the newest applicable version first, so the query stops at
-- the first row rather than sorting a lane's whole rate history.
CREATE INDEX tariff_lookup_idx
    ON tariff (tenant_id, vendor_partner_id, origin_terminal_id,
               destination_terminal_id, vehicle_type, effective_from DESC);

-- Weight slabs. Half-open intervals: a slab covers [min, max), so 5000 kg falls
-- in the 5000-10000 slab and not in the 0-5000 one. Closed intervals would make
-- every boundary weight ambiguous, and the ambiguity would only surface as a
-- rounding argument on an invoice.
CREATE TABLE tariff_slab
(
    id            UUID PRIMARY KEY,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    tariff_id     UUID        NOT NULL REFERENCES tariff (id) ON DELETE CASCADE,
    min_weight_kg NUMERIC(14, 3) NOT NULL,
    -- NULL is the open top slab: everything heavier than min.
    max_weight_kg NUMERIC(14, 3),
    -- PER_KG multiplies by the chargeable weight; FLAT is the whole lorry.
    rate_basis    TEXT        NOT NULL,
    rate_amount   NUMERIC(14, 4) NOT NULL,
    version       BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tariff_slab_basis_valid CHECK (rate_basis IN ('PER_KG', 'FLAT')),
    CONSTRAINT tariff_slab_bounds_sane CHECK (
        min_weight_kg >= 0 AND (max_weight_kg IS NULL OR max_weight_kg > min_weight_kg)),
    CONSTRAINT tariff_slab_rate_non_negative CHECK (rate_amount >= 0),
    CONSTRAINT tariff_slab_floor_once UNIQUE (tariff_id, min_weight_kg)
);

CREATE INDEX tariff_slab_tariff_idx ON tariff_slab (tenant_id, tariff_id, min_weight_kg);

-- ---------------------------------------------------------------------------
-- Immutability
-- ---------------------------------------------------------------------------

-- The rule of 3.9.1, enforced by the database rather than by everybody
-- remembering. Without this, correcting "a typo in last month's rate" silently
-- reprices every settled invoice that referenced it, and the reprice is
-- invisible -- there is no diff, no audit entry, nothing to notice.
--
-- Closing a version is the one permitted update, because superseding is how a
-- new rate is published. A version already closed stays closed: reopening it
-- would put two cards in force on the same day.
CREATE OR REPLACE FUNCTION tariff_refuse_repricing() RETURNS TRIGGER AS
$$
BEGIN
    IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
        RAISE EXCEPTION 'Tariff % is already closed on %; a rate that has been used cannot be reopened',
            OLD.code, OLD.effective_to
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF ROW (NEW.tenant_id, NEW.org_unit_id, NEW.code, NEW.vendor_partner_id,
            NEW.origin_terminal_id, NEW.destination_terminal_id, NEW.vehicle_type,
            NEW.currency, NEW.effective_from, NEW.detention_free_hours,
            NEW.detention_hourly_rate, NEW.additional_drop_fee, NEW.minimum_charge)
        IS DISTINCT FROM
       ROW (OLD.tenant_id, OLD.org_unit_id, OLD.code, OLD.vendor_partner_id,
            OLD.origin_terminal_id, OLD.destination_terminal_id, OLD.vehicle_type,
            OLD.currency, OLD.effective_from, OLD.detention_free_hours,
            OLD.detention_hourly_rate, OLD.additional_drop_fee, OLD.minimum_charge)
    THEN
        RAISE EXCEPTION 'Tariff % cannot be edited; publish a new version instead', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tariff_immutable
    BEFORE UPDATE
    ON tariff
    FOR EACH ROW
EXECUTE FUNCTION tariff_refuse_repricing();

-- Slabs are wholly immutable -- there is no equivalent of closing a version,
-- because a slab belongs to a card that is itself a version.
CREATE RULE tariff_slab_no_update AS ON UPDATE TO tariff_slab DO INSTEAD NOTHING;

-- Deleting either would achieve by removal what the trigger refuses by edit.
CREATE RULE tariff_no_delete AS ON DELETE TO tariff DO INSTEAD NOTHING;
CREATE RULE tariff_slab_no_delete AS ON DELETE TO tariff_slab DO INSTEAD NOTHING;

-- ---------------------------------------------------------------------------
-- Fuel surcharge index
-- ---------------------------------------------------------------------------

-- A stored monthly figure rather than a call to a price API. An external
-- dependency on the pricing path would mean an invoice that cannot be raised
-- because somebody else's service is down, and a rate that silently changes
-- underneath a settled bill. A row is entered once a month by whoever reads
-- the notification; that is how the number arrives in practice anyway.
CREATE TABLE fuel_surcharge_index
(
    id              UUID PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- The first of the month it applies to.
    effective_month DATE        NOT NULL,
    surcharge_pct   NUMERIC(6, 3) NOT NULL,
    version         BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fuel_index_month_once UNIQUE (tenant_id, effective_month),
    CONSTRAINT fuel_index_is_first_of_month CHECK (EXTRACT(DAY FROM effective_month) = 1),
    -- Negative surcharges are real: fuel falls, and a contract that shares the
    -- rise usually shares the fall too.
    CONSTRAINT fuel_index_plausible CHECK (surcharge_pct BETWEEN -50 AND 100)
);

-- ---------------------------------------------------------------------------
-- 3.9.2 The freight bill
-- ---------------------------------------------------------------------------

CREATE TABLE freight_bill
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id          UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    trip_id              UUID        NOT NULL REFERENCES trip (id) ON DELETE RESTRICT,
    load_id              UUID        NOT NULL REFERENCES load_unit (id) ON DELETE RESTRICT,
    vendor_partner_id    UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    bill_no              TEXT        NOT NULL,
    status               TEXT        NOT NULL DEFAULT 'DRAFT',
    currency             TEXT        NOT NULL DEFAULT 'INR',

    -- The exact rate version this bill was priced against, kept so that a
    -- dispute months later can be answered with "this card, these slabs"
    -- rather than by re-running today's rates and getting a different number.
    tariff_id            UUID REFERENCES tariff (id) ON DELETE RESTRICT,
    -- The dispatch date. The whole reason 3.9.1 insists on versioning.
    priced_on            DATE,

    chargeable_weight_kg NUMERIC(14, 3),
    detention_hours      NUMERIC(8, 2),
    drop_count           INT,

    base_freight         NUMERIC(14, 2) NOT NULL DEFAULT 0,
    detention_amount     NUMERIC(14, 2) NOT NULL DEFAULT 0,
    multi_drop_amount    NUMERIC(14, 2) NOT NULL DEFAULT 0,
    fuel_surcharge_amount NUMERIC(14, 2) NOT NULL DEFAULT 0,
    -- What the platform says the movement cost.
    computed_amount      NUMERIC(14, 2) NOT NULL DEFAULT 0,
    -- What the vendor billed. NULL until they submit.
    claimed_amount       NUMERIC(14, 2),
    variance_amount      NUMERIC(14, 2),
    variance_pct         NUMERIC(8, 2),
    -- Why it was disputed, or on what grounds a dispute was later approved.
    resolution           TEXT,
    matched_at           TIMESTAMPTZ,

    version              BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT freight_bill_no_unique UNIQUE (tenant_id, bill_no),
    -- One bill per trip. Two would mean paying the same movement twice, which
    -- is the failure mode this table exists to prevent.
    CONSTRAINT freight_bill_trip_once UNIQUE (tenant_id, trip_id),
    CONSTRAINT freight_bill_status_valid CHECK (status IN
        ('DRAFT', 'UNPRICED', 'APPROVED_FOR_PAYMENT', 'DISPUTED', 'CANCELLED'))
);

CREATE INDEX freight_bill_status_idx ON freight_bill (tenant_id, status, created_at DESC);
CREATE INDEX freight_bill_vendor_idx ON freight_bill (tenant_id, vendor_partner_id, status);

-- The arithmetic, line by line.
--
-- A bill that carried only a total would be unarguable: a vendor disputing
-- 4 200 has no way to see that 3 000 of it is linehaul and 900 is detention
-- they did not think they had incurred. These lines are the entire content of
-- the conversation that follows a dispute.
CREATE TABLE freight_bill_line
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    bill_id     UUID        NOT NULL REFERENCES freight_bill (id) ON DELETE CASCADE,
    line_no     INT         NOT NULL,
    charge_type TEXT        NOT NULL,
    narrative   TEXT        NOT NULL,
    quantity    NUMERIC(14, 3),
    unit        TEXT,
    rate        NUMERIC(14, 4),
    amount      NUMERIC(14, 2) NOT NULL,
    version     BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT freight_bill_line_no_unique UNIQUE (bill_id, line_no),
    CONSTRAINT freight_bill_line_type_valid CHECK (charge_type IN
        ('BASE_FREIGHT', 'MINIMUM_CHARGE_UPLIFT', 'DETENTION',
         'ADDITIONAL_DROP', 'FUEL_SURCHARGE'))
);

CREATE INDEX freight_bill_line_bill_idx ON freight_bill_line (tenant_id, bill_id, line_no);

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Publishing a rate card is not the same authority as approving an invoice.
-- Whoever can change what a lane costs should not also be the person who signs
-- off that the vendor's bill matches it; that is the oldest separation of
-- duties there is.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'TARIFF_MANAGE', 'TARIFF', 'UPDATE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'tariff', 'tariff_slab', 'fuel_surcharge_index',
            'freight_bill', 'freight_bill_line'
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
