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

import java.time.LocalDate;

import com.lms.approvals.api.ApprovalOutcome;
import com.lms.approvals.api.ApprovalOutcomeHandler;
import com.lms.approvals.api.ApprovalSubjectTypes;
import org.springframework.stereotype.Component;

/**
 * An approved extension moves the contract's end date later, audited with
 * before and after. Re-validated at decision: a contract since closed by a
 * successor, or whose end date has already moved to or past the requested
 * one, refuses it and the request stays open. Nothing is done while a request
 * is open, so a rejection or expiry needs no undoing.
 */
@Component
public class ContractExtensionApproval implements ApprovalOutcomeHandler {

    private final ClientContractLifecycleService lifecycle;

    public ContractExtensionApproval(ClientContractLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    public String subjectType() {
        return ApprovalSubjectTypes.CONTRACT_EXTENSION;
    }

    @Override
    public void onApproved(ApprovalOutcome outcome) {
        lifecycle.applyExtension(outcome.subjectId(), LocalDate.parse(outcome.text("newValidTill")),
                outcome.text("reason"));
    }
}
