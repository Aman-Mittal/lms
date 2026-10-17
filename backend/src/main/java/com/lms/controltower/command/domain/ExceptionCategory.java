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
package com.lms.controltower.command.domain;

/**
 * What went wrong, which decides how bad it is (vision document 3.8).
 *
 * <p>The severity belongs to the category and is not chosen per ticket. A
 * severity somebody can pick drifts towards whatever gets attention fastest,
 * and P1 stops meaning anything within a month.
 */
public enum ExceptionCategory {

    /** A major accident. */
    ACCIDENT(Severity.P1),
    /** Material theft. */
    THEFT(Severity.P1),
    /** A breakdown bad enough that the load has to be moved to another vehicle. */
    BREAKDOWN(Severity.P1),

    /** The vehicle has left its permitted corridor. */
    ROUTE_DEVIATION(Severity.P2),
    /** An unplanned stop of more than twelve hours. */
    EXTENDED_STOPPAGE(Severity.P2),
    /** A refrigerated load outside its temperature band. */
    TEMPERATURE_EXCURSION(Severity.P2),

    /** The expected arrival has slipped by more than four hours. */
    ETA_DELAY(Severity.P3),
    /** A minor mismatch in the trip's paperwork. */
    DOCUMENT_MISMATCH(Severity.P3);

    private final Severity severity;

    ExceptionCategory(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
