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

-- Credit limits become a gate.
--
-- business_partner.credit_limit has been stored since V4 and enforced
-- nowhere. From here it is the hard limit, a soft limit sits beside it, and
-- both change only through a request that a second person approves. A
-- refused action can be let through once by an override that names what it
-- covers.

ALTER TABLE business_partner
    ADD COLUMN credit_soft_limit NUMERIC(18, 2);

ALTER TABLE business_partner
    ADD CONSTRAINT business_partner_credit_limit_non_negative
        CHECK (credit_limit IS NULL OR credit_limit >= 0),
    ADD CONSTRAINT business_partner_credit_soft_limit_sane
        CHECK (credit_soft_limit IS NULL
            OR (credit_soft_limit >= 0 AND (credit_limit IS NULL OR credit_soft_limit <= credit_limit)));

-- A request to change a partner's limits, and once decided, its record in the
-- change log. Rows are never deleted; the previous values are kept so the log
-- reads as before and after without joining history.
CREATE TABLE credit_limit_change
(
    id                   UUID PRIMARY KEY,
    tenant_id            UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    partner_id           UUID           NOT NULL REFERENCES business_partner (id) ON DELETE CASCADE,
    status               TEXT           NOT NULL DEFAULT 'PENDING',
    previous_limit       NUMERIC(18, 2),
    previous_soft_limit  NUMERIC(18, 2),
    requested_limit      NUMERIC(18, 2) NOT NULL,
    requested_soft_limit NUMERIC(18, 2),
    reason               TEXT           NOT NULL,
    requested_by         TEXT           NOT NULL,
    requested_at         TIMESTAMPTZ    NOT NULL,
    decided_by           TEXT,
    decided_at           TIMESTAMPTZ,
    decision_note        TEXT,
    version              BIGINT,
    CONSTRAINT credit_limit_change_status_valid CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT credit_limit_change_limit_non_negative CHECK (requested_limit >= 0),
    CONSTRAINT credit_limit_change_soft_sane CHECK (requested_soft_limit IS NULL
        OR (requested_soft_limit >= 0 AND requested_soft_limit <= requested_limit)),
    CONSTRAINT credit_limit_change_reason_given CHECK (length(btrim(reason)) > 0),
    CONSTRAINT credit_limit_change_decided_iff_closed CHECK (
        (status = 'PENDING') = (decided_at IS NULL))
);

-- One open request per partner: a second would be decided against limits the
-- first may already have changed.
CREATE UNIQUE INDEX credit_limit_change_one_pending
    ON credit_limit_change (tenant_id, partner_id) WHERE status = 'PENDING';
CREATE INDEX credit_limit_change_partner_log_idx
    ON credit_limit_change (tenant_id, partner_id, requested_at DESC, id DESC);
CREATE INDEX credit_limit_change_queue_idx
    ON credit_limit_change (tenant_id, status, requested_at DESC, id DESC);
CREATE INDEX credit_limit_change_listing_idx
    ON credit_limit_change (tenant_id, requested_at DESC, id DESC);

ALTER TABLE credit_limit_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_limit_change FORCE ROW LEVEL SECURITY;
CREATE POLICY credit_limit_change_isolation ON credit_limit_change
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- A one-time pass through the hard limit for one subject: an order
-- (ORDER:<order id>) or a client's billing month (CLIENT_INVOICE:<yyyy-mm-01>).
-- Used up by the first action it lets through; unused ones lapse.
CREATE TABLE credit_override
(
    id                UUID PRIMARY KEY,
    tenant_id         UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    partner_id        UUID           NOT NULL REFERENCES business_partner (id) ON DELETE CASCADE,
    subject_type      TEXT           NOT NULL,
    subject_key       TEXT           NOT NULL,
    up_to             NUMERIC(18, 2) NOT NULL,
    reason            TEXT           NOT NULL,
    granted_by        TEXT           NOT NULL,
    granted_at        TIMESTAMPTZ    NOT NULL,
    expires_at        TIMESTAMPTZ    NOT NULL,
    used_at           TIMESTAMPTZ,
    used_exposure     NUMERIC(18, 2),
    version           BIGINT,
    CONSTRAINT credit_override_subject_valid CHECK (subject_type IN ('ORDER', 'CLIENT_INVOICE')),
    CONSTRAINT credit_override_up_to_non_negative CHECK (up_to >= 0),
    CONSTRAINT credit_override_reason_given CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX credit_override_open_idx
    ON credit_override (tenant_id, partner_id, subject_key) WHERE used_at IS NULL;

ALTER TABLE credit_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE credit_override FORCE ROW LEVEL SECURITY;
CREATE POLICY credit_override_isolation ON credit_override
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- Open orders by customer, for exposure: the statuses that count, and no
-- others, so the partial index is exactly the predicate the query states.
CREATE INDEX sales_order_customer_open_idx
    ON sales_order (tenant_id, customer_partner_id)
    WHERE status IN ('VALIDATED', 'PARTIALLY_PLANNED', 'FULLY_PLANNED');

-- Uninvoiced charges by client, across every open month.
CREATE INDEX client_charge_unbilled_client_idx
    ON client_charge (tenant_id, client_partner_id) WHERE invoice_id IS NULL;

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Asking for a limit, approving one and overriding one are three different
-- authorities, so that no single right both sets and spends a limit.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'CREDIT_LIMIT_REQUEST', 'CREDIT_LIMIT', 'CREATE'),
       (gen_random_uuid(), 'CREDIT_LIMIT_APPROVE', 'CREDIT_LIMIT', 'UPDATE'),
       (gen_random_uuid(), 'CREDIT_OVERRIDE', 'CREDIT_LIMIT', 'EXECUTE');

-- ---------------------------------------------------------------------------
-- Approval limits on credit
-- ---------------------------------------------------------------------------

-- Approving a limit, and overriding one, are held to the approver's approval
-- limit: the amount is the limit approved, or the exposure an override lets
-- through above the hard limit. Replaces V28's definition with two rules
-- added; the V16 trigger calls the function by name, so new tenants get them.
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
         NULL),
        ('client-credit-note-write-org-subtree', 'CLIENT_CREDIT_NOTE_ISSUE',
         'A credit note may be issued only against the invoice of a client in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('client-billing-write-org-subtree', 'CLIENT_BILLING_RUN',
         'A client may be billed -- run, usage recorded, storage computed -- only in the caller''s own organisational unit or one beneath it.',
         NULL),
        ('credit-limit-approval-limit', 'CREDIT_LIMIT_APPROVE',
         'A credit limit may be approved only up to the approver''s approval limit.',
         '{"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}'::jsonb),
        ('credit-override-approval-limit', 'CREDIT_OVERRIDE',
         'A credit limit may be overridden only up to an exposure within the approver''s approval limit.',
         '{"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}'::jsonb)
      ) AS rule (code, action, description, condition)
    ON CONFLICT (tenant_id, code) DO NOTHING
$$;

-- Existing tenants, as V28 seeded them: each under its own scope, scope
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
