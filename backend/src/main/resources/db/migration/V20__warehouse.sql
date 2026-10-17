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

-- Warehousing: inbound, putaway, inventory, pick-pack and cross-dock.
--
-- A warehouse is not a new kind of site. It is a terminal whose functional
-- category is WAREHOUSE, referenced by id; the geofence, the address and the
-- operating hours stay where master data keeps them.
--
-- The rule that shapes everything below: stock is never edited. Every change
-- is a row in stock_movement, which cannot be updated or deleted, and the
-- quantity on hand at a location is derived from those rows by a trigger.
-- Two invariants are held by the database rather than trusted to the service:
--
--   * no location ever holds a negative quantity -- a CHECK on the balance;
--   * a bin never holds more units than its capacity -- checked by the same
--     trigger that moves the balance, under a lock on the bin.
--
-- The service checks both first so a refusal reads as a business rule; the
-- database is what makes them true under concurrency and against anyone who
-- writes to the ledger without going through the service.

-- ---------------------------------------------------------------------------
-- Bins and SKUs
-- ---------------------------------------------------------------------------

CREATE TABLE warehouse_bin
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    warehouse_id   UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    code           TEXT        NOT NULL,
    -- In units of whatever the SKUs in it are counted in. Volumetric and
    -- weight capacity are not modelled; see the module documentation.
    capacity_units INTEGER     NOT NULL,
    version        BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT warehouse_bin_code_unique UNIQUE (tenant_id, warehouse_id, code),
    CONSTRAINT warehouse_bin_capacity_positive CHECK (capacity_units > 0)
);

CREATE TABLE sku
(
    id               UUID PRIMARY KEY,
    tenant_id        UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    code             TEXT        NOT NULL,
    description      TEXT        NOT NULL,
    -- Whose goods these are, for a warehouse run on a customer's behalf.
    -- Optional: a tenant storing its own stock owns it implicitly.
    owner_partner_id UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    uom              TEXT        NOT NULL,
    version          BIGINT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT sku_code_unique UNIQUE (tenant_id, code)
);

-- ---------------------------------------------------------------------------
-- Inbound: the advance shipping notice and what actually arrived
-- ---------------------------------------------------------------------------

CREATE TABLE asn
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    warehouse_id        UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    asn_no              TEXT        NOT NULL,
    supplier_partner_id UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    status              TEXT        NOT NULL,
    -- True when the receipt went straight to an outbound handling unit.
    cross_dock          BOOLEAN     NOT NULL DEFAULT FALSE,
    received_by         TEXT,
    received_at         TIMESTAMPTZ,
    version             BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT asn_no_unique UNIQUE (tenant_id, asn_no),
    CONSTRAINT asn_status_valid CHECK (status IN ('EXPECTED', 'RECEIVED')),
    CONSTRAINT asn_receipt_whole CHECK ((status = 'RECEIVED') = (received_at IS NOT NULL))
);

CREATE INDEX asn_warehouse_idx ON asn (tenant_id, warehouse_id, created_at);

CREATE TABLE asn_line
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID    NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    asn_id       UUID    NOT NULL REFERENCES asn (id) ON DELETE CASCADE,
    line_no      INTEGER NOT NULL,
    sku_id       UUID    NOT NULL REFERENCES sku (id) ON DELETE RESTRICT,
    -- '' rather than NULL for "no lot": it is part of the balance key, and a
    -- NULL there would make two unlotted balances of one SKU distinct.
    lot_no       TEXT    NOT NULL DEFAULT '',
    expected_qty INTEGER NOT NULL,
    -- Everything counted at the dock, damaged units included.
    received_qty INTEGER,
    damaged_qty  INTEGER,
    version      BIGINT,
    CONSTRAINT asn_line_no_unique UNIQUE (tenant_id, asn_id, line_no),
    CONSTRAINT asn_line_expected_positive CHECK (expected_qty > 0),
    CONSTRAINT asn_line_counts_valid CHECK (
        (received_qty IS NULL AND damaged_qty IS NULL)
            OR (received_qty >= 0 AND damaged_qty >= 0 AND damaged_qty <= received_qty))
);

