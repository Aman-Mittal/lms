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

import java.util.UUID;

import com.lms.masterdata.api.TerminalCategoryPort;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Confirms that a terminal is a warehouse, and names who is acting.
 *
 * <p>Shared by the command services so the rule "only a WAREHOUSE terminal
 * holds stock" is written once.
 */
@Component
class Warehouses {

    static final String WAREHOUSE = "WAREHOUSE";

    private final TerminalCategoryPort terminals;

    Warehouses(TerminalCategoryPort terminals) {
        this.terminals = terminals;
    }

    void requireWarehouse(UUID terminalId) {
        String category = terminals.functionalCategoryOf(terminalId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse", terminalId));
        if (!WAREHOUSE.equals(category)) {
            throw new BusinessRuleViolationException("terminal-not-a-warehouse",
                    "Terminal " + terminalId + " is a " + category
                            + ", not a warehouse; stock is only held at warehouses");
        }
    }

    /** Any terminal of this tenant's: where a dispatched unit is bound. */
    void requireTerminal(UUID terminalId) {
        if (terminals.functionalCategoryOf(terminalId).isEmpty()) {
            throw new ResourceNotFoundException("Terminal", terminalId);
        }
    }

    static String currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null
                ? "system"
                : authentication.getName();
    }
}
