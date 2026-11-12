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

package com.lms.finance.command;

import java.util.UUID;

import com.lms.execution.api.TripLookupPort;
import com.lms.finance.command.domain.ManualCharge;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What a manual charge is raised against, found for it: the trip (through
 * execution's port), a consignment or an order (read under row-level security,
 * so another tenant's is absent). A subject that is cancelled takes no charge.
 *
 * <p>Answers the unit the charge belongs to, the customer a receivable is
 * billed to and, for a trip, the vendor a payable is owed to. A trip carrying
 * several customers' freight has no sole customer, and the caller then names
 * which one the receivable is for.
 */
@Component
class MoneySubjects {

    private final TripLookupPort trips;
    private final JdbcClient jdbc;

    MoneySubjects(TripLookupPort trips, JdbcClient jdbc) {
        this.trips = trips;
        this.jdbc = jdbc;
    }

    /** A subject and the customers a receivable on it could be billed to. */
    record Found(ManualCharge.Subject subject, java.util.List<UUID> customers) {
    }

    Found find(ManualCharge.SubjectType type, UUID id) {
        return switch (type) {
            case TRIP -> trip(id);
            case CONSIGNMENT -> consignment(id);
            case ORDER -> order(id);
        };
    }

    private Found trip(UUID id) {
        TripLookupPort.TripSnapshot trip = trips.find(id).orElseThrow(() -> new ResourceNotFoundException("Trip", id));
        if (trip.isCancelled()) {
            throw cancelled("Trip " + trip.tripNo());
        }
        TripLookupPort.TripScope scope = trips.scopeOf(id).orElseThrow(() -> new ResourceNotFoundException("Trip", id));
        return new Found(new ManualCharge.Subject(ManualCharge.SubjectType.TRIP, id, trip.tripNo(), scope.orgUnitId(),
                id, scope.soleCustomerId(), trip.vendorPartnerId()), scope.customerIds());
    }

    private Found consignment(UUID id) {
        return jdbc.sql("""
                        SELECT c.tracking_ref, c.status, so.org_unit_id, so.customer_partner_id
                          FROM consignment c
                          LEFT JOIN sales_order so ON so.id = c.order_id
                         WHERE c.id = :id
                        """)
                .param("id", id)
                .query((rs, row) -> {
                    if ("CANCELLED".equals(rs.getString("status"))) {
                        throw cancelled("Consignment " + rs.getString("tracking_ref"));
                    }
                    UUID unit = rs.getObject("org_unit_id", UUID.class);
                    if (unit == null) {
                        throw new BusinessRuleViolationException("manual-charge-subject-unscoped",
                                "Consignment " + rs.getString("tracking_ref")
                                        + " has no order, so there is no customer or unit to charge");
                    }
                    UUID customer = rs.getObject("customer_partner_id", UUID.class);
                    return new Found(new ManualCharge.Subject(ManualCharge.SubjectType.CONSIGNMENT, id,
                            rs.getString("tracking_ref"), unit, null, customer, null), java.util.List.of(customer));
                })
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Consignment", id));
    }

    private Found order(UUID id) {
        return jdbc.sql("SELECT order_no, status, org_unit_id, customer_partner_id FROM sales_order WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> {
                    if ("CANCELLED".equals(rs.getString("status"))) {
                        throw cancelled("Order " + rs.getString("order_no"));
                    }
                    UUID customer = rs.getObject("customer_partner_id", UUID.class);
                    return new Found(new ManualCharge.Subject(ManualCharge.SubjectType.ORDER, id,
                            rs.getString("order_no"), rs.getObject("org_unit_id", UUID.class), null, customer, null),
                            java.util.List.of(customer));
                })
                .optional()
                .orElseThrow(() -> new ResourceNotFoundException("Order", id));
    }

    private static BusinessRuleViolationException cancelled(String what) {
        return new BusinessRuleViolationException("manual-charge-subject-cancelled",
                what + " is cancelled; no charge is raised against it");
    }
}
