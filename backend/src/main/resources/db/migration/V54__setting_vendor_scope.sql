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

-- A setting profile may now be a vendor's: a carrier or other supplier the
-- tenant pays, for keys kept per vendor (a freight bill's match tolerance).
-- scope_id names a business_partner of type VENDOR, looked up under row-level
-- security by the application, as a customer's is. In the fold VENDOR sits
-- next to CUSTOMER and above it:
-- PLATFORM < ORG_DEFAULT < ORG_UNIT < VEHICLE < CUSTOMER < VENDOR < USER;
-- no key a customer's context reads is one a vendor's does.
ALTER TABLE setting_profile DROP CONSTRAINT setting_profile_scope_type_valid;
ALTER TABLE setting_profile ADD CONSTRAINT setting_profile_scope_type_valid
    CHECK (scope_type IN ('PLATFORM', 'ORG_DEFAULT', 'ORG_UNIT', 'VEHICLE', 'CUSTOMER', 'VENDOR', 'USER'));