-- ---------------------------------------------------------------------------
-- Outbound: orders to pick, and the handling units they are packed into
-- ---------------------------------------------------------------------------

CREATE TABLE outbound_order
(
    id           UUID PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    warehouse_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    order_no     TEXT        NOT NULL,
    -- Free text naming where it is going: a sales order number, a customer.
    -- Not a foreign key -- the warehouse ships for callers it does not model.
    ship_to      TEXT,
    status       TEXT        NOT NULL,
    version      BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT outbound_order_no_unique UNIQUE (tenant_id, order_no),
    CONSTRAINT outbound_order_status_valid CHECK (status IN (
        'OPEN', 'PICKED', 'SHORT_PICKED', 'PACKED', 'DISPATCHED'))
);

CREATE INDEX outbound_order_warehouse_idx ON outbound_order (tenant_id, warehouse_id, created_at);

CREATE TABLE outbound_line
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID    NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    order_id    UUID    NOT NULL REFERENCES outbound_order (id) ON DELETE CASCADE,
    line_no     INTEGER NOT NULL,
    sku_id      UUID    NOT NULL REFERENCES sku (id) ON DELETE RESTRICT,
    ordered_qty INTEGER NOT NULL,
    picked_qty  INTEGER NOT NULL DEFAULT 0,
    version     BIGINT,
    CONSTRAINT outbound_line_no_unique UNIQUE (tenant_id, order_id, line_no),
    CONSTRAINT outbound_line_ordered_positive CHECK (ordered_qty > 0),
    -- Over-picking is not a variance to record; it is impossible.
    CONSTRAINT outbound_line_picked_bounded CHECK (picked_qty BETWEEN 0 AND ordered_qty)
);

CREATE TABLE handling_unit
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    warehouse_id      UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    hu_no             TEXT        NOT NULL,
    -- How it was filled: packed from a picked order, or straight from the dock.
    source            TEXT        NOT NULL,
    outbound_order_id UUID REFERENCES outbound_order (id) ON DELETE RESTRICT,
    asn_id            UUID REFERENCES asn (id) ON DELETE RESTRICT,
    status            TEXT        NOT NULL,
    dispatch_ref      TEXT,
    dispatched_at     TIMESTAMPTZ,
    version           BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT handling_unit_no_unique UNIQUE (tenant_id, hu_no),
    CONSTRAINT handling_unit_source_valid CHECK (source IN ('PACK', 'CROSS_DOCK')),
    CONSTRAINT handling_unit_status_valid CHECK (status IN ('PACKED', 'DISPATCHED')),
    CONSTRAINT handling_unit_origin_matches CHECK (
        (source = 'PACK' AND outbound_order_id IS NOT NULL AND asn_id IS NULL)
            OR (source = 'CROSS_DOCK' AND asn_id IS NOT NULL AND outbound_order_id IS NULL)),
    CONSTRAINT handling_unit_dispatch_whole CHECK (
        (status = 'DISPATCHED') = (dispatched_at IS NOT NULL))
);

CREATE INDEX handling_unit_warehouse_idx ON handling_unit (tenant_id, warehouse_id, created_at);
-- The unit an order was packed into, or a receipt cross-docked into.
CREATE INDEX handling_unit_order_idx
    ON handling_unit (tenant_id, outbound_order_id) WHERE outbound_order_id IS NOT NULL;
CREATE INDEX handling_unit_asn_idx
    ON handling_unit (tenant_id, asn_id) WHERE asn_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The ledger
-- ---------------------------------------------------------------------------

