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

-- Routing guides become something a tenant can manage itself (vision 3.5).
--
-- Until now a guide could only be written by a test fixture going straight to
-- the repository, which made sourcing impossible to demonstrate over the API:
-- a fresh tenant had no way to say which vendors serve which lane. Defining a
-- guide is a commercial act -- it decides who gets offered freight -- so it is
-- its own permission rather than a side effect of being allowed to allocate.
INSERT INTO permission (id, code, resource, action)
VALUES (gen_random_uuid(), 'ROUTING_GUIDE_MANAGE', 'ROUTING_GUIDE', 'UPDATE');
