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
package com.lms.finance.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.finance.command.LtlRatingService;
import com.lms.finance.command.domain.LtlRateCard;
import com.lms.finance.query.DocketReceivableView;
import com.lms.finance.query.LtlQueryService;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Part-load rating: zones, zone-to-zone rate cards, and the receivable each
 * docket earns.
 *
 * <p>Receivables are read-only here. They are raised by the platform when a
 * docket is booked, never posted by a client, for the same reason freight
 * bills are: the price is the rate card's answer, not the caller's.
 */
@RestController
@RequestMapping("/api/v1/ltl")
public class LtlController {

    private final LtlRatingService rating;
    private final LtlQueryService queries;

    public LtlController(LtlRatingService rating, LtlQueryService queries) {
        this.rating = rating;
        this.queries = queries;
    }

    /** A PUT: a terminal is in one zone, so placing it again replaces the placement. */
    @PutMapping("/zones/{terminalId}")
    public ResponseEntity<Void> assignZone(@PathVariable UUID terminalId,
                                           @Valid @RequestBody AssignZoneRequest request) {
        rating.assignZone(terminalId, request.zoneCode(),
                Boolean.TRUE.equals(request.outOfDeliveryArea()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rate-cards")
    public ResponseEntity<IdResponse> publish(@Valid @RequestBody PublishRateCardRequest request) {
        UUID id = rating.publishRateCard(request.code(), request.originZone(),
                request.destinationZone(), request.currency(), request.effectiveFrom(),
                request.rateBasis(), request.rateAmount(), request.minimumChargeableWeightKg(),
                request.kgPerCft(), request.docketCharge(), request.odaSurcharge(),
                request.odaBasis(), request.odaRatePerKg(), request.odaMinimum());
        return ResponseEntity.created(URI.create("/api/v1/ltl/rate-cards/" + id))
                .body(new IdResponse(id));
    }

    @GetMapping("/receivables")
    public Slice<DocketReceivableView> receivables(
            @RequestParam(required = false) UUID customerPartnerId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return queries.list(customerPartnerId, status, cursor, limit);
    }

    public record AssignZoneRequest(
            @NotBlank String zoneCode,
            /** True when delivering to this terminal earns the ODA surcharge. */
            Boolean outOfDeliveryArea) {
    }

    public record PublishRateCardRequest(
            @NotBlank String code,
            @NotBlank String originZone,
            @NotBlank String destinationZone,
            String currency,
            @NotNull LocalDate effectiveFrom,
            @NotNull LtlRateCard.RateBasis rateBasis,
            @NotNull @PositiveOrZero BigDecimal rateAmount,
            @PositiveOrZero BigDecimal minimumChargeableWeightKg,
            @NotNull @Positive BigDecimal kgPerCft,
            @PositiveOrZero BigDecimal docketCharge,
            /** The flat ODA amount; used when {@code odaBasis} is FLAT or omitted. */
            @PositiveOrZero BigDecimal odaSurcharge,
            /** FLAT when omitted. */
            LtlRateCard.OdaBasis odaBasis,
            /** Required when {@code odaBasis} is PER_KG. */
            @PositiveOrZero BigDecimal odaRatePerKg,
            /** The least a PER_KG ODA charge comes to. */
            @PositiveOrZero BigDecimal odaMinimum) {
    }
}