-- Where stock can be. location_id is never NULL, which keeps the balance key
-- free of NULLs:
--   DOCK      location_id = the warehouse; received, not yet put away
--   BIN       location_id = a warehouse_bin
--   OUTBOUND  location_id = an outbound_order; picked, not yet packed
--   HU        location_id = a handling_unit; packed, not yet dispatched
CREATE TABLE stock_movement
(
    id            UUID PRIMARY KEY,
    -- RESTRICT, not CASCADE: the ledger refuses deletes, and a cascade that
    -- the guard then refused would fail with a message about the ledger
    -- rather than about the tenant.
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE RESTRICT,
    warehouse_id  UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    movement_type TEXT        NOT NULL,
    location_type TEXT        NOT NULL,
    location_id   UUID        NOT NULL,
    sku_id        UUID        NOT NULL REFERENCES sku (id) ON DELETE RESTRICT,
    lot_no        TEXT        NOT NULL DEFAULT '',
    -- Signed. A transfer is two rows sharing a reference: minus where it was,
    -- plus where it went.
    quantity      INTEGER     NOT NULL,
    -- The ASN, order, handling unit or adjustment this movement belongs to.
    reference_id  UUID,
    reason        TEXT,
    actor         TEXT        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,
    version       BIGINT,
    CONSTRAINT stock_movement_type_valid CHECK (movement_type IN (
        'RECEIPT', 'PUTAWAY', 'PICK', 'PACK', 'CROSS_DOCK', 'DISPATCH', 'ADJUST')),
    CONSTRAINT stock_movement_location_valid CHECK (location_type IN (
        'DOCK', 'BIN', 'OUTBOUND', 'HU')),
    CONSTRAINT stock_movement_nonzero CHECK (quantity <> 0)
);

-- A SKU's movement history, newest first, for the ledger screen.
CREATE INDEX stock_movement_sku_idx
    ON stock_movement (tenant_id, sku_id, occurred_at DESC, id DESC);
-- Everything one receipt, order or unit did.
CREATE INDEX stock_movement_reference_idx
    ON stock_movement (tenant_id, reference_id);

-- Derived. Written only by stock_movement_apply below; the application never
-- issues an INSERT or UPDATE here, and a guard refuses one that tries.
CREATE TABLE stock_balance
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE RESTRICT,
    warehouse_id  UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    location_type TEXT        NOT NULL,
    location_id   UUID        NOT NULL,
    sku_id        UUID        NOT NULL REFERENCES sku (id) ON DELETE RESTRICT,
    lot_no        TEXT        NOT NULL,
    on_hand       INTEGER     NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The invariant the ledger exists for. Holds whatever path the movement
    -- took to get here, including a hand-written INSERT.
    CONSTRAINT stock_balance_never_negative CHECK (on_hand >= 0),
    -- Also the index for "what is in this bin".
    CONSTRAINT stock_balance_key UNIQUE (tenant_id, location_type, location_id, sku_id, lot_no)
);

-- "Where is this SKU in this warehouse": pick allocation and the stock screen.
CREATE INDEX stock_balance_sku_idx
    ON stock_balance (tenant_id, sku_id, warehouse_id, location_type);
-- The stock screen for a whole warehouse, keyset on id.
CREATE INDEX stock_balance_warehouse_idx
    ON stock_balance (tenant_id, warehouse_id, id);

-- Applies one movement to its balance, and holds the bin capacity rule.
--
-- The bin row is locked before the capacity is summed, so two putaways into
-- the same bin serialise here instead of each seeing room the other is about
-- to take.
CREATE OR REPLACE FUNCTION stock_movement_apply() RETURNS TRIGGER AS
$$
DECLARE
    capacity INTEGER;
    occupied BIGINT;
