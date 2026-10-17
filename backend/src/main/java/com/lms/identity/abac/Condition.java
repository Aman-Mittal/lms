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
package com.lms.identity.abac;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * A policy condition: a small boolean expression over attributes.
 *
 * <p>Stored as JSON and parsed into this tree once, when a policy is written
 * and again when it is loaded. The language is deliberately tiny -- three
 * connectives and a fixed set of comparisons -- because it is administered by
 * people, not programmers, and every construct added is one more way for a
 * tenant to write a rule that does not mean what they think. There is no
 * expression evaluation, no scripting and no reflection: a condition can only
 * compare attributes the platform chose to expose.
 *
 * <pre>
 * {"allOf": [c, ...]}   {"anyOf": [c, ...]}   {"not": c}
 * {"attr": "resource.amount", "op": "gt", "ref": "subject.approvalLimit"}
 * {"attr": "subject.region",  "op": "eq", "value": "WEST"}
 * {"attr": "subject.partnerId", "op": "present"}
 * </pre>
 *
 * <p><b>Absent attributes.</b> A comparison with a missing operand is false --
 * neither "greater" nor "not equal". That single rule is what makes a DENY
 * policy safe to seed: a user with no approval limit is never "over" it.
 * {@code present} and {@code absent} exist for when absence is the point.
 */
public sealed interface Condition {

    /** Whether the condition holds, reading attributes by dotted name. */
    boolean holds(Function<String, Object> attributes);

    /** Subject attributes a condition may name. Others are rejected as typos. */
    Set<String> SUBJECT_ATTRIBUTES = Set.of("userId", "permissions", "orgPath", "region",
            "freightModes", "partnerId", "approvalLimit");

    /** Environment attributes, evaluated in UTC. */
    Set<String> ENV_ATTRIBUTES = Set.of("hour", "dayOfWeek");

    /** Nesting deeper than this is refused; a real rule never needs it. */
    int MAX_DEPTH = 8;

    Pattern RESOURCE_ATTRIBUTE = Pattern.compile("^resource\\.[A-Za-z][A-Za-z0-9]{0,62}$");

    /**
     * Parses a condition from its JSON form, already read into maps and lists.
     *
     * @throws IllegalArgumentException naming what is wrong, so an
     *         administrator writing a policy is told at write time rather than
     *         finding out when it silently never matches
     */
    static Condition parse(Object node) {
        return parse(node, 0);
    }

    private static Condition parse(Object node, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Condition nests deeper than " + MAX_DEPTH + " levels");
        }
        if (!(node instanceof Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalArgumentException("A condition must be a non-empty JSON object");
        }
        if (map.containsKey("allOf") || map.containsKey("anyOf")) {
            boolean all = map.containsKey("allOf");
            if (map.size() != 1) {
                throw new IllegalArgumentException("allOf/anyOf must be the only key in its object");
            }
            Object items = map.get(all ? "allOf" : "anyOf");
            if (!(items instanceof List<?> list) || list.isEmpty()) {
                throw new IllegalArgumentException((all ? "allOf" : "anyOf")
                        + " needs a non-empty list of conditions");
            }
            List<Condition> parts = new ArrayList<>();
            for (Object item : list) {
                parts.add(parse(item, depth + 1));
            }
            return all ? new AllOf(parts) : new AnyOf(parts);
        }
        if (map.containsKey("not")) {
            if (map.size() != 1) {
                throw new IllegalArgumentException("not must be the only key in its object");
            }
            return new Not(parse(map.get("not"), depth + 1));
        }
        return Compare.parse(map);
    }

    /**
     * A condition with a fixed outcome. Never parsed from JSON; used to make an
     * unreadable stored policy fail closed.
     */
    record Fixed(boolean result) implements Condition {
        @Override
        public boolean holds(Function<String, Object> attributes) {
            return result;
        }
    }

    record AllOf(List<Condition> parts) implements Condition {
        public AllOf {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean holds(Function<String, Object> attributes) {
            return parts.stream().allMatch(part -> part.holds(attributes));
        }
    }

    record AnyOf(List<Condition> parts) implements Condition {
        public AnyOf {
            parts = List.copyOf(parts);
        }

        @Override
        public boolean holds(Function<String, Object> attributes) {
            return parts.stream().anyMatch(part -> part.holds(attributes));
        }
    }

    record Not(Condition inner) implements Condition {
        @Override
        public boolean holds(Function<String, Object> attributes) {
            return !inner.holds(attributes);
        }
    }

    enum Op {
        EQ("eq"), NE("ne"), GT("gt"), GTE("gte"), LT("lt"), LTE("lte"),
        IN("in"), CONTAINS("contains"), STARTS_WITH("startsWith"),
        PRESENT("present"), ABSENT("absent");

        private final String json;

        Op(String json) {
            this.json = json;
        }

        static Op of(Object raw) {
            for (Op op : values()) {
                if (op.json.equals(raw)) {
                    return op;
                }
            }
            throw new IllegalArgumentException("Unknown operator '" + raw + "'; expected one of "
                    + "eq, ne, gt, gte, lt, lte, in, contains, startsWith, present, absent");
        }

