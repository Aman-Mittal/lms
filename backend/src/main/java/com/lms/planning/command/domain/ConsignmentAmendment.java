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
package com.lms.planning.command.domain;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A change to a consignment's particulars, judged field by field.
 *
 * <p>The caller names only the fields it changes. A field set to blank text is
 * cleared; one left out, or sent as null, is kept -- so a client whose
 * generated model writes every property, set or not, changes only what it set.
 * Each named field gets
 * an outcome -- APPLIED, UNCHANGED or REFUSED with a code -- so a screen can
 * mark every field, not only the first that was wrong. The amendment is all or
 * nothing: one refused field and nothing is applied.
 *
 * <p>A field that describes what the consignment carries -- weight, volume,
 * lines, material, consignee, destination -- is refused with
 * {@code consignment-contents-fixed}: contents are fixed at generation, and a
 * consignment that should carry something else is cancelled and generated
 * again from its order.
 */
public final class ConsignmentAmendment {

    /** What became of one named field. */
    public enum Outcome {
        APPLIED, UNCHANGED, REFUSED
    }

    /** One named field's outcome; {@code code} and {@code message} only when refused. */
    public record FieldResult(String field, Outcome outcome, String code, String message) {
    }

    /** The particulars after the change, and every named field's outcome in the order named. */
    public record Result(ConsignmentParticulars next, List<FieldResult> fields) {

        public boolean refused() {
            return fields.stream().anyMatch(f -> f.outcome() == Outcome.REFUSED);
        }

        public boolean changed() {
            return fields.stream().anyMatch(f -> f.outcome() == Outcome.APPLIED);
        }

        /** Each refused field to its reason, in the order named. */
        public Map<String, String> refusals() {
            Map<String, String> refusals = new LinkedHashMap<>();
            fields.stream().filter(f -> f.outcome() == Outcome.REFUSED)
                    .forEach(f -> refusals.put(f.field(), f.message()));
            return refusals;
        }

        /** {@code consignment-contents-fixed} when any contents field was named, else the general code. */
        public String refusalCode() {
            return fields.stream().anyMatch(f -> CONTENTS_FIXED.equals(f.code()))
                    ? CONTENTS_FIXED : "consignment-amendment-invalid";
        }
    }

    /** The code for a field that names what the consignment carries. */
    public static final String CONTENTS_FIXED = "consignment-contents-fixed";

    /** Fields describing what the consignment carries, as a client might name them. */
    public static final Set<String> CONTENTS = Set.of("totalDeadWeightKg", "deadWeightKg",
            "weightKg", "totalVolumeM3", "volumeM3", "chargeableWeightKg", "lines", "orderLines",
            "materialClasses", "materialClass", "material", "containsHazmat",
            "consigneePartnerId", "destinationTerminalId", "quantity");

    /** The fields that may be amended, in the order a result lists them when named. */
    public static final List<String> AMENDABLE = List.of("contactName", "contactPhone",
            "consigneeReference", "clientReference", "deliveryWindowFrom", "deliveryWindowTo",
            "remarks");

