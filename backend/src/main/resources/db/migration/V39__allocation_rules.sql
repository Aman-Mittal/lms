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

-- Allocation rules: what a tenant says about who should carry a load, applied
-- when the load is offered (sourcing owns them: it is the module that decides
-- who is asked next, and a rule is one more input to that decision).
--
-- A rule matches a load when EVERY condition it states holds; a condition left
-- empty is not tested, so a rule with none applies to every load. The first
-- match by priority (lowest first, ties by age then id) decides, alone: rules
-- do not stack, because two preferences that disagree have no sensible blend and
-- "which rule fired" must have one answer.
CREATE TABLE allocation_rule
(
    id                      UUID PRIMARY KEY,
    tenant_id               UUID           NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    name                    TEXT           NOT NULL,
    priority                INTEGER        NOT NULL,
    active                  BOOLEAN        NOT NULL DEFAULT TRUE,
    origin_terminal_id      UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    destination_terminal_id UUID REFERENCES terminal (id) ON DELETE RESTRICT,
    min_weight_kg           NUMERIC(12, 3),
    max_weight_kg           NUMERIC(12, 3),
    material_class          TEXT,
    action                  TEXT           NOT NULL,
    vendor_partner_ids      UUID[]         NOT NULL,
    version                 BIGINT,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT allocation_rule_action_valid CHECK (action IN ('PREFER', 'EXCLUDE')),
    CONSTRAINT allocation_rule_priority_range CHECK (priority BETWEEN 0 AND 100000),
    CONSTRAINT allocation_rule_vendors_present CHECK (cardinality(vendor_partner_ids) BETWEEN 1 AND 20),
    CONSTRAINT allocation_rule_weight_band CHECK (
        (min_weight_kg IS NULL OR min_weight_kg >= 0)
            AND (max_weight_kg IS NULL OR max_weight_kg > 0)
            AND (min_weight_kg IS NULL OR max_weight_kg IS NULL OR min_weight_kg <= max_weight_kg)),
    CONSTRAINT allocation_rule_class_valid CHECK (material_class IS NULL OR material_class IN
        ('GENERAL', 'FOOD', 'PHARMA', 'CHEMICAL', 'TOXIC', 'FLAMMABLE', 'FRAGILE', 'TEMPERATURE_CONTROLLED'))
);

CREATE UNIQUE INDEX allocation_rule_name_idx ON allocation_rule (tenant_id, lower(name));
CREATE INDEX allocation_rule_order_idx ON allocation_rule (tenant_id, priority, created_at, id) WHERE active;

ALTER TABLE allocation_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE allocation_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY allocation_rule_isolation ON allocation_rule
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- What decided an allocation: the rule that fired, or that none did. The name
-- is copied, because a rule is renamed and retired and the answer to "why did
-- this load go to that vendor" must not change with it.
CREATE TABLE allocation_rule_firing
(
    allocation_id UUID PRIMARY KEY REFERENCES allocation (id) ON DELETE CASCADE,
    tenant_id     UUID        NOT NULL REFERENCES tenant (id) ON DELETE CASCADE,
    rule_id       UUID REFERENCES allocation_rule (id) ON DELETE SET NULL,
    rule_name     TEXT,
    action        TEXT,
    fired_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT allocation_rule_firing_whole CHECK (
        (rule_name IS NULL) = (action IS NULL))
);

ALTER TABLE allocation_rule_firing ENABLE ROW LEVEL SECURITY;
ALTER TABLE allocation_rule_firing FORCE ROW LEVEL SECURITY;
CREATE POLICY allocation_rule_firing_isolation ON allocation_rule_firing
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'ALLOCATION_RULE_MANAGE', 'ALLOCATION_RULE', 'UPDATE'),
       (gen_random_uuid(), 'ALLOCATION_RULE_READ', 'ALLOCATION_RULE', 'READ');
