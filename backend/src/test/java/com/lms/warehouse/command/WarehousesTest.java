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
package com.lms.warehouse.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The two terminal checks the warehouse commands rely on, against a stand-in for the master data port. */
class WarehousesTest {

    private static Warehouses knowing(String category) {
        TerminalCategoryPort port = id -> Optional.ofNullable(category);
        return new Warehouses(port);
    }

    @Test
    @DisplayName("any terminal the tenant can see will do where a dispatched unit is bound")
    void anyKnownTerminalPassesRequireTerminal() {
        assertThatCode(() -> knowing("TOLL_PLAZA").requireTerminal(UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a terminal that is not visible is reported as not found")
    void unknownTerminalIsNotFound() {
        assertThatThrownBy(() -> knowing(null).requireTerminal(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Terminal");
        assertThatThrownBy(() -> knowing(null).requireWarehouse(UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Warehouse");
    }

    @Test
    @DisplayName("stock is held only at a warehouse")
    void onlyWarehousesHoldStock() {
        assertThatCode(() -> knowing("WAREHOUSE").requireWarehouse(UUID.randomUUID()))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> knowing("TOLL_PLAZA").requireWarehouse(UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("not a warehouse");
    }
}
