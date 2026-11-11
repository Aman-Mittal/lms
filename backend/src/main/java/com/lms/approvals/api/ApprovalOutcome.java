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

import java.util.Map;
import java.util.UUID;

/**
 * A decided request, as its owning module's handler receives it.
 *
 * @param payload     the payload frozen when the request was raised
 * @param requestedBy who asked; null when it was not a person
 * @param decidedBy   who gave the deciding approval or the rejection; null
 *                    when the platform decided (an expiry)
 * @param reason      the rejection's reason, or the approver's note; may be null
 *                    for an approval
 */
public record ApprovalOutcome(UUID requestId, String subjectType, UUID subjectId, String action,
                              Map<String, Object> payload, String summary, UUID requestedBy,
                              UUID decidedBy, String reason) {

    public ApprovalOutcome {
        payload = payload == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(payload));
    }

    /** A payload value as text, or null when it is absent or JSON null. */
    public String text(String key) {
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }
}
