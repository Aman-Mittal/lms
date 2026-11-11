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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An approval request as the inbox shows it.
 *
 * @param pendingPermissions the permissions an approval may still be given
 *                           under; empty once the request is closed
 * @param approvalsRequired  how many distinct approvers the request's policy needs
 * @param mineToDecide       whether the caller could give its next approval now
 */
public record ApprovalView(
        UUID id,
        String subjectType,
        UUID subjectId,
        String action,
        String summary,
        Map<String, Object> payload,
        String status,
        UUID orgUnitId,
        UUID requestedBy,
        Instant requestedAt,
        Instant dueAt,
        List<String> pendingPermissions,
        int approvalsGiven,
        int approvalsRequired,
        UUID decidedBy,
        Instant decidedAt,
        String reason,
        boolean mineToDecide,
        boolean requestedByMe,
        long version) {
}
