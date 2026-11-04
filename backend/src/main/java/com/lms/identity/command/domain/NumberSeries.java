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
package com.lms.identity.command.domain;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lms.identity.numbering.DocumentType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One way a tenant numbers one type of document, at one scope.
 *
 * <p>The scope is the whole tenant ({@code orgUnitId} null) or one
 * organisational unit with everything beneath it, and it is fixed once
 * created, as are the code and the document type: a series that moved would
 * leave the numbers it issued attributed to the wrong place. The format may
 * change only while nothing has been issued from the series, which the
 * service checks, because a changed format could no longer verify the numbers
 * already handed out.
 *
 * <p>The counters and the released-number pool are not part of the aggregate.
 * They are advanced with single statements under a row lock, which is what
 * makes concurrent numbering safe, and loading them here would turn that into
 * read-modify-write.
 */
@Table("number_series")
public record NumberSeries(
        @Id UUID id,
        UUID tenantId,
        String code,
        DocumentType documentType,
        UUID orgUnitId,
        String prefix,
        SeriesFormat.YearCode yearCode,
        String separator,
        int counterLength,
        int yearStartMonth,
        String timeZone,
        boolean reuseReleased,
        boolean active,
        String description,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    private static final Pattern SAFE_CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,47}$");

    public NumberSeries {
        if (code == null || !SAFE_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "A series code is 1-48 letters, digits, '_' or '-', starting with a letter or digit: " + code);
        }
        if (documentType == null) {
            throw new IllegalArgumentException("A series numbers one document type; none was given");
        }
        if (description != null && description.length() > 500) {
            throw new IllegalArgumentException("A description is at most 500 characters");
        }
        // Validates prefix, year code, separator, length, month and zone together.
        new SeriesFormat(prefix, yearCode, separator, counterLength, yearStartMonth,
                SeriesFormat.zoneOf(timeZone));
    }

    /** A new series, active unless {@code active} says otherwise. */
    public static NumberSeries create(UUID id, UUID tenantId, String code, DocumentType documentType,
                                      UUID orgUnitId, SeriesFormat format, boolean reuseReleased,
                                      boolean active, String description) {
        Instant now = Instant.now();
        return new NumberSeries(id, tenantId, code, documentType, orgUnitId, format.prefix(),
                format.yearCode(), format.separator(), format.counterLength(), format.yearStartMonth(),
                format.zone().getId(), reuseReleased, active, blankToNull(description), null, now, now);
    }

    /** How this series spells its numbers. */
    public SeriesFormat format() {
        return new SeriesFormat(prefix, yearCode, separator, counterLength, yearStartMonth,
                SeriesFormat.zoneOf(timeZone));
    }

    /** True when {@code format} spells numbers differently from this series. */
    public boolean formatDiffers(SeriesFormat format) {
        return !format().equals(format);
    }

    public NumberSeries revise(SeriesFormat format, boolean reuse, String newDescription) {
        return new NumberSeries(id, tenantId, code, documentType, orgUnitId, format.prefix(),
                format.yearCode(), format.separator(), format.counterLength(), format.yearStartMonth(),
                format.zone().getId(), reuse, active, blankToNull(newDescription), version, createdAt,
                Instant.now());
    }

    public NumberSeries withActive(boolean nowActive) {
        return new NumberSeries(id, tenantId, code, documentType, orgUnitId, prefix, yearCode,
                separator, counterLength, yearStartMonth, timeZone, reuseReleased, nowActive,
                description, version, createdAt, Instant.now());
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
