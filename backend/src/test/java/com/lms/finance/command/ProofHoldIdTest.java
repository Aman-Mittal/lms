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
package com.lms.finance.command;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A trip has at most one proof hold, however often its completion is delivered. */
class ProofHoldIdTest {

    @Test
    @DisplayName("the hold's key is the same for one trip every time and differs between trips")
    void oneHoldPerTrip() {
        UUID trip = UUID.randomUUID();
        assertThat(SettlementHolds.proofHoldId(trip)).isEqualTo(SettlementHolds.proofHoldId(trip));
        assertThat(SettlementHolds.proofHoldId(trip)).isNotEqualTo(SettlementHolds.proofHoldId(UUID.randomUUID()));
        assertThat(SettlementHolds.proofHoldId(trip)).as("never a trip's own id").isNotEqualTo(trip);
    }
}
