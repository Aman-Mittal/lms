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

package com.lms.finance.command;

import java.util.UUID;

import com.lms.identity.access.AccessSubject;

/** Who is acting, for the money records that name a person. */
final class Actors {

    private Actors() {
    }

    /** The signed-in user, or null for a platform actor or a test principal. */
    static UUID currentUserId() {
        String userId = AccessSubject.current().userId();
        try {
            return userId == null ? null : UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
