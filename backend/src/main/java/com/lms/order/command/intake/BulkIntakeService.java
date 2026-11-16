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

package com.lms.order.command.intake;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lms.order.api.FreightMode;
import com.lms.order.api.MaterialClass;
import com.lms.order.command.OrderCommandService;
import com.lms.order.command.domain.SalesOrder;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bulk order intake (FR-ORD-002): a batch of orders, each with its own
 * idempotency key, each raised, lined and validated by the order module's own
 * commands, each in a transaction of its own, so one bad item can never fail
 * or roll back the others.
 *
 * <p>A key is remembered with the order it made (and a digest of the item).
 * Sending the same item again, a retried batch or a file uploaded twice,
 * answers DUPLICATE with the order already made; sending the key with other
 * content is a client defect and fails that item
 * ({@code idempotency-key-reused}). Items that share a key within one batch
 * are served in turn by an advisory lock, so the second sees the first.
 */
@Service
public class BulkIntakeService {

    /** The most items one batch holds. */
    public static final int MAX_ITEMS = 500;

    private static final Logger log = LoggerFactory.getLogger(BulkIntakeService.class);

    private final OrderCommandService orders;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public BulkIntakeService(OrderCommandService orders, JdbcClient jdbc, PlatformTransactionManager manager) {
        this.orders = orders;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
    }

    /**
     * Not transactional itself: each item opens its own transaction after the
     * tenant scope is bound, which is the order the platform requires.
     *
     * @return one result per item, in the order sent
     * @throws BusinessRuleViolationException {@code bulk-batch-too-large} past {@value #MAX_ITEMS} items
     */
    @PreAuthorize("hasAuthority('ORDER_CREATE') and hasAuthority('ORDER_UPDATE')"
            + " and @abac.permitsIn('ORDER_CREATE', #orgUnitId)")
    public List<BulkResult> submit(UUID orgUnitId, List<BulkItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A batch holds at least one item");
        }
        if (items.size() > MAX_ITEMS) {
            throw new BusinessRuleViolationException("bulk-batch-too-large",
                    "A batch holds at most " + MAX_ITEMS + " items; this one has " + items.size());
        }
        List<BulkResult> results = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            results.add(one(orgUnitId, i, items.get(i)));
        }
        return results;
    }

    private BulkResult one(UUID orgUnitId, int index, BulkItem item) {
        String key = item.idempotencyKey() == null ? "" : item.idempotencyKey().strip();
        if (key.isEmpty() || key.length() > 255) {
            return BulkResult.failed(index, List.of("idempotency-key-required: an item carries an idempotency key"
                    + " of 1 to 255 characters"));
        }
        try {
            BulkResult outcome = transactions.execute(status -> inTransaction(orgUnitId, index, key, item));
            return outcome == null ? BulkResult.failed(index, List.of("unexpected: no outcome")) : outcome;
        } catch (AccessDeniedException refused) {
            throw refused;
        } catch (BusinessRuleViolationException refused) {
            return BulkResult.failed(index, reasonsOf(refused));
        } catch (ResourceNotFoundException | IllegalArgumentException refused) {
            return BulkResult.failed(index, List.of(refused.getMessage()));
        } catch (DataAccessException e) {
            log.warn("Bulk item {} failed in the database: {}", index, e.getMostSpecificCause().getMessage());
            return BulkResult.failed(index, List.of("conflict: the item could not be stored; send it again"));
        } catch (RuntimeException e) {
            log.error("Bulk item {} failed unexpectedly", index, e);
            return BulkResult.failed(index, List.of("unexpected: the item could not be processed"));
        }
    }

    private BulkResult inTransaction(UUID orgUnitId, int index, String key, BulkItem item) {
        UUID tenantId = TenantContext.requireTenantId();
        String digest = item.digest();
        // Serialises items that share a key, within and across batches.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0))")
                .param("scope", tenantId + ":" + key).query().singleRow();
        Optional<Known> known = jdbc.sql("""
                        SELECT order_id, request_hash FROM bulk_intake_key
                         WHERE tenant_id = :tenantId AND idempotency_key = :key
                        """)
                .param("tenantId", tenantId).param("key", key).query(Known.class).optional();
        if (known.isPresent()) {
            if (!known.get().requestHash().equals(digest)) {
                throw new BusinessRuleViolationException("idempotency-key-reused",
                        "The key " + key + " was used for different content");
            }
            return BulkResult.duplicate(index, known.get().orderId());
        }

        UUID orderId = orders.raiseOrder(orgUnitId, item.customerPartnerId(), item.orderNo(),
                item.originTerminalId(), item.requestedPickupAt(), item.requestedDeliveryAt(),
                SalesOrder.OrderSource.BULK_UPLOAD, FreightMode.FTL, null, null, null, item.loadType());
        int lineNo = 1;
        for (BulkItem.Line line : item.lines()) {
            orders.addLine(orderId, lineNo++, line.materialCode(), line.materialDescription(),
                    line.materialClass() == null ? MaterialClass.GENERAL : line.materialClass(), line.hazmatUnCode(),
                    line.quantity(), line.uom(), line.deadWeightKg(), null, null, null, null,
                    line.consigneePartnerId() == null ? item.customerPartnerId() : line.consigneePartnerId(),
                    line.destinationTerminalId());
        }
        orders.validate(orderId);
        jdbc.sql("""
                        INSERT INTO bulk_intake_key (tenant_id, idempotency_key, request_hash, order_id)
                        VALUES (:tenantId, :key, :hash, :orderId)
                        """)
                .param("tenantId", tenantId).param("key", key).param("hash", digest).param("orderId", orderId)
                .update();
        return BulkResult.created(index, orderId);
    }

    private record Known(UUID orderId, String requestHash) {
    }

    /** The refusal's code and message, then each item it named, so a screen can show every reason. */
    static List<String> reasonsOf(BusinessRuleViolationException refused) {
        List<String> reasons = new ArrayList<>();
        reasons.add(refused.code() + ": " + refused.getMessage());
        refused.errors().forEach((subject, why) -> reasons.add(subject + ": " + why));
        return reasons;
    }
}
