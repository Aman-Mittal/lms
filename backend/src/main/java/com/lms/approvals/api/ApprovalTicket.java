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
package com.lms.approvals.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A request as its raiser needs to answer for it: enough to say "awaiting
 * approval" and where to look. A value, not a view of the request.
 *
 * @param dueAt when the request falls due; null when it never does
 */
public record ApprovalTicket(UUID id, String subjectType, UUID subjectId, String action,
                             String status, Instant requestedAt, Instant dueAt, long version) {
}
