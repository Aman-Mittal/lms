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
 * Warehousing: inbound, putaway, inventory, pick-pack and cross-dock.
 *
 * <p>A warehouse is a terminal of category {@code WAREHOUSE}, referenced by
 * id; this module adds what happens inside it. Goods arrive against an advance
 * shipping notice and are counted at the dock, where short, excess and damaged
 * quantities are recorded. They are put away to bins of fixed capacity, picked
 * against outbound orders -- one at a time or several together in a wave --
 * packed into handling units and handed off. What a pick cannot fill stays
 * owed as a back-order, filled by a later pick into a further unit. Damaged
 * units go into the warehouse's quarantine until an adjustment releases or
 * writes them off. A cross-docked receipt skips the bins and goes straight
 * into a handling unit.
 *
 * <p>Inventory is an append-only ledger. Every change of stock is a row in
 * {@code stock_movement}; the quantity at a location is derived from those
 * rows by the database and is never edited. A correction is a new adjustment,
 * not a changed movement. The database, not only the service, refuses negative
 * stock, an overfull bin, and any edit to a movement.
 *
 * <p>Depends on {@code masterdata::api} only, to confirm that a terminal is a
 * warehouse, or that a dispatched unit's destination exists. Receipt variances,
 * pick shortfalls, filled back-orders, resolved quarantine and the hand-off of
 * a handling unit are published from {@code events}. Planning consumes the
 * hand-off: a unit dispatched with a destination, consignee and weighed gross
 * becomes a consignment ready to be put on a load -- a back-order's further
 * unit becomes a further consignment. The control tower raises tickets from
 * receipt variances and pick shortfalls.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Warehousing",
        allowedDependencies = {"masterdata::api", "identity::access"})
package com.lms.warehouse;
