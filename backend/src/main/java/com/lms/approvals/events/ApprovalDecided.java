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
package com.lms.approvals.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An approval request closed: approved (by people or by its policy at
 * expiry), rejected, cancelled by its requester, or expired.
 *
 * <p>Published once per request, when it closes. An approval step that does
 * not close the request (the first of two approvers) is not announced.
 *
 * @param status    APPROVED, REJECTED, CANCELLED or EXPIRED
 * @param decidedBy null when the platform closed it
 * @param reason    a rejection's reason, a cancellation's or expiry's note; may be null
 */
public record ApprovalDecided(
        UUID tenantId,
        UUID requestId,
        String subjectType,
        UUID subjectId,
        String action,
        String status,
        UUID decidedBy,
        String reason,
        Instant occurredAt) {
}
