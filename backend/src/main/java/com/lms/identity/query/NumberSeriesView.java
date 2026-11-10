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
package com.lms.identity.query;

import java.time.Instant;
import java.util.UUID;

/**
 * A number series as the administration desk reads it, with where its
 * counter stands in the current financial year.
 *
 * @param orgUnitId           the unit whose subtree it numbers; null for the whole tenant
 * @param financialYear       the current financial year, by the year it starts in; 0 for a
 *                            series without a year code, whose counter never restarts
 * @param issuedThisYear      how far the counter has advanced in that year
 * @param lastIssued          the number the counter last gave, or null when none yet
 * @param releasedAvailable   released numbers of that year not issued again
 * @param sample              the first number of the current year, to show the format
 */
public record NumberSeriesView(
        UUID id,
        String code,
        String documentType,
        UUID orgUnitId,
        String orgUnitName,
        String orgUnitPath,
        String prefix,
        String yearCode,
        String separator,
        int counterLength,
        int yearStartMonth,
        String timeZone,
        boolean reuseReleased,
        boolean active,
        String description,
        int financialYear,
        long issuedThisYear,
        String lastIssued,
        long releasedAvailable,
        String sample,
        Instant createdAt,
        Instant updatedAt) {
}
