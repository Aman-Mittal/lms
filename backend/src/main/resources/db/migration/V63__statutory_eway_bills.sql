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

-- Issue #231: statutory e-way bills per consignment.
--
-- A consignment moving goods needs an e-way bill from the statutory system. The
-- platform keeps one row per bill: its number, when it was generated, until
-- when it is valid, its status and the vehicle on Part-B. A consignment has at
-- most one live bill (ACTIVE or EXTENDED); a cancelled or expired one stays as
-- history and a new one can be raised after it. What happened to a bill is the
-- audit trail of the row (resource type EWAY_BILL); every call to the adapter is
-- audited as well, with its outcome and never a secret.
--
-- Whether a unit uses the adapter at all, and whether dispatch waits for a live
-- bill, are settings (statutory.ewayBill.*), both off by default.

CREATE TABLE eway_bill
(
    id                  UUID PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    org_unit_id         UUID        NOT NULL REFERENCES org_unit (id) ON DELETE RESTRICT,
    consignment_id      UUID        NOT NULL REFERENCES consignment (id) ON DELETE RESTRICT,
    bill_no             TEXT        NOT NULL,
    -- ACTIVE, EXTENDED (valid until a later time than generated), CANCELLED, EXPIRED.
    status              TEXT        NOT NULL,
    generated_at        TIMESTAMPTZ NOT NULL,
    valid_until         TIMESTAMPTZ NOT NULL,
    -- The vehicle on Part-B, as the registration number the statutory system holds.
    vehicle_no          TEXT,
    vehicle_updated_at  TIMESTAMPTZ,
    distance_km         INT,
    -- Which adapter issued it (STUB, ...), or MANUAL for a bill keyed from the portal.
    source              TEXT        NOT NULL,
    extension_count     INT         NOT NULL DEFAULT 0,
    cancelled_at        TIMESTAMPTZ,
    cancel_reason       TEXT,
    -- Set by the sweep when the bill is within the unit's threshold of expiry; cleared by an extension.
    expiring_flagged_at TIMESTAMPTZ,
    expired_at          TIMESTAMPTZ,
    created_by          UUID,
    version             BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT eway_bill_no_unique UNIQUE (tenant_id, bill_no),
    CONSTRAINT eway_bill_status_valid CHECK (status IN ('ACTIVE', 'EXTENDED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT eway_bill_validity_ordered CHECK (valid_until > generated_at),
    CONSTRAINT eway_bill_cancelled_whole CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL)),
    CONSTRAINT eway_bill_expired_whole CHECK ((status = 'EXPIRED') = (expired_at IS NOT NULL)),
    CONSTRAINT eway_bill_distance_positive CHECK (distance_km IS NULL OR distance_km > 0),
    CONSTRAINT eway_bill_text_bounded CHECK (length(bill_no) <= 40
        AND (vehicle_no IS NULL OR length(vehicle_no) <= 20)
        AND (cancel_reason IS NULL OR length(cancel_reason) <= 500))
);

-- One live bill per consignment; a cancelled or expired one does not hold the key.
CREATE UNIQUE INDEX eway_bill_live_consignment_idx ON eway_bill (tenant_id, consignment_id)
    WHERE status IN ('ACTIVE', 'EXTENDED');
-- A consignment's bills, newest first.
CREATE INDEX eway_bill_consignment_idx ON eway_bill (tenant_id, consignment_id, generated_at DESC, id DESC);
-- The desk, newest first, with or without a status.
CREATE INDEX eway_bill_list_idx ON eway_bill (tenant_id, created_at DESC, id DESC);
CREATE INDEX eway_bill_status_idx ON eway_bill (tenant_id, status, created_at DESC, id DESC);
-- What the expiry sweep reads: live bills by the time they lapse.
CREATE INDEX eway_bill_live_expiry_idx ON eway_bill (tenant_id, valid_until, id)
    WHERE status IN ('ACTIVE', 'EXTENDED');
-- The expiring-soon list: live bills the sweep has flagged, by the time they lapse.
CREATE INDEX eway_bill_flagged_idx ON eway_bill (tenant_id, valid_until, id)
    WHERE status IN ('ACTIVE', 'EXTENDED') AND expiring_flagged_at IS NOT NULL;

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'EWAY_BILL_MANAGE', 'EWAY_BILL', 'UPDATE');

-- ---------------------------------------------------------------------------
-- Row-level security
-- ---------------------------------------------------------------------------

ALTER TABLE eway_bill ENABLE ROW LEVEL SECURITY;
ALTER TABLE eway_bill FORCE ROW LEVEL SECURITY;
CREATE POLICY eway_bill_isolation ON eway_bill USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
