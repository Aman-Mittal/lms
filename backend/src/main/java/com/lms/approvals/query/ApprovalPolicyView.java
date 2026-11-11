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
package com.lms.approvals.query;

import java.util.List;

import com.lms.approvals.api.ApprovalPolicy;

/**
 * A policy as callers see it.
 *
 * @param dueMinutes null when requests never fall due
 */
public record ApprovalPolicyView(String subjectType, boolean required, boolean mandatory,
                                 List<String> decidePermissions, boolean ordered, boolean allowSelfApproval,
                                 Long dueMinutes, String onExpiry) {

    public static ApprovalPolicyView of(ApprovalPolicy p) {
        return new ApprovalPolicyView(p.subjectType(), p.required(), p.mandatory(), p.decidePermissions(),
                p.ordered(), p.allowSelfApproval(), p.due() == null ? null : p.due().toMinutes(),
                p.onExpiry().name());
    }
}
