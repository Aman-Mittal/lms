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

package com.lms.order.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An enquiry as the desk shows it; {@code version} is the ETag an edit sends
 * back in {@code If-Match}. {@code lines} is filled for one enquiry and empty
 * in a listing, which carries the counts instead.
 */
public record EnquiryView(
        UUID id,
        String enquiryNo,
        String status,
        UUID orgUnitId,
        UUID customerPartnerId,
        String customerName,
        String reference,
        String notes,
        Instant deadlineAt,
        String cancelReason,
        Instant cancelledAt,
        UUID orderId,
        String orderNo,
        Instant convertedAt,
        int lineCount,
        int wonLineCount,
        Long version,
        Instant createdAt,
        Instant updatedAt,
        List<EnquiryLineView> lines) {

    public EnquiryView {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
