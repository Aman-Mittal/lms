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
 * Third-party logistics: client contracts, client-scoped charges and client
 * billing.
 *
 * <p>Finance is the shipper paying its carriers. This module is the other side
 * of the ledger: a 3PL running transport and warehousing for several clients
 * out of one tenant, invoicing each of them for what it did. A <em>client</em>
 * is a business partner of type {@code CUSTOMER} that holds a client contract.
 *
 * <p>Listens to {@code execution::events} for completed trips, which become
 * transport charges for every contracted client with freight aboard.
 * Warehousing arrives as recorded service usage -- storage pallet-days,
 * handling in and out, value-added services -- through an idempotent endpoint
 * any warehouse system, including the planned warehouse module, can feed.
 *
 * <p>Which client a trip carried for, and what the carrier billed for it, are
 * read with plain SQL on the query side rather than through another module's
 * classes: they are reads across aggregates, which is what the CQRS split
 * allows queries to do.
 *
 * <p>Client isolation is enforced here, by {@code ClientAccess}, for every read
 * and command in this module. It honours the partner attribute that
 * {@code identity::access} carries for a portal user, and adds this module's
 * own portal binding for users without one.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "3PL Client Billing",
        allowedDependencies = {"execution::events", "identity::access"})
package com.lms.clientbilling;