        boolean unary() {
            return this == PRESENT || this == ABSENT;
        }
    }

    /**
     * One comparison. The right-hand side is either a literal {@code value}
     * or another attribute named by {@code ref} -- never both.
     */
    record Compare(String attr, Op op, Object value, String ref) implements Condition {

        static Compare parse(Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (!Set.of("attr", "op", "value", "ref").contains(key)) {
                    throw new IllegalArgumentException("Unknown key '" + key + "' in a comparison");
                }
            }
            String attr = attributeName(map.get("attr"), "attr");
            Op op = Op.of(map.get("op"));
            boolean hasValue = map.containsKey("value");
            boolean hasRef = map.containsKey("ref");

            if (op.unary()) {
                if (hasValue || hasRef) {
                    throw new IllegalArgumentException("'" + op.json + "' takes no value or ref");
                }
                return new Compare(attr, op, null, null);
            }
            if (hasValue == hasRef) {
                throw new IllegalArgumentException(
                        "Comparison on " + attr + " needs exactly one of 'value' or 'ref'");
            }
            Object value = hasValue ? map.get("value") : null;
            if (hasValue && value == null) {
                throw new IllegalArgumentException(
                        "Comparison on " + attr + " has a null value; use 'absent' instead");
            }
            if (op == Op.IN && hasValue && !(value instanceof List<?>)) {
                throw new IllegalArgumentException("'in' needs a list value");
            }
            if (hasValue && (value instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("A comparison value cannot be an object");
            }
            String ref = hasRef ? attributeName(map.get("ref"), "ref") : null;
            return new Compare(attr, op, value, ref);
        }

        private static String attributeName(Object raw, String key) {
            if (!(raw instanceof String name)) {
                throw new IllegalArgumentException("'" + key + "' must name an attribute");
            }
            if (name.startsWith("subject.")
                    && SUBJECT_ATTRIBUTES.contains(name.substring("subject.".length()))) {
                return name;
            }
            if (name.startsWith("env.") && ENV_ATTRIBUTES.contains(name.substring("env.".length()))) {
                return name;
            }
            if (RESOURCE_ATTRIBUTE.matcher(name).matches()) {
                return name;
            }
            throw new IllegalArgumentException("Unknown attribute '" + name + "'; subject attributes are "
                    + SUBJECT_ATTRIBUTES.stream().sorted().toList() + ", env attributes are "
                    + ENV_ATTRIBUTES.stream().sorted().toList() + ", and resource.<name> is open");
        }

        @Override
        public boolean holds(Function<String, Object> attributes) {
            Object left = attributes.apply(attr);
            if (op == Op.PRESENT) {
                return left != null;
            }
            if (op == Op.ABSENT) {
                return left == null;
            }
            Object right = ref != null ? attributes.apply(ref) : value;
            if (left == null || right == null) {
                return false;
            }
            return switch (op) {
                case EQ -> same(left, right);
                case NE -> !same(left, right);
                case GT, GTE, LT, LTE -> ordered(left, right, op);
                case IN -> right instanceof Collection<?> options
                        && options.stream().anyMatch(option -> option != null && same(left, option));
                case CONTAINS -> left instanceof Collection<?> held
                        && held.stream().anyMatch(item -> item != null && same(item, right));
                case STARTS_WITH -> left.toString().startsWith(right.toString());
                case PRESENT, ABSENT -> throw new IllegalStateException("handled above");
            };
        }

        /**
         * An ordered comparison, false whenever either side is not a number.
         *
         * <p>False rather than an exception: a policy that compares a number
         * with a word is a mistake in the policy, and it should not fail the
         * request it was consulted about -- it should simply never match.
         */
        private static boolean ordered(Object left, Object right, Op op) {
            Integer c = compareOrNull(left, right);
            if (c == null) {
                return false;
            }
            return switch (op) {
                case GT -> c > 0;
                case GTE -> c >= 0;
                case LT -> c < 0;
                case LTE -> c <= 0;
                default -> throw new IllegalStateException("not an ordering: " + op);
            };
        }

        private static Integer compareOrNull(Object left, Object right) {
            BigDecimal l = decimal(left);
            BigDecimal r = decimal(right);
            if (l == null || r == null) {
                return null;
            }
            return l.compareTo(r);
        }

        private static boolean same(Object left, Object right) {
            BigDecimal l = decimal(left);
            BigDecimal r = decimal(right);
            if (l != null && r != null) {
                return l.compareTo(r) == 0;
            }
            if (left instanceof Boolean || right instanceof Boolean) {
                return String.valueOf(left).equalsIgnoreCase(String.valueOf(right));
            }
            return left.toString().equals(right.toString());
        }

        private static BigDecimal decimal(Object raw) {
            if (raw instanceof BigDecimal d) {
                return d;
            }
            if (raw instanceof Number n) {
                return new BigDecimal(n.toString());
            }
            return null;
        }
    }
}
