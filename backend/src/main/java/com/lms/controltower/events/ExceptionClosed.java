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
package com.lms.controltower.events;

import java.time.Instant;
import java.util.UUID;

/**
 * A manager has closed a ticket; whatever it was holding up may proceed.
 *
 * <p>Carries the trip and ticket number as well as the identifier, so a
 * listener that receives this before the matching {@link ExceptionRaised} --
 * which the outbox permits after a restart -- still has everything it needs to
 * record the release.
 */
public record ExceptionClosed(
        UUID tenantId,
        UUID exceptionId,
        String ticketNo,
        UUID tripId,
        boolean suspendsSettlement,
        Instant raisedAt,
        Instant closedAt) {
}
