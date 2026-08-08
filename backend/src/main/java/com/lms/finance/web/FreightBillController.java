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
 */package com.lms.finance.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.lms.finance.command.BillingService;
import com.lms.finance.command.domain.FreightBill;
import com.lms.finance.query.FinanceQueryService;
import com.lms.finance.query.FreightBillLineView;
import com.lms.finance.query.FreightBillView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Freight bills and their settlement (vision document 3.9.2).
 *
 * <p>There is no endpoint to create one. A bill is raised by the platform when
 * a trip completes, because pricing depends on the dispatch date and the origin
 * dwell -- facts the movement produces, not facts a caller supplies. Letting a
 * client post a bill would let it post the numbers too.
 */
@RestController
@RequestMapping("/api/v1/freight-bills")
public class FreightBillController {

    private final BillingService billing;
    private final FinanceQueryService finance;

    public FreightBillController(BillingService billing, FinanceQueryService finance) {
        this.billing = billing;
        this.finance = finance;
    }

    @GetMapping
    public Slice<FreightBillView> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String cursor,
                                       @RequestParam(required = false) Integer limit) {
        return finance.list(status, cursor, limit);
    }

    @GetMapping("/{id}")
    public FreightBillView get(@PathVariable UUID id) {
        return finance.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("FreightBill", id));
    }

    /** The arithmetic behind the total, which is what a dispute is argued from. */
    @GetMapping("/{id}/lines")
    public List<FreightBillLineView> lines(@PathVariable UUID id) {
        return finance.linesOf(id);
    }

    /**
     * Records the vendor's invoice and answers with what it settled to.
     *
     * <p>Returns the resulting status rather than 204, because the whole point
     * of the call is which of the two it produced. A caller that had to issue a
     * second request to find out would be reading a value that another
     * request could already have changed.
     */
    @PostMapping("/{id}/match")
    public MatchResponse match(@PathVariable UUID id, @Valid @RequestBody MatchRequest request) {
        FreightBill.BillStatus status = billing.matchVendorClaim(id, request.claimedAmount());
        return new MatchResponse(status.name());
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Void> resolve(@PathVariable UUID id,
                                        @Valid @RequestBody ResolveRequest request) {
        billing.resolveDispute(id, request.reason());
        return ResponseEntity.noContent().build();
    }

    public record MatchRequest(@NotNull @PositiveOrZero BigDecimal claimedAmount) {
    }

    public record MatchResponse(String status) {
    }

    public record ResolveRequest(@NotBlank String reason) {
    }
}
