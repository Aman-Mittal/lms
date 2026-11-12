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

package com.lms.finance.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A keyset position for the money lists: a sort time (to the microsecond, as
 * stored) or a due date, and the id that breaks ties. The cursor a client sees
 * is opaque to it.
 */
record Keyset(Instant at, LocalDate day, UUID id) {

    static String ofTime(Instant at, UUID id) {
        Instant micros = at.truncatedTo(ChronoUnit.MICROS);
        return "t" + micros.getEpochSecond() + "." + String.format("%06d", micros.getNano() / 1000) + "_" + id;
    }

    static String ofDay(LocalDate day, UUID id) {
        return "d" + day + "_" + id;
    }

    /** The position {@code cursor} names, or null for the first page; the kind must be the list's own. */
    static Keyset parseTime(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            if (!cursor.startsWith("t")) {
                throw new IllegalArgumentException("wrong kind");
            }
            String[] parts = cursor.substring(1).split("_", 2);
            String[] seconds = parts[0].split("\\.", 2);
            return new Keyset(Instant.ofEpochSecond(Long.parseLong(seconds[0]), Long.parseLong(seconds[1]) * 1000),
                    null, UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The cursor is not one this list handed out", e);
        }
    }

    static Keyset parseDay(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            if (!cursor.startsWith("d")) {
                throw new IllegalArgumentException("wrong kind");
            }
            String[] parts = cursor.substring(1).split("_", 2);
            return new Keyset(null, LocalDate.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The cursor is not one this list handed out", e);
        }
    }

    java.sql.Timestamp atParam() {
        return at == null ? null : java.sql.Timestamp.from(at);
    }
}
