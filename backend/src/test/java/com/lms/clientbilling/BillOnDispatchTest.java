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
package com.lms.clientbilling;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Whether a client is billed on proof or on dispatch is a term of the contract version. */
class BillOnDispatchTest {

    private static final ClientContract.Rates RATES = new ClientContract.Rates(
            new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

    @Test
    @DisplayName("a contract bills on proof unless it says otherwise, and keeps its rule when closed")
    void ruleIsATerm() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        ClientContract onProof = ClientContract.publish(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "C-1", UUID.randomUUID(), "INR", from, RATES);
        assertThat(onProof.billOnDispatch()).isFalse();

        ClientContract onDispatch = ClientContract.publish(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "C-2", UUID.randomUUID(), "INR", from, RATES, true);
        assertThat(onDispatch.billOnDispatch()).isTrue();
        assertThat(onDispatch.closeOn(from.plusMonths(1)).billOnDispatch()).isTrue();
    }
}
