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
package com.lms.clientbilling.query;

import java.util.Map;
import java.util.UUID;

import com.lms.identity.access.ResourceAttributeProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What access policies may know about a client invoice.
 *
 * <p>Only where it lives: an invoice has no unit of its own, so it is filed
 * where its client partner is. A credit note, which corrects the invoice, is
 * held to the same subtree.
 */
@Component
class ClientInvoiceAttributes implements ResourceAttributeProvider {

    private final JdbcClient jdbc;

    ClientInvoiceAttributes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String resourceType() {
        return "CLIENT_INVOICE";
    }

    @Override
    public Map<String, Object> attributesOf(UUID id) {
        return jdbc.sql("""
                        SELECT p.org_unit_id
                          FROM client_invoice i
                          JOIN business_partner p ON p.id = i.client_partner_id
                         WHERE i.id = :id
                        """)
                .param("id", id)
                .query(UUID.class)
                .optional()
                .<Map<String, Object>>map(unit -> Map.of("orgUnitId", unit))
                .orElseGet(Map::of);
    }
}
