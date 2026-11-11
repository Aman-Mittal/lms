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

import com.lms.settings.api.SettingContext;

/**
 * The policy in force for a subject type, for the tenant in scope and the
 * context of the subject.
 *
 * <p>The one place any flow learns its policy, including whether it needs
 * approval at all: no flow reads a default or a setting of its own. The
 * implementation resolves the type's profile setting ({@code approvals.policy.*})
 * for the context, so a branch's, a customer's or a combination's profile may
 * differ from the organisation default; with none, the code default from
 * {@code ApprovalPolicyDefaults} answers. A mandatory flag in the code default
 * is not overridable by any profile.
 */
public interface ApprovalPolicyResolver {

    /**
     * @throws IllegalArgumentException for a subject type that has no default
     *                                  policy: a programming error, not input
     */
    ApprovalPolicy resolve(String subjectType, SettingContext context);

    /** The organisation-wide policy: no unit, customer or freight mode. */
    default ApprovalPolicy resolve(String subjectType) {
        return resolve(subjectType, SettingContext.tenant());
    }
}
