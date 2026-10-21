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
package com.lms.warehouse.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import com.lms.warehouse.command.InboundCommandService;
import com.lms.warehouse.query.AsnSummaryView;
import com.lms.warehouse.query.AsnView;
import com.lms.warehouse.query.WarehouseQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Goods in: ASNs, the receipt against them, and putaway. */
@RestController
public class InboundController {

    private final InboundCommandService inbound;
    private final WarehouseQueryService queries;

    public InboundController(InboundCommandService inbound, WarehouseQueryService queries) {
        this.inbound = inbound;
        this.queries = queries;
    }

    @PostMapping("/api/v1/asns")
    public ResponseEntity<IdResponse> createAsn(@Valid @RequestBody CreateAsnRequest request) {
        UUID id = inbound.createAsn(request.warehouseId(), request.asnNo(),
                request.supplierPartnerId(), request.lines().stream()
                        .map(line -> new InboundCommandService.ExpectedLine(line.skuId(),
                                line.lotNo(), line.quantity()))
                        .toList());
        return ResponseEntity.created(URI.create("/api/v1/asns/" + id)).body(new IdResponse(id));
    }

    @GetMapping("/api/v1/asns")
    public Slice<AsnSummaryView> asns(@RequestParam UUID warehouseId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return queries.asns(warehouseId, status, cursor, limit);
    }

    @GetMapping("/api/v1/asns/{id}")
    public AsnView asn(@PathVariable UUID id) {
        return queries.asn(id).orElseThrow(() -> new ResourceNotFoundException("ASN", id));
    }

    /**
     * Records the count. Answers 204; the variances and any cross-dock
     * handling unit are read back from the ASN.
     */
    @PostMapping("/api/v1/asns/{id}/receipt")
    public ResponseEntity<Void> receive(@PathVariable UUID id,
                                        @Valid @RequestBody ReceiveRequest request) {
        inbound.receive(id, request.lines() == null ? List.of() : request.lines().stream()
                        .map(line -> new InboundCommandService.CountedLine(line.lineNo(),
                                line.receivedQty(), line.damagedQty()))
                        .toList(),
                Boolean.TRUE.equals(request.crossDock()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/putaways")
    public ResponseEntity<Void> putaway(@Valid @RequestBody PutawayRequest request) {
        inbound.putaway(request.binId(), request.skuId(), request.lotNo(), request.quantity());
        return ResponseEntity.noContent().build();
    }

    public record CreateAsnRequest(@NotNull UUID warehouseId,
                                   @NotBlank String asnNo,
                                   UUID supplierPartnerId,
                                   @NotEmpty List<@Valid ExpectedLine> lines) {
    }

    public record ExpectedLine(@NotNull UUID skuId, String lotNo, @Positive int quantity) {
    }

    public record ReceiveRequest(List<@Valid CountedLine> lines, Boolean crossDock) {
    }

    public record CountedLine(@Positive int lineNo,
                              @PositiveOrZero int receivedQty,
                              @PositiveOrZero int damagedQty) {
    }

    public record PutawayRequest(@NotNull UUID binId, @NotNull UUID skuId, String lotNo,
                                 @Positive int quantity) {
    }
}
