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
package com.lms.shared.web;

/**
 * The version a conditional edit was read at, from its {@code If-Match}
 * header, and the {@code ETag} a read hands out for it.
 *
 * <p>The header is mandatory on the edits that take it: an unconditional edit
 * is the lost update the header exists to prevent, and {@code *} is not
 * accepted for the same reason. Quoted, bare and weak forms of the same
 * version are all read as that version. A missing or unreadable header is a
 * 400, not a 409: the caller has not read the resource, so it has nothing
 * stale to be told about.
 */
public final class IfMatch {

    private IfMatch() {
    }

    /** The {@code ETag} for a version; a resource never saved reads as version 0. */
    public static String etag(Long version) {
        return "\"" + (version == null ? 0 : version) + "\"";
    }

    /**
     * @param what the resource, for the message: "the load's version"
     * @throws IllegalArgumentException when the header is missing or not a version
     */
    public static long version(String ifMatch, String what) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("An If-Match header carrying " + what + " is required");
        }
        String raw = ifMatch.strip();
        if (raw.startsWith("W/")) {
            raw = raw.substring(2);
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match must be " + what + " as returned in its ETag", e);
        }
    }
}
