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
package com.lms.statutory.api;

import java.util.Locale;
import java.util.Optional;

/**
 * Everything an adapter can be asked to do to an e-way bill. A name outside
 * this list is not ignored: {@link #parse} answers empty and the caller refuses
 * the request with {@code eway-action-unknown}.
 */
public enum EwayBillAction {
    /** Raise a bill for a document, or fetch the one the system already holds for it. */
    GENERATE,
    /** Read the bill the system holds for a document, without raising one. */
    FETCH,
    /** Update the vehicle on Part-B. */
    PART_B,
    /** Extend the validity. */
    EXTEND,
    /** Cancel the bill. */
    CANCEL,
    /** Change the vehicle after Part-B (a breakdown, a transhipment). */
    CHANGE_VEHICLE;

    /** The action of that name (any case), or empty for an unknown or blank one. */
    public static Optional<EwayBillAction> parse(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
