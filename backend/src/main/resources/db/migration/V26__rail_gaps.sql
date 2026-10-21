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

-- Closing the gaps V19 left in rail: railheads as a kind of terminal, rail
-- rate cards, and the index the missed-connection sweep reads.

-- ---------------------------------------------------------------------------
-- Railheads
-- ---------------------------------------------------------------------------

-- V19 let a rail leg start at any terminal, so a rake could be "loaded" at a
-- customer's gate. A railhead is now its own functional category, and a rail
-- leg must run from one to another. The original constraint is replaced
-- rather than edited, because V4 has already run everywhere.
ALTER TABLE terminal DROP CONSTRAINT terminal_category_valid;
ALTER TABLE terminal
    ADD CONSTRAINT terminal_functional_category_valid
        CHECK (functional_category IN ('PLANT', 'WAREHOUSE', 'PORT', 'HUB', 'TOLL_PLAZA',
                                       'CUSTOMER_SITE', 'RAILHEAD'));

-- ---------------------------------------------------------------------------
-- Rail rate cards
-- ---------------------------------------------------------------------------

-- One version of one rail carrier's tariff between two railheads. Versioned
-- and immutable for the reason the road tariff of V13 is: a leg priced in
-- January must still be explicable after the rate changed in February.
CREATE TABLE rail_tariff
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    code                    TEXT           NOT NULL,
    carrier_partner_id      UUID           NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    origin_terminal_id      UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    destination_terminal_id UUID           NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    currency                TEXT           NOT NULL DEFAULT 'INR',
    -- Railways quote by what is booked. A card may price only one of the two;
    -- a booking in the unit it does not price is refused rather than guessed.
    per_wagon_rate          NUMERIC(14, 2),
    per_container_rate      NUMERIC(14, 2),
    -- The floor for a booking: two containers still occupy a slot on a rake.
    minimum_charge          NUMERIC(14, 2) NOT NULL DEFAULT 0,
    -- Dates, as on the road tariff: a rate takes effect on a day.
    effective_from          DATE           NOT NULL,
    -- Inclusive. NULL while this is the version in force.
    effective_to            DATE,
    created_by              TEXT           NOT NULL,
    version                 BIGINT,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT rail_tariff_period_sane CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT rail_tariff_rates_non_negative CHECK (
        (per_wagon_rate IS NULL OR per_wagon_rate >= 0)
            AND (per_container_rate IS NULL OR per_container_rate >= 0)
            AND minimum_charge >= 0),
    CONSTRAINT rail_tariff_prices_something CHECK (
        per_wagon_rate IS NOT NULL OR per_container_rate IS NOT NULL),
    CONSTRAINT rail_tariff_terminals_differ CHECK (origin_terminal_id <> destination_terminal_id)
);

-- At most one open version per carrier and railhead pair: what makes "the
-- card in force on a date" a single answer under concurrent publishing.
CREATE UNIQUE INDEX rail_tariff_open_version_idx
    ON rail_tariff (tenant_id, carrier_partner_id, origin_terminal_id, destination_terminal_id)
    WHERE effective_to IS NULL;

-- Pricing a leg: one carrier, one pair, one date, newest version first.
CREATE INDEX rail_tariff_lookup_idx
    ON rail_tariff (tenant_id, carrier_partner_id, origin_terminal_id,
                    destination_terminal_id, effective_from DESC);

-- The listing: newest first, keyset on (created_at, id).
CREATE INDEX rail_tariff_listing_idx ON rail_tariff (tenant_id, created_at DESC, id DESC);

-- Closing a version is the only permitted change, and only once.
CREATE OR REPLACE FUNCTION rail_tariff_refuse_repricing() RETURNS TRIGGER AS
$$
BEGIN
    IF OLD.effective_to IS NOT NULL AND NEW.effective_to IS DISTINCT FROM OLD.effective_to THEN
        RAISE EXCEPTION 'Rail tariff % is already closed on %; it cannot be reopened',
            OLD.code, OLD.effective_to
            USING ERRCODE = 'restrict_violation';
    END IF;

    IF ROW (NEW.tenant_id, NEW.code, NEW.carrier_partner_id, NEW.origin_terminal_id,
            NEW.destination_terminal_id, NEW.currency, NEW.per_wagon_rate,
            NEW.per_container_rate, NEW.minimum_charge, NEW.effective_from, NEW.created_by)
        IS DISTINCT FROM
       ROW (OLD.tenant_id, OLD.code, OLD.carrier_partner_id, OLD.origin_terminal_id,
            OLD.destination_terminal_id, OLD.currency, OLD.per_wagon_rate,
            OLD.per_container_rate, OLD.minimum_charge, OLD.effective_from, OLD.created_by)
    THEN
        RAISE EXCEPTION 'Rail tariff % cannot be edited; publish a new version instead', OLD.code
            USING ERRCODE = 'restrict_violation';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rail_tariff_immutable
    BEFORE UPDATE
    ON rail_tariff
    FOR EACH ROW
EXECUTE FUNCTION rail_tariff_refuse_repricing();

CREATE RULE rail_tariff_no_delete AS ON DELETE TO rail_tariff DO INSTEAD NOTHING;

-- ---------------------------------------------------------------------------
-- Legs priced from a card
-- ---------------------------------------------------------------------------

