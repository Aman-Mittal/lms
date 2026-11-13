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

-- Line-level proof of delivery.
--
-- A proof of delivery stops being one code and one photo reference. It becomes
-- a draft that is started ("ensured") for a consignment, fed over time with
-- what was received against each order line, the documents that came back and
-- the unloading times, and then submitted. A submitted proof does everything a
-- code-confirmed one does: the consignment is delivered, the carrier's hold
-- lifts once every proof is clean, and the client may be billed.
--
-- Statuses. PENDING is a draft with no code issued; OTP_ISSUED, CONFIRMED and
-- REVERSED are as before. SUBMITTED is a proof submitted by the line-level
-- route, with a code or keyed from paper; CONFIRMED stays what the one-step
-- code route records. Both prove the delivery. NOT_RECEIVED says the consignee
-- took nothing; it proves nothing and the draft can still be fed and submitted.

ALTER TABLE delivery_proof
    ALTER COLUMN otp_hash DROP NOT NULL,
    ALTER COLUMN otp_issued_at DROP NOT NULL,
    ALTER COLUMN otp_expires_at DROP NOT NULL;

ALTER TABLE delivery_proof DROP CONSTRAINT delivery_proof_status_valid;
ALTER TABLE delivery_proof
    ADD CONSTRAINT delivery_proof_status_valid CHECK (status IN
        ('PENDING', 'OTP_ISSUED', 'CONFIRMED', 'SUBMITTED', 'NOT_RECEIVED', 'REVERSED'));

ALTER TABLE delivery_proof DROP CONSTRAINT delivery_proof_confirmed_is_complete;
ALTER TABLE delivery_proof
    ADD CONSTRAINT delivery_proof_confirmed_is_complete CHECK (
        status NOT IN ('CONFIRMED', 'SUBMITTED') OR (recipient_name IS NOT NULL AND delivered_at IS NOT NULL)),
    ADD CONSTRAINT delivery_proof_code_present CHECK (status <> 'OTP_ISSUED' OR otp_hash IS NOT NULL);

-- A paper proof is keyed by staff from the signed copy, without a code; who
-- keyed it is on the row as well as in the audit trail.
ALTER TABLE delivery_proof
    ADD COLUMN paper                 BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN keyed_by              UUID,
    ADD COLUMN reported_at           TIMESTAMPTZ,
    ADD COLUMN unloading_started_at  TIMESTAMPTZ,
    ADD COLUMN unloading_ended_at    TIMESTAMPTZ,
    ADD CONSTRAINT delivery_proof_timings_ordered CHECK (
        (reported_at IS NULL OR unloading_started_at IS NULL OR reported_at <= unloading_started_at)
        AND (unloading_started_at IS NULL OR unloading_ended_at IS NULL
             OR unloading_started_at <= unloading_ended_at)
        AND (reported_at IS NULL OR unloading_ended_at IS NULL OR reported_at <= unloading_ended_at));

-- What was received against each order line of the consignment. The gross
-- quantity is the order line's own; it is read, never copied, so it cannot
-- drift from the order.
CREATE TABLE delivery_proof_line
(
    tenant_id       UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    proof_id        UUID           NOT NULL REFERENCES delivery_proof (id) ON DELETE CASCADE,
    order_line_id   UUID           NOT NULL REFERENCES order_line (id) ON DELETE RESTRICT,
    received_qty    NUMERIC(14, 3) NOT NULL DEFAULT 0,
    short_qty       NUMERIC(14, 3) NOT NULL DEFAULT 0,
    damaged_qty     NUMERIC(14, 3) NOT NULL DEFAULT 0,
    excess_qty      NUMERIC(14, 3) NOT NULL DEFAULT 0,
    PRIMARY KEY (proof_id, order_line_id),
    CONSTRAINT delivery_proof_line_non_negative CHECK (
        received_qty >= 0 AND short_qty >= 0 AND damaged_qty >= 0 AND excess_qty >= 0)
);

CREATE INDEX delivery_proof_line_tenant_idx ON delivery_proof_line (tenant_id, proof_id);

-- Documents that came back with the proof: a reference and a kind, never the
-- file (the platform stores none).
CREATE TABLE delivery_proof_document
(
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    proof_id   UUID        NOT NULL REFERENCES delivery_proof (id) ON DELETE CASCADE,
    doc_type   TEXT        NOT NULL,
    reference  TEXT        NOT NULL,
    position   INT         NOT NULL,
    CONSTRAINT delivery_proof_document_type_valid CHECK (doc_type IN ('FRONT', 'BACK', 'OTHER')),
    CONSTRAINT delivery_proof_document_ref_present CHECK (length(btrim(reference)) > 0),
    CONSTRAINT delivery_proof_document_ref_bounded CHECK (length(reference) <= 500)
);

CREATE INDEX delivery_proof_document_proof_idx ON delivery_proof_document (tenant_id, proof_id, position);

DO
$$
    DECLARE
        t TEXT;
    BEGIN
        FOREACH t IN ARRAY ARRAY ['delivery_proof_line', 'delivery_proof_document']
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

-- Keying a proof from paper is its own right: it proves a delivery with no
-- code from the consignee.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'POD_PAPER_KEY', 'DELIVERY', 'EXECUTE');
