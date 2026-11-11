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
package com.lms.approvals.command.domain;

/**
 * Where an approval request stands.
 *
 * <pre>
 *   REQUESTED -> APPROVED    every step given (or the policy approves at expiry)
 *             -> REJECTED    a decider refused it, with a reason
 *             -> CANCELLED   its requester withdrew it
 *             -> EXPIRED     it fell due with steps outstanding
 * </pre>
 *
 * Every state but REQUESTED is final.
 */
public enum ApprovalStatus {
    REQUESTED, APPROVED, REJECTED, CANCELLED, EXPIRED;

    public boolean open() {
        return this == REQUESTED;
    }
}
