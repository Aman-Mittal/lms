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
package com.lms.execution.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A trip was cancelled before its vehicle left the yard.
 *
 * <p>A fact for any module that holds something against the trip. Nothing
 * reacts to it today, deliberately: a cancelled trip never reaches
 * completion, so finance has no bill to withhold; the load stays awarded and
 * is released by execution itself (a replacement trip can be raised); and
 * control tower tickets about the trip stay open for a person to resolve and
 * close, because closing one can release held settlement money and so needs
 * the access policies a person is subject to, not a system shortcut. A
 * listener added later must be safe to run twice.
 */
public record TripCancelled(
        UUID tenantId,
        UUID tripId,
        String tripNo,
        UUID loadId,
        UUID vendorPartnerId,
        String reason,
        Instant cancelledAt,
        Instant occurredAt) {
}