BEGIN
    IF NEW.location_type = 'BIN' AND NEW.quantity > 0 THEN
        SELECT capacity_units INTO capacity
          FROM warehouse_bin
         WHERE id = NEW.location_id
           FOR UPDATE;
        IF capacity IS NULL THEN
            RAISE EXCEPTION 'Bin % does not exist', NEW.location_id
                USING ERRCODE = 'foreign_key_violation';
        END IF;
        SELECT coalesce(sum(on_hand), 0) INTO occupied
          FROM stock_balance
         WHERE tenant_id = NEW.tenant_id
           AND location_type = 'BIN'
           AND location_id = NEW.location_id;
        IF occupied + NEW.quantity > capacity THEN
            RAISE EXCEPTION 'Bin % holds % of % units; % more will not fit',
                NEW.location_id, occupied, capacity, NEW.quantity
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    -- UPDATE first, INSERT only for a location's first holding. Not
    -- INSERT ... ON CONFLICT: Postgres checks the CHECK constraint on the
    -- proposed row before it looks for a conflict, so a withdrawal (a negative
    -- quantity) would be refused even from a balance that can cover it.
    LOOP
        UPDATE stock_balance
           SET on_hand    = on_hand + NEW.quantity,
               updated_at = NEW.occurred_at
         WHERE tenant_id = NEW.tenant_id
           AND location_type = NEW.location_type
           AND location_id = NEW.location_id
           AND sku_id = NEW.sku_id
           AND lot_no = NEW.lot_no;
        EXIT WHEN FOUND;
        BEGIN
            -- A first movement that takes stock away fails the CHECK here,
            -- which is the point.
            INSERT INTO stock_balance (tenant_id, warehouse_id, location_type, location_id,
                                       sku_id, lot_no, on_hand, updated_at)
            VALUES (NEW.tenant_id, NEW.warehouse_id, NEW.location_type, NEW.location_id,
                    NEW.sku_id, NEW.lot_no, NEW.quantity, NEW.occurred_at);
            EXIT;
        EXCEPTION
            WHEN unique_violation THEN
                -- A concurrent first movement created the row; update it.
                NULL;
        END;
    END LOOP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_apply
    AFTER INSERT
    ON stock_movement
    FOR EACH ROW
EXECUTE FUNCTION stock_movement_apply();

-- The ledger is append-only. A correction is a new ADJUST row, never an edit:
-- an edited movement silently disagrees with the balance it already produced.
CREATE OR REPLACE FUNCTION stock_movement_refuse_change() RETURNS TRIGGER AS
$$
BEGIN
    RAISE EXCEPTION 'Stock movements are append-only; record an adjustment instead'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_movement_immutable
    BEFORE UPDATE OR DELETE
    ON stock_movement
    FOR EACH ROW
EXECUTE FUNCTION stock_movement_refuse_change();

-- A balance written by anything other than the ledger trigger is a balance
-- the ledger cannot explain. pg_trigger_depth() is 1 inside
-- stock_movement_apply, so the statements it issues arrive here at depth 2.
CREATE OR REPLACE FUNCTION stock_balance_refuse_direct_write() RETURNS TRIGGER AS
$$
BEGIN
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'Stock balances are derived from stock_movement and cannot be written directly'
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER stock_balance_derived_only
    BEFORE INSERT OR UPDATE OR DELETE
    ON stock_balance
    FOR EACH ROW
EXECUTE FUNCTION stock_balance_refuse_direct_write();

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Adjusting stock is its own permission: it is the one movement with no
-- physical event behind it, and so the one a shrinkage investigation reads.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'WAREHOUSE_READ', 'WAREHOUSE', 'READ'),
       (gen_random_uuid(), 'WAREHOUSE_SETUP', 'WAREHOUSE', 'CREATE'),
       (gen_random_uuid(), 'WAREHOUSE_OPERATE', 'WAREHOUSE', 'EXECUTE'),
       (gen_random_uuid(), 'INVENTORY_ADJUST', 'WAREHOUSE', 'UPDATE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'warehouse_bin', 'sku', 'asn', 'asn_line', 'outbound_order', 'outbound_line',
            'handling_unit', 'stock_movement', 'stock_balance'
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
