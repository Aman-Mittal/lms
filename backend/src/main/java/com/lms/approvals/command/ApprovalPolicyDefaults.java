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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.lms.approvals.api.ApprovalPolicy;
import com.lms.approvals.api.ApprovalPolicy.Expiry;
import com.lms.approvals.api.ApprovalSubjectTypes;

/**
 * The code default of every subject type's policy, in one place.
 *
 * <p>A flow never reads this directly: it asks
 * {@link com.lms.approvals.api.ApprovalPolicyResolver}, which starts here and
 * applies what the tenant has set. Adding a flow means a line here, a constant
 * in {@link ApprovalSubjectTypes} and a handler in the owning module.
 *
 * <ul>
 *   <li>{@code ORDER_CANCELLATION}: not required by default, so an order is
 *       cancelled at once exactly as before; a tenant switches it on. One
 *       holder of {@code ORDER_APPROVE}, not the requester, within 72 hours,
 *       after which the request expires and the order stays as it is.
 *   <li>{@code POD_REVERSAL}: mandatory -- a proof of delivery is reversed
 *       only through an audited approval, and no tenant setting turns that
 *       off. One holder of {@code POD_REVERSAL_APPROVE}, not the requester,
 *       within 72 hours, then it expires.
 *   <li>{@code CONTRACT_ACTIVATION}, {@code CONTRACT_EXTENSION} and
 *       {@code CONTRACT_CHARGE_ADD}: required, though a tenant may switch them
 *       off. One holder of {@code CLIENT_CONTRACT_APPROVE}, not the requester,
 *       within 14 days, after which the request expires and the contract stays
 *       as it is (a draft goes back to DRAFT).
 * </ul>
 */
public final class ApprovalPolicyDefaults {

    private static final Duration THREE_DAYS = Duration.ofHours(72);
    private static final Duration TWO_WEEKS = Duration.ofDays(14);

    private static final Map<String, ApprovalPolicy> DEFAULTS = new LinkedHashMap<>();

    static {
        add(new ApprovalPolicy(ApprovalSubjectTypes.ORDER_CANCELLATION, false, false,
                List.of("ORDER_APPROVE"), true, false, THREE_DAYS, Expiry.EXPIRE));
        add(new ApprovalPolicy(ApprovalSubjectTypes.POD_REVERSAL, true, true,
                List.of("POD_REVERSAL_APPROVE"), true, false, THREE_DAYS, Expiry.EXPIRE));
        for (String type : List.of(ApprovalSubjectTypes.CONTRACT_ACTIVATION, ApprovalSubjectTypes.CONTRACT_EXTENSION,
                ApprovalSubjectTypes.CONTRACT_CHARGE_ADD)) {
            add(new ApprovalPolicy(type, true, false, List.of("CLIENT_CONTRACT_APPROVE"), true, false, TWO_WEEKS,
                    Expiry.EXPIRE));
        }
    }

    private ApprovalPolicyDefaults() {
    }

    private static void add(ApprovalPolicy policy) {
        DEFAULTS.put(policy.subjectType(), policy);
    }

    public static Optional<ApprovalPolicy> of(String subjectType) {
        return Optional.ofNullable(DEFAULTS.get(subjectType));
    }

    /** Every registered subject type, in registration order. */
    public static List<String> subjectTypes() {
        return List.copyOf(DEFAULTS.keySet());
    }
}
