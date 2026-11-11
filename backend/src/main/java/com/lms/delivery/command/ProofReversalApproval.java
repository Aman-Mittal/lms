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
package com.lms.delivery.command;

import java.util.UUID;

import com.lms.approvals.api.ApprovalOutcome;
import com.lms.approvals.api.ApprovalOutcomeHandler;
import com.lms.approvals.api.ApprovalSubjectTypes;
import org.springframework.stereotype.Component;

/**
 * An approved reversal reverses the proof, in the deciding transaction --
 * unless billing has used it since the request was raised, in which case the
 * decision is refused with {@code pod-already-billed} and the request stays
 * open (to be rejected, cancelled, or left to expire).
 */
@Component
public class ProofReversalApproval implements ApprovalOutcomeHandler {

    private final ProofReversalService reversals;

    public ProofReversalApproval(ProofReversalService reversals) {
        this.reversals = reversals;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectTypes.POD_REVERSAL;
    }

    @Override
    public void onApproved(ApprovalOutcome outcome) {
        String proofId = outcome.text("proofId");
        reversals.applyApproved(outcome.subjectId(), proofId == null ? null : UUID.fromString(proofId),
                outcome.text("reason"), outcome.requestId());
    }
}
