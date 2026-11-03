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

-- Warehousing, second pass: back-orders, wave picking, quarantine, listings.
--
-- None of this changes how stock moves. Every unit still travels through
-- stock_movement, and the V20 triggers still refuse negative stock, an
-- overfull bin and any edit to the ledger.

-- ---------------------------------------------------------------------------
-- Back-orders
-- ---------------------------------------------------------------------------

-- An order shipped short is not finished. Once its last packed unit is
-- dispatched it waits as BACK_ORDERED for a further pick, and a further unit,
-- rather than claiming to be DISPATCHED with goods still owed. The back-order
-- itself needs no column: it is ordered_qty - picked_qty on each line.
ALTER TABLE outbound_order
    DROP CONSTRAINT outbound_order_status_valid,
    ADD CONSTRAINT outbound_order_status_valid CHECK (status IN (
        'OPEN', 'PICKED', 'SHORT_PICKED', 'PACKED', 'DISPATCHED', 'BACK_ORDERED'));

-- ---------------------------------------------------------------------------
-- Quarantine
-- ---------------------------------------------------------------------------

-- Damaged units counted at the dock used to be recorded on the ASN line and
-- then left out of the ledger altogether, so nothing said where they were.
-- They now enter it at a QUARANTINE location, identified -- like the dock --
-- by the warehouse, and leave it only by an adjustment that releases them to
-- a bin or writes them off. Receipts counted before this migration are not
-- backfilled: whatever happened to those units was never recorded.
ALTER TABLE stock_movement
    DROP CONSTRAINT stock_movement_location_valid,
    ADD CONSTRAINT stock_movement_location_valid CHECK (location_type IN (
        'DOCK', 'BIN', 'OUTBOUND', 'HU', 'QUARANTINE'));

-- ---------------------------------------------------------------------------
-- Wave picking
-- ---------------------------------------------------------------------------

CREATE TABLE pick_wave
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    warehouse_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    wave_no      TEXT        NOT NULL,
    status       TEXT        NOT NULL,
    picked_by    TEXT,
    picked_at    TIMESTAMPTZ,
    version      BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pick_wave_no_unique UNIQUE (tenant_id, wave_no),
    CONSTRAINT pick_wave_status_valid CHECK (status IN ('RELEASED', 'PICKED')),
    CONSTRAINT pick_wave_pick_whole CHECK ((status = 'PICKED') = (picked_at IS NOT NULL))
);

CREATE INDEX pick_wave_warehouse_idx ON pick_wave (tenant_id, warehouse_id, created_at, id);

-- Which orders a wave holds, in the order they are served. Sequence matters:
-- when the bins cannot fill the whole wave, earlier orders are filled first
-- and the shortfall falls on the later ones.
CREATE TABLE pick_wave_order
(
    wave_id   UUID    NOT NULL REFERENCES pick_wave (id) ON DELETE CASCADE,
    seq       INTEGER NOT NULL,
    tenant_id UUID    NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    order_id  UUID    NOT NULL REFERENCES outbound_order (id) ON DELETE RESTRICT,
    PRIMARY KEY (wave_id, seq),
    CONSTRAINT pick_wave_order_once UNIQUE (tenant_id, wave_id, order_id),
    CONSTRAINT pick_wave_order_seq_positive CHECK (seq > 0)
);

-- "Is this order waiting in a wave": asked before every single-order pick.
CREATE INDEX pick_wave_order_order_idx ON pick_wave_order (tenant_id, order_id);

-- ---------------------------------------------------------------------------
-- Listings
-- ---------------------------------------------------------------------------

-- ASNs, outbound orders and handling units are listed per warehouse, newest
-- first, keyset on (created_at, id), optionally narrowed to one status. The
-- V20 indexes stopped at created_at, which cannot break a tie between two
-- rows created in the same microsecond; these replace them.
DROP INDEX asn_warehouse_idx;
CREATE INDEX asn_warehouse_page_idx ON asn (tenant_id, warehouse_id, created_at, id);
CREATE INDEX asn_warehouse_status_idx ON asn (tenant_id, warehouse_id, status, created_at, id);

DROP INDEX outbound_order_warehouse_idx;
CREATE INDEX outbound_order_warehouse_page_idx
    ON outbound_order (tenant_id, warehouse_id, created_at, id);
CREATE INDEX outbound_order_warehouse_status_idx
    ON outbound_order (tenant_id, warehouse_id, status, created_at, id);

DROP INDEX handling_unit_warehouse_idx;
CREATE INDEX handling_unit_warehouse_page_idx
    ON handling_unit (tenant_id, warehouse_id, created_at, id);
CREATE INDEX handling_unit_warehouse_status_idx
    ON handling_unit (tenant_id, warehouse_id, status, created_at, id);

-- ---------------------------------------------------------------------------
-- Access policies
-- ---------------------------------------------------------------------------

-- Releasing a wave is filed at its warehouse under WAREHOUSE_OPERATE, so
-- V24's asn-write-org-subtree rule -- a policy on the action, not on ASNs --
-- already holds it to the caller's subtree. Adjustments had no rule: a
-- correction, a release from quarantine and a write-off are all
-- INVENTORY_ADJUST, decided at the bin's or the warehouse's unit.
--
-- Replaces V26's definition with every rule it had, plus that one. The
-- trigger V16 installed calls the function by name, so new tenants pick it up.
CREATE OR REPLACE FUNCTION seed_default_access_policies(p_tenant UUID) RETURNS void
    LANGUAGE sql
    AS $$
    INSERT INTO access_policy (id, tenant_id, code, action, effect, description, condition, version)
    SELECT gen_random_uuid(), p_tenant, rule.code, rule.action, 'DENY', rule.description,
           -- NULL stands for the subtree rule, written once rather than
           -- repeated per rule: refuse when the record's unit is not the
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
        ('inventory-adjust-org-subtree', 'INVENTORY_ADJUST',
         'Stock may be adjusted, and quarantined units released or written off, only at a warehouse in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('rail-tariff-write-org-subtree', 'TARIFF_MANAGE',
         'A rail rate card may be published only from a railhead in the caller''s own organisational unit or one beneath it.',
         NULL)
      ) AS rule (code, action, description, condition)
    ON CONFLICT (tenant_id, code) DO NOTHING
$$;

-- Existing tenants, exactly as V24 seeded them. ON CONFLICT leaves every rule
-- a tenant already has -- and any it has edited -- alone.
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

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['pick_wave', 'pick_wave_order']
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
