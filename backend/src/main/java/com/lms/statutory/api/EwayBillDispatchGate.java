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
package com.lms.statutory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Asked by trip execution at dispatch: which consignments on the load still
 * lack a live, unexpired e-way bill. Empty, always, for a unit that has not
 * switched {@code statutory.ewayBill.requiredAtDispatch} on, so a tenant that
 * has not adopted the module is dispatched exactly as before.
 */
public interface EwayBillDispatchGate {

    /**
     * One sentence per consignment that cannot leave, naming it and why.
     *
     * @param orgUnitId the unit the trip was raised under, whose setting applies
     * @param at        the moment of departure, which a bill must still be valid at
     */
    List<String> blockers(UUID orgUnitId, UUID loadId, Instant at);
}
