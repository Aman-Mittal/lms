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
package com.lms.masterdata.command.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A customer, vendor (transporter), broker or consignee (vision document 3.2.1).
 */
@Table("business_partner")
public record BusinessPartner(
        @Id UUID id,
        UUID tenantId,
        UUID orgUnitId,
        String code,
        String legalName,
        PartnerType partnerType,
        PartnerStatus status,
        String taxId,
        BigDecimal creditLimit,
        String paymentTerms,
        String billingAddress,
        BigDecimal onTimePct,
        BigDecimal claimsRatio,
        Instant scorecardAt,
        /*
         * Required for correctness: Spring Data JDBC treats a record with a
         * pre-assigned @Id as existing and would UPDATE nothing instead of
         * inserting. A null version marks the aggregate as new.
         */
        @Version Long version,
        Instant createdAt,
        Instant updatedAt) {

    public enum PartnerType {
        CUSTOMER, VENDOR, BROKER, CONSIGNEE
    }

    public enum PartnerStatus {
        DRAFT, PENDING_VERIFICATION, ACTIVE, SUSPENDED, BLACKLISTED
    }

    /**
     * The KYC lifecycle of 3.2.1, as an explicit transition table.
     *
     * <p>BLACKLISTED is terminal on purpose. Reinstating a blacklisted partner
     * should be a deliberate act with its own record, not a status edit that
     * leaves no trace of why the block was lifted.
     */
    private static final Map<PartnerStatus, Set<PartnerStatus>> TRANSITIONS =
            new EnumMap<>(PartnerStatus.class);

    static {
        TRANSITIONS.put(PartnerStatus.DRAFT,
                EnumSet.of(PartnerStatus.PENDING_VERIFICATION, PartnerStatus.BLACKLISTED));
        TRANSITIONS.put(PartnerStatus.PENDING_VERIFICATION,
                EnumSet.of(PartnerStatus.ACTIVE, PartnerStatus.DRAFT, PartnerStatus.BLACKLISTED));
        TRANSITIONS.put(PartnerStatus.ACTIVE,
                EnumSet.of(PartnerStatus.SUSPENDED, PartnerStatus.BLACKLISTED));
        TRANSITIONS.put(PartnerStatus.SUSPENDED,
                EnumSet.of(PartnerStatus.ACTIVE, PartnerStatus.BLACKLISTED));
        TRANSITIONS.put(PartnerStatus.BLACKLISTED, EnumSet.noneOf(PartnerStatus.class));
    }

    /**
     * Registers a partner in DRAFT.
     *
     * <p>Always DRAFT, never ACTIVE: 3.2.1 requires partners to be walked
     * through verification, and creating one ready to transact would bypass KYC
     * altogether.
     */
    public static BusinessPartner register(UUID id, UUID tenantId, UUID orgUnitId, String code,
                                           String legalName, PartnerType type, String taxId) {
        return new BusinessPartner(id, tenantId, orgUnitId, code, legalName, type,
                PartnerStatus.DRAFT, taxId, null, null, null, null, null, null,
                null, Instant.now(), Instant.now());
    }

    /**
     * Whether the partner may be allocated a load.
     *
     * <p>Vision document 3.2.1: "System MUST block order creation or load
     * allocation to any vendor in a SUSPENDED or BLACKLISTED state." A partner
     * still in DRAFT or PENDING_VERIFICATION has not been checked yet, so it is
     * equally ineligible -- only ACTIVE will do.
     */
    public boolean canTransact() {
        return status == PartnerStatus.ACTIVE;
    }

    /** Applies a lifecycle transition, refusing anything not in the table. */
    public BusinessPartner transitionTo(PartnerStatus target) {
        if (status == target) {
            return this;
        }
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(target)) {
            throw new BusinessRuleViolationException("partner-illegal-transition",
                    "A partner cannot move from " + status + " to " + target
                            + (status == PartnerStatus.BLACKLISTED
                            ? "; blacklisting is terminal" : ""));
        }
        return new BusinessPartner(id, tenantId, orgUnitId, code, legalName, partnerType, target,
                taxId, creditLimit, paymentTerms, billingAddress, onTimePct, claimsRatio,
                scorecardAt, version, createdAt, Instant.now());
    }

    /**
     * Composite score used to rank bids (vision document 3.5):
     * price weighted 70%, historical SLA 30%.
     *
     * <p>A partner with no scorecard yet returns null rather than zero. Treating
     * "not yet measured" as "measured, and terrible" would permanently exclude
     * every new vendor from winning work, which is how a routing guide quietly
     * ossifies around incumbents.
     */
    public BigDecimal slaScore() {
        if (onTimePct == null) {
            return null;
        }
        BigDecimal claimsPenalty = claimsRatio == null ? BigDecimal.ZERO : claimsRatio;
        return onTimePct.subtract(claimsPenalty).max(BigDecimal.ZERO);
    }
}
