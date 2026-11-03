/*
 * Copyright 2026 Aman Mittal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Custom fields: attributes a tenant defines for itself on core records.
 *
 * <p>Shippers differ in what they track -- a purchase-order number, a customs
 * reference, a cold-chain class -- and each used to be a code change. A tenant
 * now defines a field (a key, a label, a type, whether it is required) for one
 * kind of record and stores a value per record.
 *
 * <p>Depends on nothing. The values live in this module's own table, keyed by
 * the record's kind and id, so no other module's schema or API changes. That
 * the record exists, for the caller's tenant, is checked with a lookup under
 * row-level security rather than by importing the module that owns it: the
 * check needs a table name, not a type.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Custom Fields",
        allowedDependencies = {})
package com.lms.customfield;
