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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.lms.planning.command.domain.ConsignmentAmendment.FieldResult;
import com.lms.shared.error.BusinessRuleViolationException;
import com.lms.shared.error.ResourceNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Amends or cancels many consignments in one request, each on its own.
 *
 * <p>Unlike putting many consignments on one load -- all or none, because the
 * load is one vehicle -- these are independent acts on independent
 * consignments, so each item succeeds or is refused by itself and the answer
 * says which, in the order asked, with the code a single request would have
 * given. Each item runs in its own transaction, one after another, so the
 * request never holds more than one connection (ADR 0006) and a refused item
 * leaves nothing behind.
 */
@Service
public class ConsignmentBulkService {

    /** The fewest and most items one bulk request takes. */
    public static final int MAX_ITEMS = 200;

    /** One item's outcome. */
    public enum Outcome {
        AMENDED, CANCELLED, REFUSED
    }

    /**
     * What became of one item.
     *
     * @param version    the consignment's version after an amendment; null otherwise
     * @param fields     each named field's outcome, for an amendment that was judged
     * @param errors     each refused field to its reason, for a refused amendment
     */
    public record ItemResult(UUID consignmentId, Outcome outcome, String code, String message,
                             Long version, List<FieldResult> fields, Map<String, String> errors) {
    }

    /** One consignment to amend: the version the caller read, and the changes. */
    public record AmendItem(UUID consignmentId, long version, Map<String, ?> changes) {
    }

    private final ConsignmentAmendmentService amendments;
    private final ConsignmentCancellationService cancellations;

    ConsignmentBulkService(ConsignmentAmendmentService amendments,
                           ConsignmentCancellationService cancellations) {
        this.amendments = amendments;
        this.cancellations = cancellations;
    }

    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public List<ItemResult> amendAll(List<AmendItem> items) {
        List<ItemResult> results = new ArrayList<>(items.size());
        for (AmendItem item : items) {
            results.add(attempt(item.consignmentId(), () -> {
                ConsignmentAmendmentService.Amended done =
                        amendments.amend(item.consignmentId(), item.version(), item.changes());
                return new ItemResult(item.consignmentId(), Outcome.AMENDED, null, null,
                        done.version(), done.fields(), Map.of());
            }));
        }
        return results;
    }

    @PreAuthorize("hasAuthority('LOAD_CREATE')")
    public List<ItemResult> cancelAll(List<UUID> consignmentIds, String reason) {
        List<ItemResult> results = new ArrayList<>(consignmentIds.size());
        for (UUID id : consignmentIds) {
            results.add(attempt(id, () -> {
                cancellations.cancel(id, reason);
                return new ItemResult(id, Outcome.CANCELLED, null, null, null, List.of(), Map.of());
            }));
        }
        return results;
    }

    /**
     * Runs one item through the service's own transactional entry point, and
     * turns a refusal into that item's result rather than the request's.
     */
    private static ItemResult attempt(UUID id, Supplier<ItemResult> action) {
        try {
            return action.get();
        } catch (BusinessRuleViolationException e) {
            return refused(id, e.code(), e.getMessage(), e.errors());
        } catch (ResourceNotFoundException e) {
            return refused(id, "not-found", "No such consignment", Map.of());
        } catch (IllegalArgumentException e) {
            return refused(id, "invalid-request", e.getMessage(), Map.of());
        } catch (RuntimeException e) {
            // A lost race surfaces wrapped by the persistence layer.
            for (Throwable cause = e; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
                if (cause instanceof OptimisticLockingFailureException) {
                    return refused(id, "concurrent-modification", "The consignment was changed by "
                            + "someone else; re-read it and try again", Map.of());
                }
                if (cause instanceof DuplicateKeyException) {
                    return refused(id, "duplicate-resource", "A consignment with the same client "
                            + "reference already exists", Map.of());
                }
            }
            throw e;
        }
    }

    private static ItemResult refused(UUID id, String code, String message, Map<String, String> errors) {
        return new ItemResult(id, Outcome.REFUSED, code, message, null, List.of(), errors);
    }
}
