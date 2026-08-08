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
 */package com.lms.masterdata.query;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A trading partner as an operations screen shows it.
 *
 * <p>Carries {@code expiredDocumentCount} rather than leaving it to be worked
 * out per row. Whether a partner's paperwork is in order is the first thing
 * anybody looks at, and computing it client-side would mean a request per row.
 */
public record PartnerView(
        UUID id,
        String code,
        String legalName,
        String partnerType,
        String status,
        String taxId,
        BigDecimal creditLimit,
        String paymentTerms,
        BigDecimal onTimePct,
        BigDecimal claimsRatio,
        Instant scorecardAt,
        int expiredDocumentCount,
        Instant createdAt) {
}
