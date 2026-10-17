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

import java.util.Optional;
import java.util.UUID;

/**
 * What kind of site a terminal is.
 *
 * <p>Asked by modules that only make sense at one kind of site -- a bin can be
 * created in a warehouse, not at a toll plaza. Synchronous because the answer
 * decides whether the command proceeds at all.
 */
public interface TerminalCategoryPort {

    /**
     * The terminal's functional category, such as {@code WAREHOUSE}, or empty
     * when no such terminal is visible to the caller's tenant.
     */
    Optional<String> functionalCategoryOf(UUID terminalId);
}
