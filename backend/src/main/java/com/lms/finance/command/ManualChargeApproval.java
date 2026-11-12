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

package com.lms.finance.command;

import com.lms.approvals.api.ApprovalOutcome;
import com.lms.approvals.api.ApprovalOutcomeHandler;
import com.lms.approvals.api.ApprovalSubjectTypes;
import org.springframework.stereotype.Component;

/**
 * An approved manual charge takes effect: its receivable is put on the
 * customer's next invoice or its payable on the trip's open bill. Re-validated
 * at decision (the charge must still be waiting), so a charge cancelled since
 * refuses the decision and the request stays open.
 */
@Component
public class ManualChargeApproval implements ApprovalOutcomeHandler {

    private final ManualChargeService charges;

    public ManualChargeApproval(ManualChargeService charges) {
        this.charges = charges;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectTypes.MANUAL_CHARGE;
    }

    @Override
    public void onApproved(ApprovalOutcome outcome) {
        charges.applyApproved(outcome.subjectId(), outcome.requestId());
    }

    @Override
    public void onClosedWithoutApproval(ApprovalOutcome outcome, String status) {
        charges.closedWithoutApproval(outcome.subjectId(), status);
    }
}
