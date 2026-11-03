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
package com.lms.clientbilling.command.domain;

/**
 * Units held to pallet positions, for storage computed from the ledger.
 *
 * <p>Rounded up: a part pallet occupies a whole position in the racking, and
 * a client holding one unit is still holding one position.
 */
public final class StoragePallets {

    private StoragePallets() {
    }

    /**
     * @param units          units held, zero or more
     * @param unitsPerPallet units making one pallet position, one or more
     * @return {@code ceil(units / unitsPerPallet)}
     */
    public static long of(long units, int unitsPerPallet) {
        if (units < 0) {
            throw new IllegalArgumentException("Units held cannot be negative");
        }
        if (unitsPerPallet <= 0) {
            throw new IllegalArgumentException("A pallet holds at least one unit");
        }
        return Math.ceilDiv(units, (long) unitsPerPallet);
    }
}
