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
package com.lms.masterdata.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Answers whether an asset is legally fit to be dispatched.
 *
 * <p>Backs the hard stop in vision document 3.2.2: "The system SHALL strictly
 * prevent the dispatch of any vehicle with an expired document." Execution
 * calls this before allowing a trip to leave the origin.
 */
public interface FleetCompliancePort {

    /**
     * Checks a vehicle and driver against the date they would dispatch on.
     *
     * <p>The date is a parameter rather than "today" on purpose. A trip planned
     * for next week must be judged against next week — a certificate valid now
     * but lapsing before departure is not a valid certificate — and it makes
     * the check testable without manipulating the clock.
     *
     * @param driverId may be null when no driver is assigned yet
     */
    ComplianceVerdict checkDispatchReadiness(UUID vehicleId, UUID driverId, LocalDate dispatchOn);

    /**
     * The outcome, as data rather than an exception.
     *
     * <p>Callers differ in what they want: execution refuses the dispatch,
     * while a planning screen wants to warn without blocking. Returning the
     * reasons lets each decide, and lets the operator see <em>every</em>
     * problem at once instead of fixing them one failed attempt at a time.
     */
    record ComplianceVerdict(boolean dispatchable, List<String> blockingReasons) {

        public ComplianceVerdict {
            blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        }

        public static ComplianceVerdict clear() {
            return new ComplianceVerdict(true, List.of());
        }

        public static ComplianceVerdict blocked(List<String> reasons) {
            return new ComplianceVerdict(false, reasons);
        }

        public String summary() {
            return String.join("; ", blockingReasons);
        }
    }
}
