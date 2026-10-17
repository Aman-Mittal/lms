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
package com.lms.order.api;

/**
 * How freight moves, which decides how it is planned and how it is priced.
 *
 * <p>Lives beside {@link MaterialClass} for the same reason: planning applies
 * it when it puts a consignment on a vehicle, and there must be one copy of
 * the vocabulary rather than two that drift apart. Modules outside the order
 * and planning pair receive it as its name on an event.
 */
public enum FreightMode {

    /** Full truckload: one customer's freight, the whole vehicle, priced per vehicle. */
    FTL,

    /**
     * Part load: each shipment travels on its own docket from an origin hub to a
     * destination hub, many customers share a vehicle, and each docket is billed
     * to its customer on its own chargeable weight, by zone.
     */
    LTL,

    /** Reserved. Named so that the vocabulary is stable; not yet planned or priced. */
    RAIL,

    /** Reserved, as {@link #RAIL}. */
    MILK_RUN;

    /** Whether the platform can plan and price this mode today. */
    public boolean isSupported() {
        return this == FTL || this == LTL;
    }

    /** Null means "not stated", which has always meant a full truckload. */
    public static FreightMode orDefault(FreightMode mode) {
        return mode == null ? FTL : mode;
    }
}
