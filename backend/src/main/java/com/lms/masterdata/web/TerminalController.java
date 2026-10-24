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
 */package com.lms.masterdata.web;

import java.net.URI;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.lms.masterdata.command.RegisterTerminalCommand;
import com.lms.masterdata.command.TerminalCommandService;
import com.lms.masterdata.command.UpdateTerminalCommand;
import com.lms.masterdata.command.domain.Terminal;
import com.lms.masterdata.query.MasterDataQueryService;
import com.lms.masterdata.query.TerminalView;
import com.lms.shared.error.ResourceNotFoundException;
import com.lms.shared.geo.LatLon;
import com.lms.shared.query.Slice;
import com.lms.shared.web.IdResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Terminals and their geofences (vision document 3.2.3).
 *
 * <p>The two geofence shapes are two endpoints rather than one endpoint with a
 * discriminator. The command they call is a sealed interface for the same
 * reason: a polygon with a radius, or a point-radius with no centre, is not a
 * validation failure to report -- it is a request that should not be
 * expressible.
 */
@RestController
@RequestMapping("/api/v1/terminals")
public class TerminalController {

    private final TerminalCommandService terminals;
    private final MasterDataQueryService masterData;

    public TerminalController(TerminalCommandService terminals,
                              MasterDataQueryService masterData) {
        this.terminals = terminals;
        this.masterData = masterData;
    }

    @GetMapping
    public Slice<TerminalView> list(@RequestParam(required = false) String category,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) Integer limit) {
        return masterData.listTerminals(category, cursor, limit);
    }

    @GetMapping("/{id}")
    public TerminalView get(@PathVariable UUID id) {
        return masterData.findTerminal(id)
                .orElseThrow(() -> new ResourceNotFoundException("Terminal", id));
    }

    @PostMapping("/polygon")
    public ResponseEntity<IdResponse> registerPolygon(
            @Valid @RequestBody RegisterPolygonTerminalRequest request) {
        UUID id = terminals.register(new RegisterTerminalCommand.Polygon(
                request.orgUnitId(), request.code(), request.name(), request.category(),
                request.ring().stream().map(p -> new LatLon(p.lat(), p.lon())).toList(),
                request.dockCount(), request.opensAt(), request.closesAt(),
                request.avgDwellMinutes(),
                request.permittedVehicleTypes() == null ? List.of()
                        : request.permittedVehicleTypes()));
        return ResponseEntity.created(URI.create("/api/v1/terminals/" + id))
                .body(new IdResponse(id));
    }

    @PostMapping("/point-radius")
    public ResponseEntity<IdResponse> registerPointRadius(
            @Valid @RequestBody RegisterPointRadiusTerminalRequest request) {
        UUID id = terminals.register(new RegisterTerminalCommand.PointRadius(
                request.orgUnitId(), request.code(), request.name(), request.category(),
                new LatLon(request.centre().lat(), request.centre().lon()),
                request.radiusMetres(), request.dockCount(), request.opensAt(),
                request.closesAt(), request.avgDwellMinutes(),
                request.permittedVehicleTypes() == null ? List.of()
                        : request.permittedVehicleTypes()));
        return ResponseEntity.created(URI.create("/api/v1/terminals/" + id))
                .body(new IdResponse(id));
    }

    /**
     * Changes a terminal. One endpoint, unlike registration: the code,
     * category and unit are not in the body, so there is nothing a shape could
     * contradict, and omitting the geofence keeps the present one. Sending both
     * shapes, or half of the circle, is a 400.
     */
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(@PathVariable UUID id,
                                       @Valid @RequestBody UpdateTerminalRequest request) {
        terminals.update(new UpdateTerminalCommand(id, request.name(), request.version(),
                request.geofence(), request.dockCount(), request.opensAt(), request.closesAt(),
                request.avgDwellMinutes(), request.permittedVehicleTypes() == null
                        ? List.of() : request.permittedVehicleTypes()));
        return ResponseEntity.noContent().build();
    }

    /** Removes a terminal nothing refers to; 409 {@code terminal-in-use} otherwise. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        terminals.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** A coordinate pair, in the order a human writes one. */
    public record Point(@NotNull Double lat, @NotNull Double lon) {
    }

    public record RegisterPolygonTerminalRequest(
            @NotNull UUID orgUnitId,
            @NotBlank String code,
            @NotBlank String name,
            @NotNull Terminal.FunctionalCategory category,
            @NotNull List<Point> ring,
            Integer dockCount,
            LocalTime opensAt,
            LocalTime closesAt,
            Integer avgDwellMinutes,
            List<String> permittedVehicleTypes) {
    }

    public record RegisterPointRadiusTerminalRequest(
            @NotNull UUID orgUnitId,
            @NotBlank String code,
            @NotBlank String name,
            @NotNull Terminal.FunctionalCategory category,
            @NotNull Point centre,
            @Positive double radiusMetres,
            Integer dockCount,
            LocalTime opensAt,
            LocalTime closesAt,
            Integer avgDwellMinutes,
            List<String> permittedVehicleTypes) {
    }

    public record UpdateTerminalRequest(
            @NotBlank String name,
            @NotNull Long version,
            List<Point> ring,
            Point centre,
            @Positive Double radiusMetres,
            Integer dockCount,
            LocalTime opensAt,
            LocalTime closesAt,
            Integer avgDwellMinutes,
            List<String> permittedVehicleTypes) {

        /** The geofence the body asks for, null if it leaves it alone. */
        UpdateTerminalCommand.Geofence geofence() {
            // An empty ring is no ring: some clients send an empty list for an unset one.
            boolean polygon = ring != null && !ring.isEmpty();
            boolean circle = centre != null || radiusMetres != null;
            if (polygon && circle) {
                throw new IllegalArgumentException(
                        "Send a polygon ring or a centre with a radius, not both");
            }
            if (polygon) {
                return new UpdateTerminalCommand.Polygon(
                        ring.stream().map(p -> new LatLon(p.lat(), p.lon())).toList());
            }
            if (circle) {
                if (centre == null || radiusMetres == null) {
                    throw new IllegalArgumentException(
                            "A circular geofence needs both a centre and a radius");
                }
                return new UpdateTerminalCommand.PointRadius(
                        new LatLon(centre.lat(), centre.lon()), radiusMetres);
            }
            return null;
        }
    }
}
