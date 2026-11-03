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
package com.lms.multimodal.web;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import com.lms.multimodal.command.PublishRailTariffCommand;
import com.lms.multimodal.command.RailTariffCommandService;
import com.lms.multimodal.query.RailTariffQueryService;
import com.lms.multimodal.query.RailTariffView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Rail rate cards: versioned, immutable, one carrier and railhead pair each. */
@RestController
@RequestMapping("/api/v1/rail-tariffs")
public class RailTariffController {

    private final RailTariffCommandService tariffs;
    private final RailTariffQueryService queries;

    public RailTariffController(RailTariffCommandService tariffs, RailTariffQueryService queries) {
        this.tariffs = tariffs;
        this.queries = queries;
    }

    @GetMapping
    public Slice<RailTariffView> list(@RequestParam(required = false) String cursor,
                                      @RequestParam(required = false) Integer limit) {
        return queries.list(cursor, limit);
    }

    @GetMapping("/{id}")
    public RailTariffView get(@PathVariable UUID id) {
        return queries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RailTariff", id));
    }

    @PostMapping
    public ResponseEntity<IdResponse> publish(@Valid @RequestBody PublishRequest request) {
        UUID id = tariffs.publish(new PublishRailTariffCommand(request.code(),
                request.carrierPartnerId(), request.originTerminalId(),
                request.destinationTerminalId(), request.currency(), request.perWagonRate(),
                request.perContainerRate(), request.minimumCharge(), request.effectiveFrom()));
        return ResponseEntity.created(URI.create("/api/v1/rail-tariffs/" + id))
                .body(new IdResponse(id));
    }

    public record PublishRequest(@NotBlank String code,
                                 @NotNull UUID carrierPartnerId,
                                 @NotNull UUID originTerminalId,
                                 @NotNull UUID destinationTerminalId,
                                 String currency,
                                 @DecimalMin("0") BigDecimal perWagonRate,
                                 @DecimalMin("0") BigDecimal perContainerRate,
                                 @DecimalMin("0") BigDecimal minimumCharge,
                                 @NotNull LocalDate effectiveFrom) {
    }
}