-- The card that priced a rail leg, or NULL when the planner gave the cost.
ALTER TABLE itinerary_leg
    ADD COLUMN rail_tariff_id UUID REFERENCES rail_tariff (id) ON DELETE RESTRICT;
ALTER TABLE itinerary_leg
    ADD CONSTRAINT itinerary_leg_tariff_rail_only CHECK (rail_tariff_id IS NULL OR mode = 'RAIL');

-- ---------------------------------------------------------------------------
-- The missed-connection sweep
-- ---------------------------------------------------------------------------

-- Legs that are waiting for their predecessor: not started, not yet marked,
-- and not first. Partial, so the index holds the open working set and not
-- every leg ever run; the sweep's predicate repeats these literals so the
-- planner can prove the index covers it.
CREATE INDEX itinerary_leg_awaiting_start_idx
    ON itinerary_leg (tenant_id, planned_departure_at)
    WHERE departed_at IS NULL AND connection_missed_at IS NULL AND seq > 1;

-- ---------------------------------------------------------------------------
-- Where a rail rate card may be filed
-- ---------------------------------------------------------------------------

-- Publishing a card creates a record, so it is held to the caller's subtree
-- like every other create (V24). A card has no unit of its own; it is filed
-- where its origin railhead is. Replaces V24's definition with one rule
-- added; the V16 trigger calls the function by name, so new tenants get it.
CREATE OR REPLACE FUNCTION seed_default_access_policies(p_tenant UUID) RETURNS void
    LANGUAGE sql
    AS $$
    INSERT INTO access_policy (id, tenant_id, code, action, effect, description, condition, version)
    SELECT gen_random_uuid(), p_tenant, rule.code, rule.action, 'DENY', rule.description,
           -- NULL stands for the subtree rule, written once rather than
           -- fourteen times: refuse when the record's unit is not the
           -- caller's own or beneath it.
           coalesce(rule.condition,
                    '{"allOf": [{"attr": "resource.orgPath", "op": "present"},
                                {"attr": "subject.orgPath", "op": "present"},
                                {"not": {"attr": "resource.orgPath", "op": "startsWith",
                                         "ref": "subject.orgPath"}}]}'::jsonb),
           0
      FROM (VALUES
        ('invoice-approval-limit', 'INVOICE_APPROVE',
         'An invoice may be approved, or a dispute settled, only up to the approver''s limit.',
         '{"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}'::jsonb),
        ('exception-close-holding-limit', 'EXCEPTION_CLOSE',
         'Closing a ticket that is holding settlement releases money, so it needs an approval limit of at least 100000.',
         '{"allOf": [{"attr": "resource.suspendsSettlement", "op": "eq", "value": true},
                     {"attr": "subject.approvalLimit", "op": "lt", "value": 100000}]}'::jsonb),
        ('partner-write-org-subtree', 'PARTNER_CREATE',
         'A partner may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('vehicle-write-org-subtree', 'VEHICLE_CREATE',
         'A vehicle may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('driver-write-org-subtree', 'DRIVER_CREATE',
         'A driver may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('terminal-write-org-subtree', 'TERMINAL_CREATE',
         'A terminal may be registered only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('order-write-org-subtree', 'ORDER_CREATE',
         'An order may be raised only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('load-write-org-subtree', 'LOAD_CREATE',
         'A load may be opened only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('trip-write-org-subtree', 'TRIP_EXECUTE',
         'A trip may be raised only against a load in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('routing-guide-write-org-subtree', 'ROUTING_GUIDE_MANAGE',
         'A routing guide may be defined only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('milk-run-write-org-subtree', 'MILKRUN_PLAN',
         'A milk run may be published only from a plant in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('itinerary-write-org-subtree', 'MULTIMODAL_PLAN',
         'An itinerary may be planned only from a terminal in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('warehouse-setup-org-subtree', 'WAREHOUSE_SETUP',
         'Bins may be laid out, and SKUs catalogued for an owner, only in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('asn-write-org-subtree', 'WAREHOUSE_OPERATE',
         'An ASN may be filed only at a warehouse in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('client-contract-write-org-subtree', 'CLIENT_CONTRACT_MANAGE',
         'A client contract may be published only under the caller''s own organisational unit or one beneath it.',
         NULL),
        ('rail-tariff-write-org-subtree', 'TARIFF_MANAGE',
         'A rail rate card may be published only from a railhead in the caller''s own organisational unit or one beneath it.',
         NULL)
      ) AS rule (code, action, description, condition)
    ON CONFLICT (tenant_id, code) DO NOTHING
$$;

-- Existing tenants, as V24 seeded them: each under its own scope, scope
-- cleared after. ON CONFLICT leaves every existing row alone.
DO
$$
    DECLARE
        t UUID;
    BEGIN
        FOR t IN SELECT id FROM tenant
            LOOP
                PERFORM set_config('app.tenant_id', t::text, true);
                PERFORM seed_default_access_policies(t);
            END LOOP;
        PERFORM set_config('app.tenant_id', '', true);
    END
$$;

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

ALTER TABLE rail_tariff ENABLE ROW LEVEL SECURITY;
ALTER TABLE rail_tariff FORCE ROW LEVEL SECURITY;
CREATE POLICY rail_tariff_isolation ON rail_tariff
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
