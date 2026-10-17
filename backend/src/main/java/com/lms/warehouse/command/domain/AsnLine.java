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
package com.lms.warehouse.command.domain;

import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One SKU and lot expected on an ASN, and -- once counted -- what came.
 *
 * <p>{@code receivedQty} is everything counted at the dock, damaged units
 * included; {@code damagedQty} is the part of it that cannot go into stock.
 * Short and excess are derived from those two and the expectation, never
 * entered, so they cannot disagree with the count.
 */
@Table("asn_line")
public record AsnLine(
        @Id UUID id,
        UUID tenantId,
        UUID asnId,
        int lineNo,
        UUID skuId,
        String lotNo,
        int expectedQty,
        Integer receivedQty,
        Integer damagedQty,
        @Version Long version) {

    public static AsnLine expect(UUID tenantId, UUID asnId, int lineNo, UUID skuId, String lotNo,
                                 int expectedQty) {
        if (expectedQty <= 0) {
            throw new BusinessRuleViolationException("asn-line-quantity-invalid",
                    "ASN line " + lineNo + " must expect at least one unit");
        }
        return new AsnLine(UUID.randomUUID(), tenantId, asnId, lineNo, skuId, normaliseLot(lotNo),
                expectedQty, null, null, null);
    }

    public AsnLine count(int received, int damaged) {
        if (received < 0 || damaged < 0) {
            throw new BusinessRuleViolationException("receipt-count-invalid",
                    "Line " + lineNo + ": counts cannot be negative");
        }
        if (damaged > received) {
            throw new BusinessRuleViolationException("receipt-count-invalid",
                    "Line " + lineNo + ": " + damaged + " damaged is more than the "
                            + received + " received");
        }
        return new AsnLine(id, tenantId, asnId, lineNo, skuId, lotNo, expectedQty, received,
                damaged, version);
    }

    /** What may go into stock: counted and undamaged. */
    public int goodQty() {
        return receivedQty == null ? 0 : receivedQty - damagedQty;
    }

    public int shortQty() {
        return receivedQty == null ? 0 : Math.max(0, expectedQty - receivedQty);
    }

    public int excessQty() {
        return receivedQty == null ? 0 : Math.max(0, receivedQty - expectedQty);
    }

    public boolean hasVariance() {
        return shortQty() > 0 || excessQty() > 0 || (damagedQty != null && damagedQty > 0);
    }

    /** Lots are part of the balance key; "no lot" is the empty string. */
    public static String normaliseLot(String lotNo) {
        return lotNo == null ? "" : lotNo.strip();
    }
}
