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
package com.lms.warehouse.command.domain;

/**
 * Where stock can be. Each has exactly one kind of identifier, so a balance
 * key never needs a NULL.
 */
public enum LocationType {
    /** Received, not yet put away. Identified by the warehouse. */
    DOCK,
    /** In storage. Identified by the bin. */
    BIN,
    /** Picked, not yet packed. Identified by the outbound order. */
    OUTBOUND,
    /** Packed, not yet dispatched. Identified by the handling unit. */
    HU
}
