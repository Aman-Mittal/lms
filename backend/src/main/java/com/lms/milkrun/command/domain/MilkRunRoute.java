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
package com.lms.milkrun.command.domain;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One version of a milk-run circuit: where it collects, when it runs, and what
 * it pays.
 *
 * <p>Versioned like a rate card and for the same reason. A circuit's plan and
 * price change over time, and a run from last month must still be explainable
 * against the plan that applied last month. A version is never edited; a
 * successor is published from a date and the incumbent is closed the day
 * before.
 *
 * <p>The schedule lives on the version rather than in a table of its own. A
 * change of days or departure time is a change of plan, negotiated with the
 * same suppliers in the same conversation, and splitting it out would create a
 * second thing to version and a second way for the two halves to disagree.
 */
@Table("milk_run_route")
public record MilkRunRoute(
        @Id UUID id,
        UUID tenantId,
        String code,
        String name,
        UUID plantTerminalId,
        LocalDate effectiveFrom,
        /* Inclusive last day, or null while this is the version in force. */
        LocalDate effectiveTo,
        /* Canonical, Monday first: "MON,WED,FRI". */
        String daysOfWeek,
        LocalTime departureTime,
        String timeZone,
        BigDecimal runRate,
        BigDecimal perStopFee,
        String currency,
        @Version Long version,
        Instant createdAt) {

    public static MilkRunRoute publish(UUID id, UUID tenantId, String code, String name,
                                       UUID plantTerminalId, LocalDate effectiveFrom,
                                       Collection<String> days, LocalTime departureTime,
                                       String timeZone, BigDecimal runRate,
                                       BigDecimal perStopFee, String currency) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A milk-run route needs a code somebody can refer to");
        }
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("A milk-run route must say when it starts applying");
        }
        if (departureTime == null) {
            throw new IllegalArgumentException("A milk-run route needs a departure time");
        }
        String zone = timeZone == null || timeZone.isBlank() ? "UTC" : timeZone.strip();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone '" + zone + "'");
        }
        BigDecimal rate = runRate == null ? BigDecimal.ZERO : runRate;
        BigDecimal fee = perStopFee == null ? BigDecimal.ZERO : perStopFee;
        if (rate.signum() < 0 || fee.signum() < 0) {
            throw new BusinessRuleViolationException("milk-run-rate-negative",
                    "A circuit's run rate and per-stop fee cannot be negative");
        }
        return new MilkRunRoute(id, tenantId, code.strip(),
                name == null || name.isBlank() ? code.strip() : name.strip(),
                plantTerminalId, effectiveFrom, null, canonicalDays(days), departureTime, zone,
                rate, fee, currency == null || currency.isBlank() ? "INR" : currency.strip(),
                null, Instant.now());
    }

    /** Whether this version was in force on the given day, both bounds inclusive. */
    public boolean coversOn(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** Whether the schedule calls for a circuit on this day of the week. */
    public boolean runsOn(LocalDate day) {
        return days().contains(day.getDayOfWeek());
    }

    /** When the circuit leaves on a given day, in the plant's own time zone. */
    public Instant plannedDepartureOn(LocalDate day) {
        return ZonedDateTime.of(day, departureTime, ZoneId.of(timeZone)).toInstant();
    }

    public Set<DayOfWeek> days() {
        EnumSet<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String token : daysOfWeek.split(",")) {
            days.add(parseDay(token));
        }
        return days;
    }

    /**
     * Closes this version so a successor can take over the following day.
     *
     * <p>The only permitted change to a published version.
     */
    public MilkRunRoute closeOn(LocalDate lastDay) {
        if (effectiveTo != null) {
            throw new BusinessRuleViolationException("milk-run-route-already-closed",
                    "Route " + code + " was already closed on " + effectiveTo);
        }
        if (lastDay.isBefore(effectiveFrom)) {
            throw new BusinessRuleViolationException("milk-run-route-backdated",
                    "Route " + code + " has been in force since " + effectiveFrom
                            + "; a successor cannot start on " + lastDay.plusDays(1));
        }
        return new MilkRunRoute(id, tenantId, code, name, plantTerminalId, effectiveFrom, lastDay,
                daysOfWeek, departureTime, timeZone, runRate, perStopFee, currency, version,
                createdAt);
    }

    /**
     * Normalises a list of day names to the stored form.
     *
     * <p>Monday first and de-duplicated, so two versions with the same
     * schedule store the same string however the caller happened to list it.
     */
    static String canonicalDays(Collection<String> days) {
        if (days == null || days.isEmpty()) {
            throw new BusinessRuleViolationException("milk-run-no-days",
                    "A milk-run schedule needs at least one day of the week to run on");
        }
        EnumSet<DayOfWeek> parsed = EnumSet.noneOf(DayOfWeek.class);
        for (String day : days) {
            parsed.add(parseDay(day));
        }
        return parsed.stream()
                .map(day -> day.name().substring(0, 3))
                .collect(Collectors.joining(","));
    }

    private static DayOfWeek parseDay(String token) {
        String key = token == null ? "" : token.strip().toUpperCase(Locale.ROOT);
        for (DayOfWeek day : DayOfWeek.values()) {
            if (key.length() >= 3 && day.name().startsWith(key)) {
                return day;
            }
        }
        throw new IllegalArgumentException("Not a day of the week: '" + token + "'");
    }
}
