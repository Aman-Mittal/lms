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
package com.lms.planning;

import java.math.BigDecimal;
import java.util.UUID;

import com.lms.planning.command.domain.LoadUnit;
import com.lms.planning.command.domain.LoadUnit.LoadStatus;
import com.lms.shared.error.BusinessRuleViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The load's award can be withdrawn, and only while the load has not left. */
class LoadAwardWithdrawalTest {

    private static LoadUnit planned() {
        LoadUnit open = LoadUnit.open(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "L-1", UUID.randomUUID(), "RIGID", new BigDecimal("1000"), new BigDecimal("10"));
        return open.transitionTo(LoadStatus.PLANNED);
    }

    @Test
    @DisplayName("withdrawing an award returns the load to PLANNED and forgets the vendor")
    void withdrawalReturnsTheLoad() {
        UUID vendor = UUID.randomUUID();
        LoadUnit awarded = planned().awardTo(vendor);
        assertThat(awarded.status()).isEqualTo(LoadStatus.AWARDED);
        assertThat(awarded.awardedVendorPartnerId()).isEqualTo(vendor);

        LoadUnit back = awarded.withdrawAward();

        assertThat(back.status()).isEqualTo(LoadStatus.PLANNED);
        assertThat(back.awardedVendorPartnerId()).isNull();
        assertThat(back.version()).isEqualTo(awarded.version());
    }

    @Test
    @DisplayName("a load can be awarded again after its award was withdrawn")
    void awardedAgain() {
        LoadUnit again = planned().awardTo(UUID.randomUUID()).withdrawAward()
                .awardTo(UUID.randomUUID());
        assertThat(again.status()).isEqualTo(LoadStatus.AWARDED);
    }

    @Test
    @DisplayName("withdrawing an award that is not there leaves a planned load as it is")
    void nothingToWithdraw() {
        LoadUnit load = planned();
        assertThat(load.withdrawAward().status()).isEqualTo(LoadStatus.PLANNED);
    }

    @Test
    @DisplayName("an award cannot be withdrawn from a load that has left or finished")
    void leftLoadsRefuse() {
        LoadUnit dispatched = planned().awardTo(UUID.randomUUID()).transitionTo(LoadStatus.DISPATCHED);
        assertThatThrownBy(dispatched::withdrawAward)
                .isInstanceOfSatisfying(BusinessRuleViolationException.class,
                        e -> assertThat(e.code()).isEqualTo("load-illegal-transition"));
        assertThatThrownBy(() -> dispatched.transitionTo(LoadStatus.COMPLETED).withdrawAward())
                .isInstanceOf(BusinessRuleViolationException.class);
    }
}
