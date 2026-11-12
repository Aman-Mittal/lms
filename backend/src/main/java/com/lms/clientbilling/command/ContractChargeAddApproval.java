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

import java.math.BigDecimal;
import java.time.LocalDate;

import com.lms.approvals.api.ApprovalOutcome;
import com.lms.approvals.api.ApprovalOutcomeHandler;
import com.lms.approvals.api.ApprovalSubjectTypes;
import org.springframework.stereotype.Component;

/**
 * An approved charge addition publishes a successor version carrying the
 * charge from the requested day. Re-validated at decision: a contract no
 * longer the version in force, a day that has passed or a charge priced since
 * refuses it and the request stays open.
 */
@Component
public class ContractChargeAddApproval implements ApprovalOutcomeHandler {

    private final ClientContractLifecycleService lifecycle;

    public ContractChargeAddApproval(ClientContractLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectTypes.CONTRACT_CHARGE_ADD;
    }

    @Override
    public void onApproved(ApprovalOutcome outcome) {
        lifecycle.applyChargeAddition(outcome.subjectId(), outcome.text("serviceCode"), outcome.text("unit"),
                new BigDecimal(outcome.text("rate")), LocalDate.parse(outcome.text("effectiveFrom")),
                outcome.text("reason"));
    }
}
