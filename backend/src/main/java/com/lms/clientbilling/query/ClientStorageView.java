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
package com.lms.clientbilling.query;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A client's storage for one month as computed from the warehouse ledger.
 *
 * @param unitDays   units held at the end of each day, summed over the month
 * @param palletDays pallet positions per day -- units over the contract's
 *                   units-per-pallet, rounded up -- summed over the month;
 *                   what the storage charges bill
 * @param days       every day of the month, including empty ones
 */
public record ClientStorageView(
        UUID clientPartnerId,
        LocalDate periodStart,
        boolean computed,
        long unitDays,
        long palletDays,
        List<Day> days) {

    public ClientStorageView {
        days = days == null ? List.of() : List.copyOf(days);
    }

    /** One day's closing stock and the pallets it was charged as. */
    public record Day(LocalDate heldOn, long unitsHeld, Integer unitsPerPallet, long pallets,
                      UUID chargeId) {
    }
}
