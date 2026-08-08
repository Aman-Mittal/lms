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

-- Vendor sourcing and load allocation (vision document 3.5): deciding who
-- actually moves a planned load.

-- ---------------------------------------------------------------------------
-- Platform-wide maintenance needs a way to enumerate tenants
-- ---------------------------------------------------------------------------
--
-- Scheduled jobs run on no tenant's behalf, so they carry no scope, so
-- app_current_tenant() returns NULL and every row-level security policy
-- evaluates `tenant_id = NULL` -- which is NULL, not true. The job sees an
-- empty table and reports success.
--
-- That is not hypothetical: the idempotency prune added in V5 has been silently
-- deleting nothing since it was written.
--
-- The fix is for a sweep to iterate tenants and do its work *scoped*, exactly
-- as ordinary request handling does, rather than to punch a hole in RLS. This
-- function is the only thing it needs that scoped code cannot do for itself,
-- and it is SECURITY DEFINER for the same reason resolve_tenant_by_code() in
-- V2 is: reading the tenant register is a precondition of establishing scope,
-- so it cannot itself require scope.
--
-- It returns identifiers only. Nothing about a tenant leaks, and no row of any
-- business table becomes reachable -- the caller still has to bind a scope, and
-- still only sees that tenant.
CREATE OR REPLACE FUNCTION app_active_tenant_ids() RETURNS SETOF uuid
    LANGUAGE sql
    STABLE
    SECURITY DEFINER
    SET search_path = public
    AS $$
    SELECT id FROM tenant WHERE status = 'ACTIVE' ORDER BY id
$$;

REVOKE ALL ON FUNCTION app_active_tenant_ids() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION app_active_tenant_ids() TO lms_app;

-- ---------------------------------------------------------------------------
-- 3.5.1 Contractual routing guide
-- ---------------------------------------------------------------------------

-- A lane -- origin, destination, vehicle type -- and the ordered list of
-- vendors contracted to serve it.
CREATE TABLE routing_guide
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id             UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    origin_terminal_id      UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    destination_terminal_id UUID        NOT NULL REFERENCES terminal (id) ON DELETE RESTRICT,
    vehicle_type            TEXT        NOT NULL,
    -- CONTRACTUAL walks the ranks in order; ROUND_ROBIN spreads work by
    -- awards-so-far this month. Both are implementations of one interface, so
    -- a reverse auction can be added later without changing any caller.
    strategy                TEXT        NOT NULL DEFAULT 'CONTRACTUAL',
    active                  BOOLEAN     NOT NULL DEFAULT true,
    version                 BIGINT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One guide per lane per vehicle type. Two would make allocation depend on
    -- which row happened to be read first.
    CONSTRAINT routing_guide_lane_unique
        UNIQUE (tenant_id, origin_terminal_id, destination_terminal_id, vehicle_type),
    CONSTRAINT routing_guide_strategy_valid CHECK (strategy IN ('CONTRACTUAL', 'ROUND_ROBIN'))
);

CREATE TABLE routing_guide_entry
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    guide_id             UUID        NOT NULL REFERENCES routing_guide (id) ON DELETE CASCADE,
    -- `rank` is a reserved word (the window function), so the column is
    -- rank_no. Quoting it instead would mean quoting it forever.
    rank_no              INT         NOT NULL,
    vendor_partner_id    UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    -- How long the vendor has to answer before the offer lapses and the
    -- cascade moves on (3.5.1).
    response_sla_minutes INT         NOT NULL DEFAULT 60,
    agreed_rate          NUMERIC(14, 2),
    version              BIGINT,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT routing_guide_entry_rank_unique UNIQUE (guide_id, rank_no),
    -- A vendor at two ranks would be offered the same load twice as the
    -- cascade descends, which reads to them as the system being broken.
    CONSTRAINT routing_guide_entry_vendor_unique UNIQUE (guide_id, vendor_partner_id),
    CONSTRAINT routing_guide_entry_rank_positive CHECK (rank_no > 0),
    CONSTRAINT routing_guide_entry_sla_positive CHECK (response_sla_minutes > 0)
);

CREATE INDEX routing_guide_entry_guide_idx
    ON routing_guide_entry (tenant_id, guide_id, rank_no);

