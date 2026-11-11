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
package com.lms.approvals.command;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicy.Expiry;

/**
 * An approval policy as a profile setting's JSON value, and back.
 *
 * <p>A value is a flat object (profile settings hold no lists or durations):
 * {@code required} (boolean), {@code steps} (permission codes, comma
 * separated, one to five), {@code ordered} and {@code selfApproval}
 * (booleans), {@code dueAfter} (an ISO-8601 duration of one minute to ninety
 * days) and {@code onExpiry} (EXPIRE or AUTO_APPROVE). Only {@code required}
 * is mandatory in the value; a property left out is the code default's, never
 * another profile's, because the fold picks one profile's whole value.
 *
 * <p>Pure, so one set of rules serves the write check and the resolver.
 */
final class ApprovalPolicyValues {

    static final String REQUIRED = "required";
    static final String STEPS = "steps";
    static final String ORDERED = "ordered";
    static final String SELF_APPROVAL = "selfApproval";
    static final String DUE_AFTER = "dueAfter";
    static final String ON_EXPIRY = "onExpiry";

    static final int MAX_STEPS = 5;
    static final Duration MIN_DUE = Duration.ofMinutes(1);
    static final Duration MAX_DUE = Duration.ofDays(90);

    private static final Pattern PERMISSION = Pattern.compile("^[A-Z][A-Z0-9_]{0,59}$");

    private ApprovalPolicyValues() {
    }

    /** The policy as a setting value, every property written. */
    static Map<String, Object> toValue(ApprovalPolicy policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put(REQUIRED, policy.required());
        value.put(STEPS, String.join(",", policy.decidePermissions()));
        value.put(ORDERED, policy.ordered());
        value.put(SELF_APPROVAL, policy.allowSelfApproval());
        if (policy.due() != null) {
            value.put(DUE_AFTER, policy.due().toString());
        }
        value.put(ON_EXPIRY, policy.onExpiry().name());
        return value;
    }

    /**
     * The code default with a setting value laid over it. The default's
     * mandatory flag stays, so a mandatory flow is required whatever the value says.
     *
     * @throws IllegalArgumentException when a property has the wrong shape,
     *                                  naming it
     */
    static ApprovalPolicy overlay(ApprovalPolicy base, Map<String, ?> value) {
        for (String name : value.keySet()) {
            if (!List.of(REQUIRED, STEPS, ORDERED, SELF_APPROVAL, DUE_AFTER, ON_EXPIRY).contains(name)) {
                throw new IllegalArgumentException("has no property '" + name + "'");
            }
        }
        return new ApprovalPolicy(base.subjectType(),
                flag(value, REQUIRED, base.required()),
                base.mandatory(),
                value.get(STEPS) == null ? base.decidePermissions() : parseSteps(text(value, STEPS)),
                flag(value, ORDERED, base.ordered()),
                flag(value, SELF_APPROVAL, base.allowSelfApproval()),
                value.get(DUE_AFTER) == null ? base.due() : parseDue(text(value, DUE_AFTER)),
                value.get(ON_EXPIRY) == null ? base.onExpiry() : parseExpiry(text(value, ON_EXPIRY)));
    }

    /** One to five permission codes, comma separated, none blank. */
    static List<String> parseSteps(String text) {
        List<String> steps = new ArrayList<>();
        for (String part : text.split(",", -1)) {
            String code = part.strip();
            if (!PERMISSION.matcher(code).matches()) {
                throw new IllegalArgumentException("property '" + STEPS + "' lists permission codes, comma"
                        + " separated; '" + code + "' is not one");
            }
            steps.add(code);
        }
        if (steps.isEmpty() || steps.size() > MAX_STEPS) {
            throw new IllegalArgumentException("property '" + STEPS + "' has one to " + MAX_STEPS
                    + " approval steps");
        }
        return List.copyOf(steps);
    }

    static Duration parseDue(String text) {
        Duration due;
        try {
            due = Duration.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("property '" + DUE_AFTER
                    + "' must be an ISO-8601 duration such as PT72H");
        }
        if (due.compareTo(MIN_DUE) < 0 || due.compareTo(MAX_DUE) > 0) {
            throw new IllegalArgumentException("property '" + DUE_AFTER + "' is one minute to 90 days");
        }
        return due;
    }

    private static Expiry parseExpiry(String text) {
        try {
            return Expiry.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("property '" + ON_EXPIRY + "' is EXPIRE or AUTO_APPROVE");
        }
    }

    private static boolean flag(Map<String, ?> value, String name, boolean fallback) {
        Object raw = value.get(name);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        throw new IllegalArgumentException("property '" + name + "' must be true or false");
    }

    private static String text(Map<String, ?> value, String name) {
        if (value.get(name) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("property '" + name + "' must be text");
    }
}
