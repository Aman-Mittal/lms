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



-- Approval policies move onto profile settings: one JSON setting per subject
-- type (approvals.policy.orderCancellation, approvals.policy.podReversal), set
-- at the organisation default, an organisational unit, a customer or a
-- combination, and resolved for the subject's own context.
--
-- A tenant's rows in approval_policy_override become values of its
-- organisation default profile (created when the tenant has none). Only the
-- columns a row set are copied; a property left out is the code default's,
-- exactly as a null column was. The table is kept, unread, so that rolling the
-- application back loses nothing; a later migration may drop it.

INSERT INTO setting_profile (id, tenant_id, scope_type, scope_id, name, version, created_at, updated_at)
SELECT gen_random_uuid(), t.tenant_id, 'ORG_DEFAULT', NULL, 'Organisation default', 1, now(), now()
  FROM (SELECT DISTINCT tenant_id FROM approval_policy_override) t
 WHERE NOT EXISTS (SELECT 1 FROM setting_profile p
                    WHERE p.tenant_id = t.tenant_id AND p.scope_type = 'ORG_DEFAULT');

INSERT INTO setting_value (tenant_id, profile_id, setting_key, value)
SELECT o.tenant_id, p.id,
       CASE o.subject_type
           WHEN 'ORDER_CANCELLATION' THEN 'approvals.policy.orderCancellation'
           WHEN 'POD_REVERSAL' THEN 'approvals.policy.podReversal'
       END,
       jsonb_strip_nulls(jsonb_build_object(
               'required', coalesce(o.required, o.subject_type = 'POD_REVERSAL'),
               'steps', array_to_string(o.decide_permissions, ','),
               'ordered', o.ordered,
               'selfApproval', o.allow_self_approval,
               'dueAfter', CASE WHEN o.due_minutes IS NOT NULL THEN 'PT' || o.due_minutes || 'M' END,
               'onExpiry', o.on_expiry))
  FROM approval_policy_override o
  JOIN setting_profile p ON p.tenant_id = o.tenant_id AND p.scope_type = 'ORG_DEFAULT'
 WHERE o.subject_type IN ('ORDER_CANCELLATION', 'POD_REVERSAL');
