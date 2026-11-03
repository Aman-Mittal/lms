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
package com.lms.delivery.command.domain;

/** The claim lifecycle. */
public enum ClaimStatus {
    OPEN,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    SETTLED;

    /** Whether a claim in this state may move to {@code target}. */
    public boolean canMoveTo(ClaimStatus target) {
        return switch (this) {
            case OPEN -> target == UNDER_REVIEW;
            case UNDER_REVIEW -> target == APPROVED || target == REJECTED;
            case APPROVED -> target == SETTLED;
            case REJECTED, SETTLED -> false;
        };
    }
}
