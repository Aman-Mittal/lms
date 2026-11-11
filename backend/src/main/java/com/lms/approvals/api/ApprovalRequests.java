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

import java.util.Map;
import java.util.UUID;

import com.lms.settings.api.SettingContext;

/**
 * Raising an approval request, from the module that owns its subject.
 *
 * <p>Both methods join the caller's transaction ({@code MANDATORY}): the
 * request is raised in the same unit of work as the check that it was needed,
 * so a failure afterwards leaves no orphan request behind.
 */
public interface ApprovalRequests {

    /**
     * Whether the policy for this subject type, resolved for the subject's
     * context (its unit, customer and freight mode, where the flow knows
     * them), asks for approval.
     */
    boolean isRequired(String subjectType, SettingContext context);

    /**
     * Whether the organisation-wide policy asks for approval. A flow that
     * knows its subject's context passes it with the overload above, so a
     * unit's or customer's profile applies.
     */
    default boolean isRequired(String subjectType) {
        return isRequired(subjectType, SettingContext.tenant());
    }

    /**
     * Raises a request, recording the policy now in force for {@code context} on it.
     *
     * @param payload what approving will do, frozen; handed back to the
     *                handler unchanged. Values are written as JSON strings,
     *                numbers, booleans or null.
     * @param summary one human line for the inbox, at most 500 characters
     * @throws com.lms.shared.error.BusinessRuleViolationException
     *         {@code approval-already-open} when the subject already has an
     *         open request for this action
     */
    ApprovalTicket request(ApprovalSubject subject, SettingContext context, String action,
                           Map<String, ?> payload, String summary);

    /**
     * As above under the organisation-wide policy. Pass the same context here
     * as to {@link #isRequired(String, SettingContext)} so the request is raised
     * under the policy that said it was needed.
     */
    default ApprovalTicket request(ApprovalSubject subject, String action, Map<String, ?> payload,
                                   String summary) {
        return request(subject, SettingContext.tenant(), action, payload, summary);
    }

    /** The open request for a subject and action, if there is one. */
    java.util.Optional<ApprovalTicket> openRequest(String subjectType, UUID subjectId, String action);
}
