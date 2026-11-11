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
package com.lms.approvals.api;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * How requests of one subject type are decided.
 *
 * @param subjectType       the flow, one of {@link ApprovalSubjectTypes}
 * @param required          whether the flow asks for approval at all; false
 *                          means the owning module acts at once, as it did
 *                          before approvals existed
 * @param mandatory         whether {@code required} is fixed at true whatever a
 *                          tenant sets -- a flow that may only ever happen
 *                          through an approval (a proof's reversal)
 * @param decidePermissions one permission per approval step: a request needs
 *                          as many distinct approvers as there are entries,
 *                          each holding the permission of the step they fill
 * @param ordered           true: steps are filled in the order listed; false:
 *                          an approver fills any step whose permission they hold
 * @param allowSelfApproval whether the requester may fill a step (or reject)
 *                          their own request
 * @param due               how long a request may stay open; null for no limit
 * @param onExpiry          what the expiry sweep does with a request past due
 */
public record ApprovalPolicy(String subjectType, boolean required, boolean mandatory,
                             List<String> decidePermissions, boolean ordered,
                             boolean allowSelfApproval, Duration due, Expiry onExpiry) {

    /** What happens to a request that is still open when it falls due. */
    public enum Expiry {
        /** It closes, EXPIRED, and nothing is done. */
        EXPIRE,
        /** It is approved by the platform, as if every step had been given. */
        AUTO_APPROVE
    }

    public ApprovalPolicy {
        Objects.requireNonNull(subjectType, "subjectType");
        decidePermissions = List.copyOf(decidePermissions);
        if (decidePermissions.isEmpty()) {
            throw new IllegalArgumentException("A policy needs at least one approval step");
        }
        if (due != null && (due.isNegative() || due.isZero())) {
            throw new IllegalArgumentException("A due duration must be positive");
        }
        onExpiry = onExpiry == null ? Expiry.EXPIRE : onExpiry;
        required = required || mandatory;
    }

    /** How many distinct approvers a request needs. */
    public int approvers() {
        return decidePermissions.size();
    }
}
