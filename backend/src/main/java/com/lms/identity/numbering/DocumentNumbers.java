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
package com.lms.identity.numbering;

import java.util.Optional;
import java.util.UUID;

/**
 * The tenant's document numbering, for a module creating a document.
 *
 * <p>Both calls join the caller's transaction and refuse to run without one:
 * a number is drawn under a row lock held until the document commits, so two
 * concurrent documents never get the same number, and a document that rolls
 * back takes its number back with it. Neither opens a connection of its own.
 * Both read the tenant from the current scope, and neither checks a
 * permission -- the caller has already been authorised to create or cancel
 * the document.
 */
public interface DocumentNumbers {

    /**
     * The next number for a document of {@code type} raised at
     * {@code orgUnitId}.
     *
     * <p>The active series whose unit is the deepest one at or above
     * {@code orgUnitId} answers; failing that, the tenant-wide series of the
     * type. A series that reuses released numbers hands out the lowest
     * released number of the current financial year before advancing its
     * counter.
     *
     * @param orgUnitId the unit the document belongs to; null for the
     *                  tenant-wide series only
     * @return the number, or empty when no active series covers the unit --
     *         the caller then numbers the document as it did before series
     *         existed
     */
    Optional<String> next(DocumentType type, UUID orgUnitId);

    /**
     * Gives back the number of a cancelled document.
     *
     * <p>Always recorded, with the reason, so the gap in the sequence is
     * explained; reused later only by a series configured to reuse. Safe to
     * call twice for the same cancellation.
     *
     * @return true when the release was recorded now; false when the number
     *         belongs to no series of the type, was never issued, or is
     *         already released
     */
    boolean release(DocumentType type, String number, String reason);
}
