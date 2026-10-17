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
 * What a client is charged for.
 *
 * <p>Transport arrives from completed trips; everything else is warehousing,
 * recorded as service usage. Each carries the unit its contract rate is quoted
 * in, which is the unit a recorded quantity is taken to be in.
 */
public enum ServiceType {

    /** A completed movement, billed on the client's chargeable weight. */
    TRANSPORT("KG"),
    /** Pallets held, multiplied by the days they were held. */
    STORAGE("PALLET_DAY"),
    /** Pallets received into the warehouse. */
    HANDLING_IN("PALLET"),
    /** Pallets picked and dispatched out of the warehouse. */
    HANDLING_OUT("PALLET"),
    /**
     * Labelling, kitting, shrink-wrapping and the like. The unit is whatever
     * the contract's rate for that service code says.
     */
    VALUE_ADDED(null);

    private final String unit;

    ServiceType(String unit) {
        this.unit = unit;
    }

    /** The fixed unit, or null where the contract's service rate decides it. */
    public String unit() {
        return unit;
    }

    /** Whether usage of this type may be recorded by hand or by a warehouse feed. */
    public boolean isWarehouseService() {
        return this != TRANSPORT;
    }
}
