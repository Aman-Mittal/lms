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
 * Which organisational unit owns a terminal.
 *
 * <p>Asked by modules that file a record where a terminal is -- a rail leg's
 * freight bill belongs to the unit that owns its origin railhead, so that the
 * approver's organisational scope applies to it as it does to a trip's.
 */
public interface TerminalOrgUnitPort {

    /** The owning unit, or empty when no such terminal is visible to the caller's tenant. */
    Optional<UUID> orgUnitOf(UUID terminalId);
}
