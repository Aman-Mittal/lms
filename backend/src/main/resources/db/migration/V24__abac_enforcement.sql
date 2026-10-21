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

-- Attribute-based access control, carried from the three rules of V16 to every
-- create command and to the read side of the portal.
--
-- Two halves, and only the first needs policies:
--
-- 1. Where a record may be filed. Every create command now asks the decision
--    point whether the caller may file under the organisational unit the
--    record will belong to (or, for a record with no unit of its own, the
--    unit of the record it hangs from -- a bin's warehouse, a trip's load).
--    The rule is the one partner registration already had: the unit must be
--    the caller's own or one beneath it. One policy per action, so a tenant
--    can relax one kind of record without relaxing the rest.
--
-- 2. What a portal user may see. Narrowed by a SQL predicate in each read
--    side, not by a policy: a policy is evaluated per record, and a listing
--    evaluated per row would read every row to return a page. The indexes
--    below are what keep those narrowed listings a seek.

-- ---------------------------------------------------------------------------
-- Default policies
-- ---------------------------------------------------------------------------

-- Replaces V16's definition, keeping its three rules unchanged and adding one
-- subtree rule per create action. The trigger V16 installed calls this
-- function by name, so new tenants pick the additions up without touching it.
--
-- Each condition bites only when both paths are present, exactly like V16's:
-- a caller at the root of the tenant (the demo administrator, every existing
-- head-office user) is a prefix of every path and keeps full access, and a
-- caller or record with no unit is decided by permissions alone.
CREATE OR REPLACE FUNCTION seed_default_access_policies(p_tenant UUID) RETURNS void
    LANGUAGE sql
    AS $$
    INSERT INTO access_policy (id, tenant_id, code, action, effect, description, condition, version)
    SELECT gen_random_uuid(), p_tenant, rule.code, rule.action, 'DENY', rule.description,
           -- NULL stands for the subtree rule, written once rather than
           -- thirteen times: refuse when the record's unit is not the
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
         NULL)
      ) AS rule (code, action, description, condition)
    ON CONFLICT (tenant_id, code) DO NOTHING
$$;

-- Tenants that already exist, the same way V16 seeded them: each tenant's rows
-- under that tenant's scope (the table is FORCEd), the scope cleared after.
-- ON CONFLICT leaves V16's rows -- and any a tenant has since edited -- alone.
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
-- Indexes for the partner-scoped listings
-- ---------------------------------------------------------------------------

-- A vendor-portal user's trips, paged by trip number.
CREATE INDEX trip_vendor_idx ON trip (tenant_id, vendor_partner_id, trip_no);

-- A vendor-portal user's loads -- the ones awarded to them -- paged by load number.
CREATE INDEX load_unit_awarded_vendor_idx
    ON load_unit (tenant_id, awarded_vendor_partner_id, load_no);

-- The offers addressed to one vendor, by allocation: what the allocation
-- listing's EXISTS probe and the offer trail both ask.
CREATE INDEX allocation_offer_vendor_idx
    ON allocation_offer (tenant_id, vendor_partner_id, allocation_id);

-- A customer-portal user's orders, paged by order number. V6's customer index
-- trails on status, which serves a filter but not this sort.
CREATE INDEX sales_order_customer_order_no_idx
    ON sales_order (tenant_id, customer_partner_id, order_no);

-- A customer-portal user's SKUs, paged by code; also the probe that narrows
-- stock and movements to the goods they own.
CREATE INDEX sku_owner_idx ON sku (tenant_id, owner_partner_id, code);
