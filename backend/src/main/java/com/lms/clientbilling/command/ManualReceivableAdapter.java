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

package com.lms.clientbilling.command;

import java.util.Optional;
import java.util.UUID;

import com.lms.clientbilling.command.domain.ClientCharge;
import com.lms.finance.api.ReceivableChargePort;
import com.lms.shared.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finance's manual receivables, as client charges: a charge raised by hand
 * against a trip, consignment or order is captured here for the customer's next
 * open month, priced by its own amount, and taken back while it is on no
 * invoice.
 *
 * <p>A customer with no client contract is not one of this module's clients
 * (the same rule transport and hand-off charges follow), so nothing is
 * captured and finance records the receivable alone. Idempotent on the
 * charge's source key, so a retried approval makes no second charge.
 */
@Component
class ManualReceivableAdapter implements ReceivableChargePort {

    private final ClientChargeRepository charges;
    private final ClientContractRepository contracts;
    private final ClientPeriods periods;
    private final JdbcClient jdbc;

    ManualReceivableAdapter(ClientChargeRepository charges, ClientContractRepository contracts,
                            ClientPeriods periods, JdbcClient jdbc) {
        this.charges = charges;
        this.contracts = contracts;
        this.periods = periods;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> capture(Capture request) {
        UUID tenantId = TenantContext.requireTenantId();
        if (contracts.countForClient(tenantId, request.customerPartnerId()) == 0) {
            return Optional.empty();
        }
        Optional<ClientCharge> already = charges.findBySourceKey(tenantId, request.sourceKey());
        if (already.isPresent()) {
            return Optional.of(already.get().id());
        }
        periods.lock(tenantId, request.customerPartnerId());
        return Optional.of(charges.save(ClientCharge.manual(UUID.randomUUID(), tenantId,
                request.customerPartnerId(), request.quantity(), request.unit(), request.rate(), request.amount(),
                request.narrative(), request.occurredOn(),
                periods.openPeriodFor(tenantId, request.customerPartnerId(), request.occurredOn()),
                request.sourceKey(), request.reference(), request.tripId())).id());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean withdraw(UUID clientChargeId) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID client = jdbc.sql("SELECT client_partner_id FROM client_charge WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", clientChargeId)
                .query(UUID.class)
                .optional()
                .orElse(null);
        if (client == null) {
            return true;
        }
        // The billing run holds this lock while it invoices the client's month, so a
        // cancellation waits for it and then sees whether the charge was invoiced.
        periods.lock(tenantId, client);
        // The delete is conditional on the charge being on no invoice, in one
        // statement, so a billing run racing the cancellation decides it: one of
        // the two wins and the other sees the result.
        int removed = jdbc.sql("""
                        DELETE FROM client_charge
                         WHERE tenant_id = :tenantId AND id = :id AND invoice_id IS NULL AND service_type = 'MANUAL'
                        """)
                .param("tenantId", tenantId)
                .param("id", clientChargeId)
                .update();
        return removed == 1 || !exists(tenantId, clientChargeId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean isInvoiced(UUID clientChargeId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM client_charge
                                        WHERE tenant_id = :tenantId AND id = :id AND invoice_id IS NOT NULL)
                        """)
                .param("tenantId", TenantContext.requireTenantId())
                .param("id", clientChargeId)
                .query(Boolean.class)
                .single());
    }

    private boolean exists(UUID tenantId, UUID id) {
        return Boolean.TRUE.equals(jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM client_charge WHERE tenant_id = :tenantId AND id = :id)")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(Boolean.class)
                .single());
    }
}