    static final int NAME_MAX = 120;
    static final int PHONE_MAX = 40;
    static final int REFERENCE_MAX = 80;
    static final int REMARKS_MAX = 500;

    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 ()\\-]{4,39}");
    private static final Pattern CLIENT_REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/\\-]{0,79}");

    private ConsignmentAmendment() {
    }

    /**
     * Judges each named field against the current particulars.
     *
     * @param changes field name to its new value: blank text to clear, text to
     *                set, null to keep (as if not named); anything else is
     *                refused as not text
     * @throws IllegalArgumentException when no field is named with a value
     */
    public static Result judge(ConsignmentParticulars current, Map<String, ?> requested) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (requested != null) {
            requested.forEach((field, value) -> {
                if (value != null) {
                    changes.put(field, value);
                }
            });
        }
        if (changes.isEmpty()) {
            throw new IllegalArgumentException("An amendment must name at least one field with a value");
        }
        ConsignmentParticulars was = current == null ? ConsignmentParticulars.NONE : current;
        Map<String, Object> accepted = new LinkedHashMap<>();
        List<FieldResult> results = new ArrayList<>();
        for (Map.Entry<String, Object> change : changes.entrySet()) {
            String field = change.getKey();
            FieldResult refusal = refusal(field, change.getValue());
            if (refusal != null) {
                results.add(refusal);
            } else {
                accepted.put(field, normalised(field, change.getValue()));
                results.add(null);
            }
        }

        ConsignmentParticulars next = new ConsignmentParticulars(
                text(accepted, "contactName", was.contactName()),
                text(accepted, "contactPhone", was.contactPhone()),
                text(accepted, "consigneeReference", was.consigneeReference()),
                text(accepted, "clientReference", was.clientReference()),
                instant(accepted, "deliveryWindowFrom", was.deliveryWindowFrom()),
                instant(accepted, "deliveryWindowTo", was.deliveryWindowTo()),
                text(accepted, "remarks", was.remarks()));
        boolean windowBackwards = next.deliveryWindowFrom() != null && next.deliveryWindowTo() != null
                && next.deliveryWindowTo().isBefore(next.deliveryWindowFrom());

        int i = 0;
        for (String field : changes.keySet()) {
            if (results.get(i) == null) {
                if (windowBackwards && field.startsWith("deliveryWindow")) {
                    results.set(i, refused(field, "delivery-window-invalid",
                            "The delivery window must not end before it starts"));
                } else {
                    boolean same = Objects.equals(valueOf(was, field), valueOf(next, field));
                    results.set(i, new FieldResult(field, same ? Outcome.UNCHANGED : Outcome.APPLIED,
                            null, null));
                }
            }
            i++;
        }
        return new Result(next, List.copyOf(results));
    }

    /** What a field holds, for the audit trail and for comparison. */
    public static Object valueOf(ConsignmentParticulars particulars, String field) {
        return switch (field) {
            case "contactName" -> particulars.contactName();
            case "contactPhone" -> particulars.contactPhone();
            case "consigneeReference" -> particulars.consigneeReference();
            case "clientReference" -> particulars.clientReference();
            case "deliveryWindowFrom" -> particulars.deliveryWindowFrom();
            case "deliveryWindowTo" -> particulars.deliveryWindowTo();
            case "remarks" -> particulars.remarks();
            default -> throw new IllegalArgumentException("Not an amendable field: " + field);
        };
    }

    private static FieldResult refusal(String field, Object value) {
        if (CONTENTS.contains(field)) {
            return refused(field, CONTENTS_FIXED, "What a consignment carries is fixed when it is "
                    + "generated; cancel it and generate it again from its order instead");
        }
        if (!AMENDABLE.contains(field)) {
            return refused(field, "field-not-amendable", "Not a particular of a consignment that "
                    + "can be amended");
        }
        if (!(value instanceof String)) {
            return refused(field, "value-not-text", "Must be text, or empty text to clear it");
        }
        String text = ((String) value).strip();
        if (text.isEmpty()) {
            return null;
        }
        return switch (field) {
            case "contactName" -> tooLong(field, text, NAME_MAX);
            case "consigneeReference" -> tooLong(field, text, REFERENCE_MAX);
            case "remarks" -> tooLong(field, text, REMARKS_MAX);
            case "contactPhone" -> PHONE.matcher(text).matches() ? null
                    : refused(field, "contact-phone-invalid", "A telephone number of digits, spaces, "
                            + "brackets and dashes, optionally starting with +, 5 to " + PHONE_MAX
                            + " characters");
            case "clientReference" -> CLIENT_REFERENCE.matcher(text).matches() ? null
                    : refused(field, "client-reference-invalid", "Letters, digits, dot, slash, dash "
                            + "and underscore, starting with a letter or digit, at most "
                            + REFERENCE_MAX + " characters");
            case "deliveryWindowFrom", "deliveryWindowTo" -> parses(text) ? null
                    : refused(field, "delivery-window-invalid", "An ISO-8601 instant, such as "
                            + "2026-11-05T09:00:00Z");
            default -> null;
        };
    }

    private static FieldResult tooLong(String field, String text, int max) {
        return text.length() <= max ? null
                : refused(field, "value-too-long", "At most " + max + " characters");
    }

    private static FieldResult refused(String field, String code, String message) {
        return new FieldResult(field, Outcome.REFUSED, code, message);
    }

    private static boolean parses(String text) {
        try {
            Instant.parse(text);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static Object normalised(String field, Object value) {
        String text = ((String) value).strip();
        if (text.isEmpty()) {
            return null;
        }
        return field.startsWith("deliveryWindow") ? Instant.parse(text) : text;
    }

    private static String text(Map<String, Object> accepted, String field, String current) {
        return accepted.containsKey(field) ? (String) accepted.get(field) : current;
    }

    private static Instant instant(Map<String, Object> accepted, String field, Instant current) {
        return accepted.containsKey(field) ? (Instant) accepted.get(field) : current;
    }
}
