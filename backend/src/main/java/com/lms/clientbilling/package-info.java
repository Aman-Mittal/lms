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
 * <p>A contract version is a draft first (editable and deletable by its maker,
 * numbered from a CONTRACT series when the tenant has one), checked against a
 * completeness checklist the tenant tunes (a COMPLIANCE setting), and put into
 * force by an approval -- {@code CONTRACT_ACTIVATION}, decided by somebody
 * other than the maker -- which is when it closes the version it succeeds.
 * An optional {@code validTill} ends it; whether it has expired is derived from
 * the date, never stored. The only change a version in force accepts is an
 * approved extension of that date ({@code CONTRACT_EXTENSION}); a new charge is
 * added by an approved {@code CONTRACT_CHARGE_ADD}, which publishes a successor
 * from a future date so no raised bill is ever repriced.
 *
 * <p>A contract version also carries lanes (origin and destination as a
 * terminal or an LTL zone, freight mode, load type, a rate basis with weight
 * slabs, transit days, allowed consignors and consignees) and allowances
 * (direct orders, load types). Lanes are part of the version: editable while
 * it is a draft, frozen once it is not, copied into a charge-add successor.
 * {@code order::api}'s {@code OrderContractPort} is implemented here: an order
 * names the version in force on its date and the lane it travels on, or is
 * refused by name; a transport charge is priced from that lane, never
 * repriced once raised.
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
 *
 * <p>A trip's transport charge is invoiced only once each of the client's
 * consignments on the trip has a confirmed proof of delivery, or has been
 * marked billable by hand with a reason -- unless the client's contract bills
 * on dispatch, in which case the charge is captured when the trip leaves and
 * is invoiceable at once. Proofs are read with SQL, like the trip's freight,
 * so no dependency on the delivery module is needed.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "3PL Client Billing",
        allowedDependencies = {"execution::events", "identity::access", "warehouse::api",
                "warehouse::events", "masterdata::api", "settings::api", "approvals::api",
                "identity::numbering", "order::api", "finance::api"})
package com.lms.clientbilling;
