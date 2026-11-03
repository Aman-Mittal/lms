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
 * transport charges for every contracted client with full-truckload freight
 * aboard. Part-load dockets are not charged here: finance bills each one to its
 * customer as a docket receivable, and charging the trips it rode as well
 * would bill the same shipment twice.
 * A handling unit the warehouse hands to transport (the unit's SKUs name the
 * client that owns the goods) is charged handling out and transport at the
 * client's contract rates, from {@code warehouse::events}; the consignment it
 * becomes has no order, so no trip charge ever covers it.
 * Warehousing arrives as recorded service usage -- storage pallet-days,
 * handling in and out, value-added services -- through an idempotent endpoint
 * any warehouse system can feed. Storage can instead be computed from the
 * warehouse ledger, read through {@code warehouse::api}: the units of the
 * client's own SKUs held at the end of each day, in pallets of the contract's
 * units-per-pallet, rounded up per day.
 *
 * <p>Invoices carry tax per line at the contract's rate for the service
 * family, and are corrected only by credit notes, never edited.
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
        allowedDependencies = {"execution::events", "identity::access", "warehouse::api",
                "warehouse::events"})
package com.lms.clientbilling;
