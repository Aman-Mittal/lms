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

-- Proof of delivery and claims, per consignment.

-- One row per consignment: first the one-time code that was issued, then the
-- proof once it was matched. The code is kept only as a hash, with an expiry
-- and a count of wrong attempts, so a leaked table does not hand out codes and
-- a stolen consignment number cannot be guessed against forever.
CREATE TABLE delivery_proof
(
    id             UUID PRIMARY KEY,
    tenant_id      UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    consignment_id UUID        NOT NULL REFERENCES consignment (id) ON DELETE RESTRICT,
    status         TEXT        NOT NULL,
    otp_hash       TEXT        NOT NULL,
    otp_issued_at  TIMESTAMPTZ NOT NULL,
    otp_expires_at TIMESTAMPTZ NOT NULL,
    otp_attempts   INTEGER     NOT NULL DEFAULT 0,
    recipient_name TEXT,
    -- A reference to the photo (a URL or a storage key); the platform stores
    -- no file.
    photo_ref      TEXT,
    delivered_at   TIMESTAMPTZ,
    lat            NUMERIC(9, 6),
    lon            NUMERIC(9, 6),
    version        BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT delivery_proof_status_valid CHECK (status IN ('OTP_ISSUED', 'CONFIRMED')),
    CONSTRAINT delivery_proof_attempts_non_negative CHECK (otp_attempts >= 0),
    CONSTRAINT delivery_proof_confirmed_is_complete CHECK (
        status <> 'CONFIRMED' OR (recipient_name IS NOT NULL AND delivered_at IS NOT NULL)),
    CONSTRAINT delivery_proof_position_whole CHECK ((lat IS NULL) = (lon IS NULL)),
    CONSTRAINT delivery_proof_position_range CHECK (
        (lat IS NULL OR lat BETWEEN -90 AND 90) AND (lon IS NULL OR lon BETWEEN -180 AND 180))
);

CREATE UNIQUE INDEX delivery_proof_consignment_idx ON delivery_proof (tenant_id, consignment_id);

ALTER TABLE delivery_proof ENABLE ROW LEVEL SECURITY;
ALTER TABLE delivery_proof FORCE ROW LEVEL SECURITY;
CREATE POLICY delivery_proof_isolation ON delivery_proof
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- A claim against a consignment: damage, shortage, loss or delay.
CREATE TABLE claim
(
    id              UUID PRIMARY KEY,
    tenant_id       UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    claim_no        TEXT           NOT NULL,
    consignment_id  UUID           NOT NULL REFERENCES consignment (id) ON DELETE RESTRICT,
    claim_type      TEXT           NOT NULL,
    amount          NUMERIC(14, 2) NOT NULL,
    currency        TEXT           NOT NULL DEFAULT 'INR',
    description     TEXT           NOT NULL,
    status          TEXT           NOT NULL DEFAULT 'OPEN',
    approved_amount NUMERIC(14, 2),
    resolution_note TEXT,
    decided_at      TIMESTAMPTZ,
    version         BIGINT,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT claim_type_valid CHECK (claim_type IN ('DAMAGE', 'SHORTAGE', 'LOSS', 'DELAY')),
    CONSTRAINT claim_status_valid CHECK (status IN
        ('OPEN', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'SETTLED')),
    CONSTRAINT claim_amount_positive CHECK (amount > 0),
    -- What is granted can never exceed what was claimed.
    CONSTRAINT claim_approved_within_claim CHECK (
        approved_amount IS NULL OR (approved_amount > 0 AND approved_amount <= amount)),
    CONSTRAINT claim_approval_has_amount CHECK (
        status NOT IN ('APPROVED', 'SETTLED') OR approved_amount IS NOT NULL),
    CONSTRAINT claim_rejection_is_explained CHECK (
        status <> 'REJECTED' OR resolution_note IS NOT NULL)
);

CREATE UNIQUE INDEX claim_no_idx ON claim (tenant_id, claim_no);
CREATE INDEX claim_status_idx ON claim (tenant_id, status, claim_no);
CREATE INDEX claim_consignment_idx ON claim (tenant_id, consignment_id, claim_no);

ALTER TABLE claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE claim FORCE ROW LEVEL SECURITY;
CREATE POLICY claim_isolation ON claim
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- Raising a claim and deciding one are different jobs: whoever holds the
-- delivery desk should not also be the person who grants the money.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'POD_RECORD', 'DELIVERY', 'CREATE'),
       (gen_random_uuid(), 'POD_READ', 'DELIVERY', 'READ'),
       (gen_random_uuid(), 'CLAIM_RAISE', 'CLAIM', 'CREATE'),
       (gen_random_uuid(), 'CLAIM_READ', 'CLAIM', 'READ'),
       (gen_random_uuid(), 'CLAIM_DECIDE', 'CLAIM', 'UPDATE');
