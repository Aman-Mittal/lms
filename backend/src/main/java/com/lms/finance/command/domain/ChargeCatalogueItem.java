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

package com.lms.finance.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * One kind of charge a tenant raises by hand: loading, unloading, a toll
 * paid on the road, halting, a penalty. It carries the basis the quantity is
 * counted in and a default rate a person may override on the charge itself.
 *
 * <p>The code never changes; a charge raised from it keeps a copy of what the
 * entry said, so editing the catalogue later changes nothing already raised.
 * An entry is switched off rather than deleted, for the same reason.
 */
@Table("charge_catalogue")
public record ChargeCatalogueItem(
        @Id UUID id,
        UUID tenantId,
        String code,
        String name,
        ChargeBasis basis,
        BigDecimal defaultRate,
        String currency,
        ChargeDirection direction,
        boolean active,
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public static ChargeCatalogueItem create(UUID id, UUID tenantId, String code, String name, ChargeBasis basis,
                                             BigDecimal defaultRate, ChargeDirection direction, boolean active) {
        return new ChargeCatalogueItem(id, tenantId, normaliseCode(code), requireName(name), requireBasis(basis),
                requireRate(defaultRate), "INR", requireDirection(direction), active, null, Instant.now(),
                Instant.now());
    }

    /** Everything but the code, which is how the entry is known. */
    public ChargeCatalogueItem edited(String newName, ChargeBasis newBasis, BigDecimal newDefaultRate,
                                      ChargeDirection newDirection, boolean newActive) {
        return new ChargeCatalogueItem(id, tenantId, code, requireName(newName), requireBasis(newBasis),
                requireRate(newDefaultRate), currency, requireDirection(newDirection), newActive, version,
                createdAt, Instant.now());
    }

    /** Upper case, no surrounding blanks; at most 40 characters of letters, digits, dash and underscore. */
    public static String normaliseCode(String code) {
        String normal = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (normal.isEmpty() || normal.length() > 40 || !normal.matches("[A-Z0-9][A-Z0-9_-]*")) {
            throw new BusinessRuleViolationException("charge-code-invalid",
                    "A charge code is 1 to 40 letters, digits, dashes or underscores");
        }
        return normal;
    }

    private static String requireName(String name) {
        String text = name == null ? "" : name.strip();
        if (text.isEmpty() || text.length() > 120) {
            throw new BusinessRuleViolationException("charge-name-invalid",
                    "A charge's name is 1 to 120 characters");
        }
        return text;
    }

    private static ChargeBasis requireBasis(ChargeBasis basis) {
        if (basis == null) {
            throw new BusinessRuleViolationException("charge-basis-required", "A charge says what it is counted in");
        }
        return basis;
    }

    private static ChargeDirection requireDirection(ChargeDirection direction) {
        if (direction == null) {
            throw new BusinessRuleViolationException("charge-direction-required",
                    "A charge says whether it is billed to the customer, paid to the vendor or either");
        }
        return direction;
    }

    private static BigDecimal requireRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0) {
            throw new BusinessRuleViolationException("charge-rate-invalid", "A default rate is zero or more");
        }
        return rate;
    }
}
