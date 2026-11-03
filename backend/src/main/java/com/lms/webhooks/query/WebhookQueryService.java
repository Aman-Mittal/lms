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
package com.lms.webhooks.query;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.query.Slice;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The read side of webhooks. */
@Service
public class WebhookQueryService {

    private static final String SUBSCRIPTION_SELECT = """
            SELECT id, name, url, event_types, active, paused_reason, consecutive_failures,
                   created_at, updated_at
              FROM webhook_subscription
            """;

    private final JdbcClient jdbc;

    public WebhookQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Ordered by name; the lower-cased name is unique per tenant, so it is the cursor. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('WEBHOOK_READ')")
    public Slice<WebhookSubscriptionView> subscriptions(String cursor, Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        var rows = jdbc.sql(SUBSCRIPTION_SELECT + """
                         WHERE (CAST(:cursor AS text) IS NULL OR lower(name) > CAST(:cursor AS text))
                         ORDER BY lower(name)
                         LIMIT :limit
                        """)
                .param("cursor", cursor).param("limit", pageSize + 1)
                .query((rs, row) -> subscription(rs))
                .list();
        return Slice.of(rows, pageSize, v -> v.name().toLowerCase(java.util.Locale.ROOT));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('WEBHOOK_READ')")
    public Optional<WebhookSubscriptionView> subscription(UUID id) {
        return jdbc.sql(SUBSCRIPTION_SELECT + " WHERE id = :id").param("id", id)
                .query((rs, row) -> subscription(rs)).optional();
    }

    /**
     * Newest first. The cursor is the last row's creation time and id, so two
     * rows created in the same instant are not skipped.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('WEBHOOK_READ')")
    public Slice<WebhookDeliveryView> deliveries(UUID subscriptionId, String status, String cursor,
                                                 Integer limit) {
        int pageSize = Slice.clampLimit(limit);
        Instant afterTime = null;
        UUID afterId = null;
        if (cursor != null) {
            String[] parts = cursor.split("_", 2);
            try {
                afterTime = Instant.parse(parts[0]);
                afterId = UUID.fromString(parts[1]);
            } catch (DateTimeParseException | IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
                throw new BusinessRuleViolationException("cursor-invalid", "That cursor was not issued by this listing");
            }
        }
        var rows = jdbc.sql("""
                        SELECT id, subscription_id, event_id, event_type, status, attempts, last_status_code,
                               last_error, next_attempt_at, delivered_at, created_at
                          FROM webhook_delivery
                         WHERE subscription_id = :subscriptionId
                           AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
                           AND (CAST(:afterTime AS timestamptz) IS NULL
                                OR (created_at, id) < (CAST(:afterTime AS timestamptz), CAST(:afterId AS uuid)))
                         ORDER BY created_at DESC, id DESC
                         LIMIT :limit
                        """)
                .param("subscriptionId", subscriptionId)
                .param("status", status)
                .param("afterTime", afterTime == null ? null : java.sql.Timestamp.from(afterTime))
                .param("afterId", afterId)
                .param("limit", pageSize + 1)
                .query(WebhookDeliveryView.class)
                .list();
        return Slice.of(rows, pageSize, v -> v.createdAt() + "_" + v.id());
    }

    private static WebhookSubscriptionView subscription(ResultSet rs) throws SQLException {
        Array types = rs.getArray("event_types");
        List<String> eventTypes = types == null ? List.of() : Arrays.asList((String[]) types.getArray());
        return new WebhookSubscriptionView(
                rs.getObject("id", UUID.class),
                rs.getString("name"),
                rs.getString("url"),
                List.copyOf(eventTypes),
                rs.getBoolean("active"),
                rs.getString("paused_reason"),
                rs.getInt("consecutive_failures"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
