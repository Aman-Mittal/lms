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
import java.util.UUID;

/**
 * One entry of a request's decision trail.
 *
 * @param step       the approval step filled, 1-based; null for anything else
 * @param decision   APPROVED, AUTO_APPROVED, REJECTED, CANCELLED or EXPIRED
 * @param permission the permission an approval step was given under
 * @param decidedBy  null when the platform decided
 */
public record ApprovalDecisionView(UUID id, Integer step, String decision, String permission, UUID decidedBy,
                                   Instant decidedAt, String note) {
}
