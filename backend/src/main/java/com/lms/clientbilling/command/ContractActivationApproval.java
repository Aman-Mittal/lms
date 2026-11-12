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
package com.lms.clientbilling.command;

import com.lms.approvals.api.ApprovalOutcome;
import com.lms.approvals.api.ApprovalOutcomeHandler;
import com.lms.approvals.api.ApprovalSubjectTypes;
import org.springframework.stereotype.Component;

/**
 * An approved verification puts the draft into force through the one
 * activation path, in the deciding transaction.
 *
 * <p>Re-validated then: a contract no longer awaiting verification, one that
 * no longer passes the checklist or one that can no longer succeed the version
 * in force refuses the decision with its own code and the request stays open.
 * A rejected, withdrawn or expired request sends the contract back to DRAFT
 * with the reason, so its maker can correct it and ask again.
 */
@Component
public class ContractActivationApproval implements ApprovalOutcomeHandler {

    private final ClientContractLifecycleService lifecycle;

    public ContractActivationApproval(ClientContractLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectTypes.CONTRACT_ACTIVATION;
    }

    @Override
    public void onApproved(ApprovalOutcome outcome) {
        lifecycle.applyActivation(outcome.subjectId(), outcome.reason());
    }

    @Override
    public void onClosedWithoutApproval(ApprovalOutcome outcome, String status) {
        lifecycle.applyVerificationClosed(outcome.subjectId(), status, outcome.reason());
    }
}
