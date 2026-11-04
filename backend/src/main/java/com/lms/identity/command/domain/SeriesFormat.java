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

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a series spells its numbers, and which financial year a moment falls in.
 *
 * <p>A number is the prefix, then -- unless the year code is {@code NONE} --
 * the separator and the financial year's code, then the separator and the
 * counter zero-padded to {@code counterLength} digits. A counter that outgrows
 * its length gains digits rather than wrapping: the number stays unique and
 * only stops sorting as text.
 *
 * <p>A financial year is named by the calendar year it starts in: with the
 * year starting in April, 2026-04-01 to 2027-03-31 is financial year 2026.
 * The counter restarts every financial year, which is why a series that shows
 * no year in its numbers must not restart: it keeps one counter, under
 * financial year 0.
 *
 * <p>Pure: no clock, no database. Mirrors the checks on {@code number_series}.
 */
public record SeriesFormat(String prefix, YearCode yearCode, String separator, int counterLength,
                           int yearStartMonth, ZoneId zone) {

    /** How the financial year appears in a number. */
    public enum YearCode {
        /** Not at all; the counter never restarts. */
        NONE,
        /** The four-digit year the financial year starts in: {@code 2026}. */
        START_YEAR,
        /** The last two digits of the first and last calendar years it spans: {@code 2627}. */
        SPAN
    }

    /** The financial year a series without a year code keeps its one counter under. */
    public static final int CONTINUOUS = 0;

    /** Mirrors {@code number_series_prefix_safe}. */
    private static final Pattern SAFE_PREFIX = Pattern.compile("^[A-Z0-9][A-Z0-9/-]{0,15}$");
    /** Mirrors {@code number_series_separator_valid}. */
    public static final Set<String> SEPARATORS = Set.of("", "-", "/", "_", ".");
    /** More digits than this cannot be a counter value. */
    private static final int MAX_COUNTER_DIGITS = 18;

    public SeriesFormat {
        if (prefix == null || !SAFE_PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("A prefix is 1-16 upper-case letters, digits, '/' or '-',"
                    + " starting with a letter or digit: " + prefix);
        }
        if (yearCode == null) {
            throw new IllegalArgumentException("A year code is required: NONE, START_YEAR or SPAN");
        }
        if (separator == null || !SEPARATORS.contains(separator)) {
            throw new IllegalArgumentException(
                    "A separator is one of '-', '/', '_', '.' or nothing: '" + separator + "'");
        }
        if (counterLength < 1 || counterLength > 12) {
            throw new IllegalArgumentException("A counter is 1 to 12 digits long, not " + counterLength);
        }
        if (yearStartMonth < 1 || yearStartMonth > 12) {
            throw new IllegalArgumentException("A financial year starts in month 1 to 12, not " + yearStartMonth);
        }
        if (zone == null) {
            throw new IllegalArgumentException("A time zone is required");
        }
    }

    /** Parses a time zone id, refusing an unknown one as bad input. */
    public static ZoneId zoneOf(String id) {
        if (id == null || id.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(id.trim());
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown time zone '" + id + "'", e);
        }
    }

    /** The financial year {@code at} falls in, or {@link #CONTINUOUS} for a series that never restarts. */
    public int financialYear(Instant at) {
        return financialYearOf(at.atZone(zone).toLocalDate());
    }

    /** The financial year {@code day} falls in, or {@link #CONTINUOUS} for a series that never restarts. */
    public int financialYearOf(LocalDate day) {
        if (yearCode == YearCode.NONE) {
            return CONTINUOUS;
        }
        return day.getMonthValue() >= yearStartMonth ? day.getYear() : day.getYear() - 1;
    }

    /** The financial year's code as a number shows it; empty for a series without one. */
    public String yearCodeOf(int financialYear) {
        return switch (yearCode) {
            case NONE -> "";
            case START_YEAR -> "%04d".formatted(financialYear);
            case SPAN -> "%02d%02d".formatted(Math.floorMod(financialYear, 100),
                    Math.floorMod(yearStartMonth == 1 ? financialYear : financialYear + 1, 100));
        };
    }

    /** The number with counter {@code value} in {@code financialYear}. */
    public String format(int financialYear, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("A counter starts at 1, not " + value);
        }
        StringBuilder number = new StringBuilder(prefix);
        if (yearCode != YearCode.NONE) {
            number.append(separator).append(yearCodeOf(financialYear));
        }
        String digits = Long.toString(value);
        number.append(separator);
        number.append("0".repeat(Math.max(0, counterLength - digits.length())));
        return number.append(digits).toString();
    }

    /** A financial year and counter value read back out of a number. */
    public record Parsed(int financialYear, long value) {
    }

    /**
     * Reads a number back into its financial year and counter value.
     *
     * <p>Empty unless formatting the result gives back exactly {@code number}:
     * a wrong prefix, a missing separator, a year code that does not name a
     * year, too few digits or a superfluous leading zero all mean the number
     * is not one this series could have produced.
     */
    public Optional<Parsed> parse(String number) {
        if (number == null || !number.startsWith(prefix)) {
            return Optional.empty();
        }
        String rest = number.substring(prefix.length());
        int financialYear = CONTINUOUS;
        if (yearCode != YearCode.NONE) {
            if (!rest.startsWith(separator) || rest.length() < separator.length() + 4) {
                return Optional.empty();
            }
            String code = rest.substring(separator.length(), separator.length() + 4);
            if (!code.chars().allMatch(Character::isDigit)) {
                return Optional.empty();
            }
            financialYear = yearCode == YearCode.START_YEAR
                    ? Integer.parseInt(code)
                    : 2000 + Integer.parseInt(code.substring(0, 2));
            rest = rest.substring(separator.length() + 4);
        }
        if (!rest.startsWith(separator)) {
            return Optional.empty();
        }
        String digits = rest.substring(separator.length());
        if (digits.isEmpty() || digits.length() > MAX_COUNTER_DIGITS
                || !digits.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        long value = Long.parseLong(digits);
        if (value <= 0 || !format(financialYear, value).equals(number)) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(financialYear, value));
    }
}
