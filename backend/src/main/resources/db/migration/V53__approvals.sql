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


-- One approvals module for every flow.
--
-- A module that wants a person to agree before something happens raises an
-- approval request naming its subject (ORDER_CANCELLATION, POD_REVERSAL, ...),
-- the action, and a frozen payload saying exactly what will be done. The
-- request is decided in-process: approving it calls the owning module's
-- handler in the deciding transaction, so the change and the decision commit
-- together or not at all.
--
-- The policy that applied when a request was raised -- who decides, how many
-- of them, in what order, by when, and what happens at expiry -- is copied
-- onto the request. A later change of policy never alters a request that is
-- already open.

CREATE TABLE approval_request
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    -- Where the subject lives, for attribute-based access control; null for a
    -- subject that belongs to no unit.
    org_unit_id         UUID REFERENCES org_unit (id) ON DELETE RESTRICT,
    subject_type        TEXT        NOT NULL,
    subject_id          UUID        NOT NULL,
    action              TEXT        NOT NULL,
    -- What approving will do, as it was asked for. Never edited.
    payload             JSONB       NOT NULL DEFAULT '{}'::jsonb,
    summary             TEXT        NOT NULL,
    status              TEXT        NOT NULL,
    requested_by        UUID,
    requested_at        TIMESTAMPTZ NOT NULL,
    due_at              TIMESTAMPTZ,
    -- The policy in force when the request was raised (see the header).
    policy              JSONB       NOT NULL,
    -- The permissions an approval may still be given under: the next step's
    -- alone when steps are ordered, every step not yet given otherwise. Empty
    -- once the request is closed. Denormalised from the policy and the
    -- decisions so that "mine to decide" is a filter, not a computation.
    pending_permissions TEXT[]      NOT NULL DEFAULT '{}',
    approvals_given     INT         NOT NULL DEFAULT 0,
    decided_by          UUID,
    decided_at          TIMESTAMPTZ,
    reason              TEXT,
    version             BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT approval_request_status_valid CHECK (status IN
        ('REQUESTED', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT approval_request_summary_present CHECK (length(btrim(summary)) > 0),
    CONSTRAINT approval_request_summary_bounded CHECK (length(summary) <= 500),
    CONSTRAINT approval_request_reason_bounded CHECK (reason IS NULL OR length(reason) <= 500),
    CONSTRAINT approval_request_rejection_has_reason CHECK (
        status <> 'REJECTED' OR length(btrim(coalesce(reason, ''))) > 0),
    CONSTRAINT approval_request_closed_when_decided CHECK (
        (status = 'REQUESTED') = (decided_at IS NULL))
);

-- One open request per subject and action: a second is 409
-- approval-already-open. A closed request is history and holds no key.
CREATE UNIQUE INDEX approval_request_open_idx
    ON approval_request (tenant_id, subject_type, subject_id, action)
    WHERE status = 'REQUESTED';

-- The inbox, newest first, by status; and a subject's requests.
CREATE INDEX approval_request_inbox_idx
    ON approval_request (tenant_id, status, requested_at DESC, id DESC);
CREATE INDEX approval_request_all_idx
    ON approval_request (tenant_id, requested_at DESC, id DESC);
CREATE INDEX approval_request_requester_idx
    ON approval_request (tenant_id, requested_by, requested_at DESC, id DESC);
CREATE INDEX approval_request_subject_idx
    ON approval_request (tenant_id, subject_type, subject_id, requested_at DESC, id DESC);
-- What the expiry sweep looks for.
CREATE INDEX approval_request_due_idx
    ON approval_request (tenant_id, due_at)
    WHERE status = 'REQUESTED' AND due_at IS NOT NULL;

ALTER TABLE approval_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval_request FORCE ROW LEVEL SECURITY;
CREATE POLICY approval_request_isolation ON approval_request
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- The decision trail: every approval given, the rejection, the requester's
-- cancellation and the expiry, in order. Append-only.
CREATE TABLE approval_decision
(
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    request_id  UUID        NOT NULL REFERENCES approval_request (id) ON DELETE CASCADE,
    -- 1..n for an approval: the policy step it filled. Null otherwise.
    step        INT,
    decision    TEXT        NOT NULL,
    -- The permission the step was given under.
    permission  TEXT,
    decided_by  UUID,
    decided_at  TIMESTAMPTZ NOT NULL,
    note        TEXT,
    CONSTRAINT approval_decision_kind_valid CHECK (decision IN
        ('APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED', 'AUTO_APPROVED')),
    CONSTRAINT approval_decision_note_bounded CHECK (note IS NULL OR length(note) <= 500)
);

-- An approver fills one step of a request, never two.
CREATE UNIQUE INDEX approval_decision_one_step_each_idx
    ON approval_decision (tenant_id, request_id, decided_by)
    WHERE decision = 'APPROVED' AND decided_by IS NOT NULL;
CREATE INDEX approval_decision_trail_idx
    ON approval_decision (tenant_id, request_id, decided_at, id);

ALTER TABLE approval_decision ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval_decision FORCE ROW LEVEL SECURITY;
CREATE POLICY approval_decision_isolation ON approval_decision
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- A tenant's own values for a subject type's policy, over the code default.
-- Every column but the key is optional: null means "the default". This is a
-- stand-in for profile settings; ApprovalPolicyResolver is the seam they will
-- replace it through.
CREATE TABLE approval_policy_override
(
    tenant_id            UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    subject_type         TEXT        NOT NULL,
    required             BOOLEAN,
    decide_permissions   TEXT[],
    ordered              BOOLEAN,
    allow_self_approval  BOOLEAN,
    due_minutes          INT,
    on_expiry            TEXT,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by           UUID,
    PRIMARY KEY (tenant_id, subject_type),
    CONSTRAINT approval_policy_override_due_positive CHECK (due_minutes IS NULL OR due_minutes > 0),
    CONSTRAINT approval_policy_override_expiry_valid CHECK (on_expiry IS NULL OR on_expiry IN
        ('EXPIRE', 'AUTO_APPROVE')),
    CONSTRAINT approval_policy_override_steps_bounded CHECK (decide_permissions IS NULL
        OR cardinality(decide_permissions) BETWEEN 1 AND 5)
);

ALTER TABLE approval_policy_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE approval_policy_override FORCE ROW LEVEL SECURITY;
CREATE POLICY approval_policy_override_isolation ON approval_policy_override
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- ---------------------------------------------------------------------------
-- A reversed proof of delivery
-- ---------------------------------------------------------------------------

-- A proof is reversed only through an approved request, and only while no
-- bill has been settled or invoiced on the strength of it. The row is kept,
-- reading REVERSED, so the recipient and the time recorded stay on file; a
-- fresh code can then be issued and a new proof recorded over it.
ALTER TABLE delivery_proof DROP CONSTRAINT delivery_proof_status_valid;
ALTER TABLE delivery_proof
    ADD CONSTRAINT delivery_proof_status_valid CHECK (status IN ('OTP_ISSUED', 'CONFIRMED', 'REVERSED'));

-- ---------------------------------------------------------------------------
-- Permission vocabulary
-- ---------------------------------------------------------------------------

-- Reading the inbox; setting a tenant's own approval policy; deciding an
-- order's cancellation; asking for and deciding a proof's reversal. Asking
-- and deciding are separate rights, so no single right both raises and
-- grants a request.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'APPROVAL_READ', 'APPROVAL', 'READ'),
       (gen_random_uuid(), 'APPROVAL_POLICY_MANAGE', 'APPROVAL', 'UPDATE'),
       (gen_random_uuid(), 'ORDER_APPROVE', 'ORDER', 'EXECUTE'),
       (gen_random_uuid(), 'POD_REVERSE', 'DELIVERY_PROOF', 'UPDATE'),
       (gen_random_uuid(), 'POD_REVERSAL_APPROVE', 'DELIVERY_PROOF', 'EXECUTE');
