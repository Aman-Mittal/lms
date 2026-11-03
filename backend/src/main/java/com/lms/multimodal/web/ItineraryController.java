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
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lms.multimodal.command.ItineraryCommandService;
import com.lms.multimodal.command.PlanItineraryCommand;
import com.lms.multimodal.command.domain.ItineraryLeg;
import com.lms.multimodal.query.ItineraryQueryService;
import com.lms.multimodal.query.ItinerarySummaryView;
import com.lms.multimodal.query.ItineraryView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Multimodal itineraries (vision document 3.4).
 *
 * <p>Road legs have no endpoint for their progress on purpose: they move with
 * their trips, and a second way to say a lorry has left would sooner or later
 * disagree with the gate log. Rail legs move only here.
 */
@RestController
@RequestMapping("/api/v1/itineraries")
public class ItineraryController {

    private final ItineraryCommandService itineraries;
    private final ItineraryQueryService queries;

    public ItineraryController(ItineraryCommandService itineraries,
                               ItineraryQueryService queries) {
        this.itineraries = itineraries;
        this.queries = queries;
    }

    @GetMapping
    public Slice<ItinerarySummaryView> list(@RequestParam(required = false) String cursor,
                                            @RequestParam(required = false) Integer limit) {
        return queries.list(cursor, limit);
    }

    @GetMapping("/{id}")
    public ItineraryView get(@PathVariable UUID id) {
        return queries.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Itinerary", id));
    }

    @PostMapping
    public ResponseEntity<IdResponse> plan(@Valid @RequestBody PlanRequest request) {
        UUID id = itineraries.plan(new PlanItineraryCommand(request.shipmentRef(),
                request.currency(), request.legs().stream().map(LegRequest::toPlan).toList()));
        return ResponseEntity.created(URI.create("/api/v1/itineraries/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/{id}/legs/{seq}/milestones")
    public ResponseEntity<Void> recordMilestone(@PathVariable UUID id, @PathVariable int seq,
                                                @Valid @RequestBody MilestoneRequest request) {
        itineraries.recordRailMilestone(id, seq, request.milestone(), request.at());
        return ResponseEntity.noContent().build();
    }

    public record PlanRequest(@NotBlank String shipmentRef,
                              String currency,
                              @NotEmpty @Size(max = 10) List<@Valid @NotNull LegRequest> legs) {
    }

    /**
     * @param costAmount required for a road leg; optional for a rail leg, which
     *                   is then priced from the rail rate card in force on its
     *                   planned departure day
     */
    public record LegRequest(@NotNull ItineraryLeg.Mode mode,
                             @NotNull UUID fromTerminalId,
                             @NotNull UUID toTerminalId,
                             @NotNull Instant plannedDepartureAt,
                             @NotNull Instant plannedArrivalAt,
                             @Min(0) Integer transferDwellMinutes,
                             UUID tripId,
                             ItineraryLeg.UnitType unitType,
                             @Min(1) Integer unitCount,
                             String bookingRef,
                             String trainNo,
                             @NotNull UUID carrierPartnerId,
                             @DecimalMin("0") BigDecimal costAmount) {

        ItineraryLeg.Plan toPlan() {
            return new ItineraryLeg.Plan(mode, fromTerminalId, toTerminalId, plannedDepartureAt,
                    plannedArrivalAt, transferDwellMinutes, tripId, unitType, unitCount,
                    bookingRef, trainNo, carrierPartnerId, costAmount);
        }
    }

    /**
     * @param at optional; when it happened. Now if not given.
     */
    public record MilestoneRequest(@NotNull ItineraryLeg.Status milestone, Instant at) {
    }
}
