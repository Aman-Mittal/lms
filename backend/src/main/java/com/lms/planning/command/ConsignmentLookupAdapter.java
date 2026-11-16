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
package com.lms.planning.command;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.planning.api.ConsignmentLookupPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers {@link ConsignmentLookupPort} with plain reads under the caller's
 * tenant scope, so a consignment of another tenant reads as absent.
 */
@Component
public class ConsignmentLookupAdapter implements ConsignmentLookupPort {

    private static final String SELECT = """
            SELECT c.id, c.tracking_ref, o.org_unit_id, c.status, lc.load_id
              FROM consignment c
              JOIN sales_order o ON o.id = c.order_id
              LEFT JOIN load_consignment lc ON lc.consignment_id = c.id
            """;

    private final JdbcClient jdbc;

    public ConsignmentLookupAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConsignmentRef> find(UUID consignmentId) {
        return jdbc.sql(SELECT + " WHERE c.id = :id AND c.tenant_id = app_current_tenant()")
                .param("id", consignmentId).query(ConsignmentRef.class).optional();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsignmentRef> onLoad(UUID loadId) {
        return jdbc.sql(SELECT + " WHERE lc.load_id = :loadId AND c.tenant_id = app_current_tenant()"
                        + " ORDER BY lc.drop_sequence, c.id")
                .param("loadId", loadId).query(ConsignmentRef.class).list();
    }
}
