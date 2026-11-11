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
 * The policy in force for a subject type, for the tenant in scope.
 *
 * <p>The one place any flow learns its policy, including whether it needs
 * approval at all: no flow reads a default or a setting of its own. Today's
 * implementation is the code default from {@code ApprovalPolicyDefaults}
 * overlaid with the tenant's row in {@code approval_policy_override}.
 *
 * <p><b>Where profile settings plug in.</b> When the effective-dated settings
 * module lands, it provides this interface (or the default implementation
 * delegates to it) and the override table is migrated into settings. No flow
 * changes, because no flow reads anything but this. A mandatory flag in the
 * code default is not overridable by any source.
 */
public interface ApprovalPolicyResolver {

    /**
     * @throws IllegalArgumentException for a subject type that has no default
     *                                  policy: a programming error, not input
     */
    ApprovalPolicy resolve(String subjectType);
}
