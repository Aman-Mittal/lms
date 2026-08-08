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
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.finance.command.TariffCommandService;
import com.lms.finance.command.domain.TariffSlab;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishing rate cards (vision document 3.9.1).
 *
 * <p>POST only, and that is the contract rather than an omission. A rate card
 * is a version: correcting one means publishing a successor, which closes the
 * incumbent. A PUT or PATCH here would offer an operation the database refuses
 * by trigger, so the API does not offer it either.
 */
@RestController
@RequestMapping("/api/v1/tariffs")
public class TariffController {

    private final TariffCommandService tariffs;

    public TariffController(TariffCommandService tariffs) {
        this.tariffs = tariffs;
    }

    @PostMapping
    public ResponseEntity<IdResponse> publish(@Valid @RequestBody PublishTariffRequest request) {
        UUID id = tariffs.publishTariff(request.orgUnitId(), request.code(),
                request.vendorPartnerId(), request.originTerminalId(),
                request.destinationTerminalId(), request.vehicleType(), request.currency(),
                request.effectiveFrom(), request.detentionFreeHours(),
                request.detentionHourlyRate(), request.additionalDropFee(),
                request.minimumCharge());
        return ResponseEntity.created(URI.create("/api/v1/tariffs/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/slabs")
    public ResponseEntity<IdResponse> addSlab(@PathVariable UUID id,
                                              @Valid @RequestBody AddSlabRequest request) {
        UUID slabId = tariffs.addSlab(id, request.minWeightKg(), request.maxWeightKg(),
                request.rateBasis(), request.rateAmount());
        return ResponseEntity.created(URI.create("/api/v1/tariffs/" + id + "/slabs"))
                .body(new IdResponse(slabId));
    }

    /** The monthly index the fuel surcharge is taken from. */
    @PostMapping("/fuel-surcharges")
    public ResponseEntity<IdResponse> recordFuelSurcharge(
            @Valid @RequestBody FuelSurchargeRequest request) {
        UUID id = tariffs.recordFuelSurcharge(request.month(), request.surchargePct());
        return ResponseEntity.created(URI.create("/api/v1/tariffs/fuel-surcharges"))
                .body(new IdResponse(id));
    }

    public record PublishTariffRequest(
            @NotNull UUID orgUnitId,
            @NotBlank String code,
            @NotNull UUID vendorPartnerId,
            @NotNull UUID originTerminalId,
            @NotNull UUID destinationTerminalId,
            @NotBlank String vehicleType,
            String currency,
            @NotNull LocalDate effectiveFrom,
            @PositiveOrZero BigDecimal detentionFreeHours,
            @PositiveOrZero BigDecimal detentionHourlyRate,
            @PositiveOrZero BigDecimal additionalDropFee,
            @PositiveOrZero BigDecimal minimumCharge) {
    }

    /**
     * A weight band.
     *
     * <p>{@code maxWeightKg} may be omitted, which opens the top band to any
     * weight above the floor. Bands are half-open, so the upper bound is the
     * first weight the band does <em>not</em> cover.
     */
    public record AddSlabRequest(
            @NotNull @PositiveOrZero BigDecimal minWeightKg,
            BigDecimal maxWeightKg,
            @NotNull TariffSlab.RateBasis rateBasis,
            @NotNull @PositiveOrZero BigDecimal rateAmount) {
    }

    /** Any day in the month; the service normalises to the first. */
    public record FuelSurchargeRequest(
            @NotNull LocalDate month,
            @NotNull BigDecimal surchargePct) {
    }
}