-- ---------------------------------------------------------------------------
-- 3.5.2 Allocation: offering a load down the ranks
-- ---------------------------------------------------------------------------

CREATE TABLE allocation
(
    id                       UUID PRIMARY KEY,
    tenant_id                UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    load_id                  UUID        NOT NULL REFERENCES load_unit (id) ON DELETE CASCADE,
    guide_id                 UUID REFERENCES routing_guide (id) ON DELETE SET NULL,
    strategy                 TEXT        NOT NULL,
    status                   TEXT        NOT NULL DEFAULT 'OFFERED',
    current_rank             INT,
    awarded_vendor_partner_id UUID REFERENCES business_partner (id) ON DELETE RESTRICT,
    awarded_at               TIMESTAMPTZ,
    version                  BIGINT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One allocation per load. Re-offering a load that is already out with a
    -- vendor is how the same trip gets sold twice.
    CONSTRAINT allocation_load_unique UNIQUE (tenant_id, load_id),
    CONSTRAINT allocation_status_valid CHECK (status IN
        ('OFFERED', 'AWARDED', 'EXHAUSTED', 'CANCELLED'))
);

CREATE INDEX allocation_status_idx ON allocation (tenant_id, status);

-- Every offer made, kept rather than overwritten. The cascade trail is what
-- answers "why did this load go to the rank-3 vendor at twice the rate", which
-- is a question asked after the invoice arrives, not during allocation.
CREATE TABLE allocation_offer
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    allocation_id     UUID        NOT NULL REFERENCES allocation (id) ON DELETE CASCADE,
    rank_no           INT         NOT NULL,
    vendor_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    offered_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Stored, not derived from the SLA at read time. The contract term can be
    -- renegotiated tomorrow; the deadline this vendor was actually given must
    -- not move retrospectively.
    responds_by       TIMESTAMPTZ NOT NULL,
    outcome           TEXT        NOT NULL DEFAULT 'PENDING',
    responded_at      TIMESTAMPTZ,
    version           BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT allocation_offer_rank_unique UNIQUE (allocation_id, rank_no),
    CONSTRAINT allocation_offer_outcome_valid CHECK (outcome IN
        ('PENDING', 'ACCEPTED', 'REJECTED', 'TIMED_OUT', 'CANCELLED'))
);

-- Serves the timeout sweep, which runs per tenant and so leads with tenant_id
-- like every other index. Partial on PENDING because that is the only outcome
-- the sweep looks at, and it is the minority on any mature allocation.
CREATE INDEX allocation_offer_pending_idx
    ON allocation_offer (tenant_id, responds_by)
    WHERE outcome = 'PENDING';

CREATE INDEX allocation_offer_allocation_idx
    ON allocation_offer (tenant_id, allocation_id, rank_no);

-- ---------------------------------------------------------------------------
-- Round-robin fair share
-- ---------------------------------------------------------------------------

-- Awards per vendor per calendar month. A running tally rather than a count
-- over allocation_offer, because "fair share this month" must not change
-- meaning when historical offers are pruned.
CREATE TABLE vendor_award_tally
(
    tenant_id         UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    guide_id          UUID        NOT NULL REFERENCES routing_guide (id) ON DELETE CASCADE,
    -- First day of the month, so the period is a value rather than a range.
    period_month      DATE        NOT NULL,
    vendor_partner_id UUID        NOT NULL REFERENCES business_partner (id) ON DELETE RESTRICT,
    awarded_count     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (guide_id, period_month, vendor_partner_id)
);

CREATE INDEX vendor_award_tally_period_idx
    ON vendor_award_tally (tenant_id, guide_id, period_month);

-- ---------------------------------------------------------------------------
-- The awarded vendor on the load itself
-- ---------------------------------------------------------------------------

-- Denormalised from the allocation so that execution can create a trip without
-- depending on the sourcing module. Which vendor is moving a load is a fact
-- about the load, not only about how it was sourced -- a load allocated
-- manually tomorrow has a vendor and no allocation row at all.
ALTER TABLE load_unit
    ADD COLUMN awarded_vendor_partner_id UUID REFERENCES business_partner (id) ON DELETE RESTRICT;

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY [
            'routing_guide', 'routing_guide_entry',
            'allocation', 'allocation_offer', 'vendor_award_tally'
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
