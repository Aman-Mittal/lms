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
package com.lms.shared.audit;

import java.util.UUID;

/**
 * Who is reading a history, as far as the history needs to know.
 *
 * <p>The shared kernel cannot read the access token itself, so each controller
 * says who is asking. A customer- or vendor-portal user is told that a step
 * was theirs, someone else's or the platform's, and never who on the tenant's
 * staff took it.
 *
 * @param userId    the caller, or null when the caller is not a user
 * @param seesNames false for a portal user: other actors' names and ids are withheld
 */
public record HistoryViewer(UUID userId, boolean seesNames) {

    /** The tenant's own staff, or an in-process caller: every actor is shown. */
    public static HistoryViewer staff() {
        return new HistoryViewer(null, true);
    }

    /**
     * From the caller's user id as the token carries it and whether the caller
     * acts for a partner. An id that is not a UUID names no user.
     */
    public static HistoryViewer of(String userId, boolean portal) {
        UUID id;
        try {
            id = userId == null ? null : UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            id = null;
        }
        return new HistoryViewer(id, !portal);
    }
}
