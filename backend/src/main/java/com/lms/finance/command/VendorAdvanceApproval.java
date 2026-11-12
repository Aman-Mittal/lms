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
 * An approved advance may be paid. Re-validated at decision (it must still be
 * waiting), so an advance cancelled since refuses the decision and the
 * request stays open.
 */
@Component
public class VendorAdvanceApproval implements ApprovalOutcomeHandler {

    private final VendorAdvanceService advances;

    public VendorAdvanceApproval(VendorAdvanceService advances) {
        this.advances = advances;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectTypes.VENDOR_ADVANCE;
    }

    @Override
    public void onApproved(ApprovalOutcome outcome) {
        advances.applyApproved(outcome.subjectId(), outcome.requestId());
    }

    @Override
    public void onClosedWithoutApproval(ApprovalOutcome outcome, String status) {
        advances.closedWithoutApproval(outcome.subjectId(), status);
    }
}
