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
/**
 * Approvals: one module for every flow that needs a person to agree first.
 *
 * <p>An owning module raises a request through {@code approvals::api}
 * ({@link com.lms.approvals.api.ApprovalRequests}) naming a subject type it
 * registered, the subject, the action and a frozen payload. The request is
 * decided over HTTP by someone holding the permission its policy names, and
 * approving it calls the owning module's
 * {@link com.lms.approvals.api.ApprovalOutcomeHandler} in the deciding
 * transaction, so the change and the decision commit together (ADR 0006: no
 * second connection, no {@code REQUIRES_NEW}). A handler that finds the action
 * no longer valid refuses with its own 409 code, the decision rolls back, and
 * the request stays open: nothing is ever marked approved that did not happen.
 *
 * <p>The policy of each subject type -- whether approval is required at all,
 * which permissions decide and in what order, whether a requester may decide
 * their own request, how long it may stay open and what expiry does -- has a
 * code default in {@code ApprovalPolicyDefaults}, registered as one profile
 * setting per type ({@code approvals.policy.*}), and is read only through
 * {@link com.lms.approvals.api.ApprovalPolicyResolver}, for the subject's
 * context (unit, customer, freight mode). The policy that applied is copied
 * onto every request when it is raised.
 *
 * <p>Depends on {@code identity::access}: who is calling and with what
 * permissions, and the hook through which the decision point learns a
 * request's organisational unit; and on {@code settings::api}, which holds
 * the policies' values. It depends on no module that uses it --
 * {@code order} and {@code delivery} depend on its {@code api}, and
 * {@code webhooks} on its {@code events}.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Approvals",
        allowedDependencies = {"identity::access", "settings::api"})
package com.lms.approvals;
