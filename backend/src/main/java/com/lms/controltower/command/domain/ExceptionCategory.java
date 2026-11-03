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
    /**
     * A multimodal leg arrived too late for the next one to leave as planned.
     * Critical: the rest of the itinerary now has to be re-booked.
     */
    MISSED_CONNECTION(Severity.P2),
    /**
     * An unplanned stop: a trip in transit that has not moved for longer than
     * the detector's threshold (45 minutes by default), outside every terminal.
     * A person may also raise it for a longer stop.
     */
    EXTENDED_STOPPAGE(Severity.P2),
    /** A refrigerated load outside its temperature band. */
    TEMPERATURE_EXCURSION(Severity.P2),

    /** The expected arrival has slipped by more than four hours. */
    ETA_DELAY(Severity.P3),
    /** A minor mismatch in the trip's paperwork. */
    DOCUMENT_MISMATCH(Severity.P3),

    /** A supplier on a milk-run circuit was not collected from. */
    MILK_RUN_STOP_SKIPPED(Severity.P3),
    /** A milk-run vehicle reached a supplier after its pickup window closed. */
    MILK_RUN_STOP_LATE(Severity.P3),
    /** An ASN line was counted in short, over or damaged. */
    RECEIPT_VARIANCE(Severity.P3),
    /** An outbound line could not be picked in full. */
    PICK_SHORTFALL(Severity.P3);

    private final Severity severity;

    ExceptionCategory(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
