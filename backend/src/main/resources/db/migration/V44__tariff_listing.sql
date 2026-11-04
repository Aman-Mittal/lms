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


-- Reading road rate cards back.
--
-- GET /tariffs lists every version of a tenant's cards newest first, a page at
-- a time, keyed on (created_at, id). Without this index each page is a sort of
-- the tenant's whole rate history; with it the listing stops after one page,
-- as the rail card listing does on rail_tariff_listing_idx.
CREATE INDEX tariff_listing_idx ON tariff (tenant_id, created_at DESC, id DESC);
