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
package com.lms.controltower.command;

import java.util.Objects;
import java.util.UUID;

import com.lms.controltower.command.domain.ExceptionCategory;
import com.lms.controltower.command.domain.ExceptionTicket;

/**
 * A fact some other module published that deserves a ticket.
 *
 * @param sourceKey   unique per occurrence within a tenant; what turns a
 *                    redelivery into a no-op
 * @param tripId      optional; the trip the fact names, if any
 * @param subject     required; what the ticket is about besides the trip
 * @param description what happened, in words a desk can act on
 */
public record DetectedException(
        String sourceKey,
        ExceptionCategory category,
        ExceptionTicket.Source source,
        UUID tripId,
        ExceptionTicket.Subject subject,
        String description) {

    public DetectedException {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(subject, "subject");
        if (source == ExceptionTicket.Source.MANUAL) {
            throw new IllegalArgumentException("A detected exception is not raised by hand");
        }
    }
}
