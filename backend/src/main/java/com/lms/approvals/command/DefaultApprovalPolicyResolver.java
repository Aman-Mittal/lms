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
package com.lms.approvals.command;

import java.util.Map;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicyResolver;
import com.lms.settings.api.SettingContext;
import com.lms.settings.api.Settings;
import org.springframework.stereotype.Service;

/**
 * The type's profile setting ({@code approvals.policy.*}) resolved for the
 * subject's context, laid over the code default. A mandatory flow stays
 * required whatever a profile says; a stored value that no longer reads as a
 * policy is skipped for the code default, as the settings module skips a
 * value its definition refuses.
 *
 * <p>One settings resolution (one statement) per call, in the caller's
 * transaction.
 */
@Service
public class DefaultApprovalPolicyResolver implements ApprovalPolicyResolver {

    private final Settings settings;

    public DefaultApprovalPolicyResolver(Settings settings) {
        this.settings = settings;
    }

    @Override
    public ApprovalPolicy resolve(String subjectType, SettingContext context) {
        ApprovalPolicy base = ApprovalPolicyDefaults.of(subjectType)
                .orElseThrow(() -> new IllegalArgumentException("No approval policy for " + subjectType));
        Map<String, Object> value = settings.effective(ApprovalSettingDefinitions.keyOf(subjectType), context);
        try {
            return ApprovalPolicyValues.overlay(base, value);
        } catch (IllegalArgumentException e) {
            return base;
        }
    }
}
