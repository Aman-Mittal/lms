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

/**
 * What an owning module does when one of its requests is decided.
 *
 * <p>One bean per subject type, in the owning module. Called in-process, in
 * the deciding transaction, after the request's own state has been checked
 * and before it is saved: whatever the handler writes commits with the
 * decision, or rolls back with it.
 *
 * <p><b>Re-validate, never trust the payload alone.</b> The subject may have
 * moved on since the request was raised. A handler that finds the action no
 * longer valid throws a {@link com.lms.shared.error.BusinessRuleViolationException}
 * with its own code. The decision then fails with that 409 and the request
 * <em>stays open</em> -- it is not marked superseded -- so the requester can
 * cancel it, a decider can reject it, or it expires; nothing is ever recorded
 * as approved that did not happen.
 *
 * <p>Handlers run as the platform, not as the decider: the decider's right is
 * the policy's permission, already checked, and the handler must not ask for
 * the owning module's own command permission on top.
 */
public interface ApprovalOutcomeHandler {

    /** The subject type handled, one of {@link ApprovalSubjectTypes}. */
    String subjectType();

    /** Applies the approved action. See the class comment on refusing. */
    void onApproved(ApprovalOutcome outcome);

    /**
     * The request closed without approval: rejected, cancelled by its
     * requester, or expired. Most flows need do nothing, because nothing was
     * done while the request was open.
     */
    default void onClosedWithoutApproval(ApprovalOutcome outcome, String status) {
    }
}
