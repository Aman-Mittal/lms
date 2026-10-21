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
package com.lms.warehouse.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * How much of one owner's stock the tenant's warehouses held, day by day.
 *
 * <p>The owner is the business partner on the SKU ({@code sku.owner_partner_id}).
 * A unit counts as held from the movement that brought it in until the one
 * that took it out of the building -- wherever it stood in between: on the
 * dock, in a bin, picked onto an order, packed into a handling unit, or held
 * in quarantine. Quarantined stock counts as held, deliberately: it is still
 * in the building, occupying a position, until it is released back to a bin
 * or written off. A
 * transfer inside the warehouse is two movements that cancel, so it changes
 * nothing here.
 *
 * <p>Read-only, and must be called inside a transaction: the ledger is
 * tenant-scoped under row-level security, and outside one it reads as empty.
 */
public interface OwnerStockPort {

    /**
     * Units of the owner's SKUs held at the end of each day, UTC, across all of
     * the tenant's warehouses.
     *
     * @param from first day, inclusive
     * @param to   last day, inclusive
     * @return one entry per day from {@code from} to {@code to}, in order,
     *         including days on which nothing was held
     */
    List<DailyHolding> unitsHeldEachDay(UUID ownerPartnerId, LocalDate from, LocalDate to);

    /** Units held at the end of one day. */
    record DailyHolding(LocalDate day, long units) {
    }
}
