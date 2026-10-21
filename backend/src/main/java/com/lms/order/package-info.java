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
 * Demand ingestion and validation (vision document 3.3).
 *
 * <p>Turns commercial demand -- indents, sales orders, purchase orders -- into
 * validated line items with a chargeable weight, ready for planning to group
 * into consignments.
 *
 * <p>Two declared dependencies. {@code identity::access} is read to confine a
 * customer-portal user to their own orders. {@code masterdata::api} answers,
 * read-only, whether each hub a part load names really is a hub: the database
 * can say a terminal exists but not what kind of site it is. Otherwise orders
 * reference partners and terminals by identifier and let the database enforce
 * those references. Planning depends on orders, never the reverse.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Order Management",
        allowedDependencies = {"identity::access", "masterdata::api"})
package com.lms.order;
